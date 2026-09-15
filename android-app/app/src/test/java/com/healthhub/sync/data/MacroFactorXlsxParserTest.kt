package com.healthhub.sync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Excel serial dates count days since 1899-12-30 (an epoch chosen to reproduce Excel's
// 1900-leap-year bug without needing a separate correction for dates after Feb 1900 —
// see LocalDate.of(1899, 12, 30).plusDays(serial) in the parser). Verified against the
// well-known anchor pair serial 44197 == 2021-01-01:
//   LocalDate.of(1899, 12, 30).plusDays(44197) == LocalDate.of(2021, 1, 1)
// From that same formula, 2026-08-01 is serial 46235 and 2026-08-02 is serial 46236.
private fun buildFixtureXlsx(): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        zip.putNextEntry(ZipEntry("xl/workbook.xml"))
        zip.write(
            """
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheets>
                <sheet name="Quick Export" sheetId="1" r:id="rId1" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"/>
                <sheet name="Food Log" sheetId="2" r:id="rId2" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"/>
              </sheets>
            </workbook>
            """.trimIndent().toByteArray()
        )
        zip.closeEntry()

        // Shared strings: index 0 = header "Date", 1 = "Expenditure" — only what the
        // parser actually reads needs to be here; real exports have far more columns,
        // the parser must ignore ones it doesn't need rather than require exact position.
        zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
        zip.write(
            """
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="2" uniqueCount="2">
              <si><t>Date</t></si>
              <si><t>Expenditure</t></si>
            </sst>
            """.trimIndent().toByteArray()
        )
        zip.closeEntry()

        // Row 1: header. Row 2: date=46235 (2026-08-01), expenditure=1728.
        // Row 3: date=46236 (2026-08-02), expenditure cell MISSING entirely (sparse column).
        zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
        zip.write(
            """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
                <row r="2"><c r="A2"><v>46235</v></c><c r="B2"><v>1728</v></c></row>
                <row r="3"><c r="A3"><v>46236</v></c></row>
              </sheetData>
            </worksheet>
            """.trimIndent().toByteArray()
        )
        zip.closeEntry()

        // A second sheet ("Food Log") is present, matching the real export, to prove
        // the parser resolves "Quick Export" by name rather than always reading sheet1.
        zip.putNextEntry(ZipEntry("xl/worksheets/sheet2.xml"))
        zip.write(
            """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1"><c r="A1" t="s"><v>0</v></c></row>
              </sheetData>
            </worksheet>
            """.trimIndent().toByteArray()
        )
        zip.closeEntry()
    }
    return out.toByteArray()
}

class MacroFactorXlsxParserTest {

    @Test
    fun `parses the Quick Export sheet's date and expenditure`() {
        val rows = parseMacroFactorExport(buildFixtureXlsx())
        assertEquals(2, rows.size)
        assertEquals(LocalDate.of(2026, 8, 1), rows[0].date)
        assertEquals(1728.0, rows[0].expenditureKcal)
    }

    @Test
    fun `a row with a missing cell parses with null for that field, not a crash`() {
        val rows = parseMacroFactorExport(buildFixtureXlsx())
        assertEquals(LocalDate.of(2026, 8, 2), rows[1].date)
        assertNull(rows[1].expenditureKcal)
    }
}
