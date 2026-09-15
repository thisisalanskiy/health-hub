package com.healthhub.sync.data

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * One day's row from a MacroFactor "Quick Export" .xlsx sheet. Any field may be null if the
 * source spreadsheet had no value in that column for that row (MacroFactor exports are sparse —
 * e.g. Trend Weight is absent until enough data has accumulated).
 */
data class MacroFactorDailyRow(
    val date: LocalDate,
    val expenditureKcal: Double?,
    val trendWeightKg: Double?,
    val weightKg: Double?,
    val caloriesKcal: Double?,
    val proteinG: Double?,
    val fatG: Double?,
    val carbsG: Double?,
)

private const val QUICK_EXPORT_SHEET_NAME = "Quick Export"

/** Maps a "Quick Export" header cell's text to the [MacroFactorDailyRow] field it fills. */
private val HEADER_TO_FIELD = mapOf(
    "Date" to "date",
    "Expenditure" to "expenditureKcal",
    "Trend Weight (kg)" to "trendWeightKg",
    "Weight (kg)" to "weightKg",
    "Calories (kcal)" to "caloriesKcal",
    "Protein (g)" to "proteinG",
    "Fat (g)" to "fatG",
    "Carbs (g)" to "carbsG",
)

private val COLUMN_LETTERS_REGEX = Regex("^([A-Za-z]+)")

/**
 * Parses a MacroFactor nutrition-app export (a real `.xlsx` — a zip containing OOXML
 * spreadsheet XML, not CSV) and returns the daily rows from its "Quick Export" sheet.
 *
 * Pure function: no Android/Room dependency, no I/O beyond reading [bytes] in memory. Uses only
 * plain JDK zip/XML APIs (`java.util.zip`, `javax.xml.parsers`) — deliberately not
 * `android.util.Xml`/`XmlPullParser`, which this project's JVM unit tests stub out to no-ops.
 */
fun parseMacroFactorExport(bytes: ByteArray): List<MacroFactorDailyRow> {
    val entries = readZipEntries(bytes)

    val workbookXml = entries["xl/workbook.xml"]
        ?: error("Not a MacroFactor export: xl/workbook.xml missing from .xlsx")
    val workbookDoc = parseXml(workbookXml)

    val sheetPath = resolveSheetPath(workbookDoc, entries, QUICK_EXPORT_SHEET_NAME)
        ?: error("Sheet named \"$QUICK_EXPORT_SHEET_NAME\" not found in workbook.xml")
    val sheetXml = entries[sheetPath]
        ?: error("Worksheet entry \"$sheetPath\" referenced by workbook.xml not found in archive")

    val sharedStrings = entries["xl/sharedStrings.xml"]
        ?.let { parseSharedStrings(parseXml(it)) }
        ?: emptyList()

    return parseQuickExportSheet(parseXml(sheetXml), sharedStrings)
}

private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
    val result = mutableMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                result[entry.name] = zip.readBytes()
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    return result
}

private fun parseXml(bytes: ByteArray): Document {
    val factory = DocumentBuilderFactory.newInstance()
    // Namespace-unaware: OOXML attribute/element names like "r:id" are then matched as plain
    // literal names, which is simpler and sufficient here since we never need to disambiguate
    // two different namespaces sharing a local name.
    factory.isNamespaceAware = false
    return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
}

/**
 * Finds the sheet named [sheetName] in `xl/workbook.xml`'s `<sheets>` and returns the zip path
 * of its worksheet XML. Resolved by name, never hardcoded to "sheet1.xml": prefers following
 * the sheet's `r:id` through `xl/_rels/workbook.xml.rels` (the fully-correct OOXML mechanism),
 * falling back to the sheet's position among `<sheet>` elements (real MacroFactor exports name
 * worksheet files `sheetN.xml` in `<sheets>` document order, and some producers omit the rels
 * file entirely).
 */
private fun resolveSheetPath(
    workbookDoc: Document,
    entries: Map<String, ByteArray>,
    sheetName: String,
): String? {
    val sheetElements = workbookDoc.getElementsByTagName("sheet")
    var position = -1
    var relationshipId: String? = null
    for (i in 0 until sheetElements.length) {
        val sheet = sheetElements.item(i) as Element
        if (sheet.getAttribute("name") == sheetName) {
            position = i + 1
            relationshipId = sheet.getAttribute("r:id").ifBlank { null }
            break
        }
    }
    if (position == -1) return null

    val relsBytes = entries["xl/_rels/workbook.xml.rels"]
    if (relsBytes != null && relationshipId != null) {
        val relsDoc = parseXml(relsBytes)
        val relationships = relsDoc.getElementsByTagName("Relationship")
        for (i in 0 until relationships.length) {
            val relationship = relationships.item(i) as Element
            if (relationship.getAttribute("Id") == relationshipId) {
                val target = relationship.getAttribute("Target").removePrefix("/xl/").removePrefix("xl/")
                return "xl/$target"
            }
        }
    }

    return "xl/worksheets/sheet$position.xml"
}

private fun parseSharedStrings(doc: Document): List<String> {
    val stringItems = doc.getElementsByTagName("si")
    val result = ArrayList<String>(stringItems.length)
    for (i in 0 until stringItems.length) {
        result.add(extractAllText(stringItems.item(i) as Element))
    }
    return result
}

/** Concatenates every `<t>` descendant's text, covering both a plain `<si><t>` and rich-text runs. */
private fun extractAllText(element: Element): String {
    val textNodes = element.getElementsByTagName("t")
    val builder = StringBuilder()
    for (i in 0 until textNodes.length) {
        builder.append(textNodes.item(i).textContent)
    }
    return builder.toString()
}

private fun parseQuickExportSheet(sheetDoc: Document, sharedStrings: List<String>): List<MacroFactorDailyRow> {
    val rowElements = sheetDoc.getElementsByTagName("row")
    if (rowElements.length == 0) return emptyList()

    // Row 1 is the header: resolve which column letter holds each field we care about, rather
    // than assuming fixed column letters — real exports may reorder columns between versions.
    val headerRow = rowElements.item(0) as Element
    val columnToField = mutableMapOf<String, String>()
    for (cell in directCellChildren(headerRow)) {
        val columnLetters = columnLettersOf(cell) ?: continue
        val headerText = cellStringValue(cell, sharedStrings) ?: continue
        val fieldKey = HEADER_TO_FIELD[headerText] ?: continue
        columnToField[columnLetters] = fieldKey
    }

    val rows = ArrayList<MacroFactorDailyRow>()
    for (i in 1 until rowElements.length) {
        val rowElement = rowElements.item(i) as Element
        val values = mutableMapOf<String, Double>()
        for (cell in directCellChildren(rowElement)) {
            val columnLetters = columnLettersOf(cell) ?: continue
            val fieldKey = columnToField[columnLetters] ?: continue
            val numericValue = cellNumericValue(cell) ?: continue
            values[fieldKey] = numericValue
        }

        val dateSerial = values["date"] ?: continue
        rows.add(
            MacroFactorDailyRow(
                date = LocalDate.of(1899, 12, 30).plusDays(dateSerial.toLong()),
                expenditureKcal = values["expenditureKcal"],
                trendWeightKg = values["trendWeightKg"],
                weightKg = values["weightKg"],
                caloriesKcal = values["caloriesKcal"],
                proteinG = values["proteinG"],
                fatG = values["fatG"],
                carbsG = values["carbsG"],
            )
        )
    }
    return rows
}

/** Direct `<c>` (cell) element children of a `<row>` — a cell absent here means "no value". */
private fun directCellChildren(rowElement: Element): List<Element> {
    val children = rowElement.childNodes
    val result = ArrayList<Element>()
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node.nodeType == Node.ELEMENT_NODE && node is Element && node.tagName == "c") {
            result.add(node)
        }
    }
    return result
}

private fun columnLettersOf(cell: Element): String? {
    val cellRef = cell.getAttribute("r")
    if (cellRef.isBlank()) return null
    return COLUMN_LETTERS_REGEX.find(cellRef)?.groupValues?.get(1)?.uppercase()
}

/** A cell's `<v>` text, resolved through sharedStrings when `t="s"`; null if `<v>` is absent. */
private fun cellStringValue(cell: Element, sharedStrings: List<String>): String? {
    val rawValue = directChildText(cell, "v") ?: return null
    return if (cell.getAttribute("t") == "s") {
        val index = rawValue.trim().toIntOrNull() ?: return null
        sharedStrings.getOrNull(index)
    } else {
        rawValue
    }
}

/** A cell's `<v>` text parsed as a number; null if `<v>` is absent (i.e. the cell has no value). */
private fun cellNumericValue(cell: Element): Double? {
    val rawValue = directChildText(cell, "v") ?: return null
    return rawValue.trim().toDoubleOrNull()
}

private fun directChildText(element: Element, tagName: String): String? {
    val children = element.childNodes
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node.nodeType == Node.ELEMENT_NODE && node is Element && node.tagName == tagName) {
            return node.textContent
        }
    }
    return null
}
