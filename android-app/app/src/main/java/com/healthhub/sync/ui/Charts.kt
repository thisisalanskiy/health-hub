package com.healthhub.sync.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.healthhub.sync.dashboard.ChartSeries
import kotlin.math.roundToInt

// Non-compact mode is used exclusively by ChartDetailScreen (a full screen's worth of
// vertical space to work with) now that DashboardMetricCard always renders compact=true —
// 180dp was sized for the old on-dashboard charts sharing space with 7 others; nothing
// depends on that size anymore, so this can be sized for a dedicated full-screen chart.
private const val CHART_DETAIL_HEIGHT_DP = 280

// Compact (dashboard card) charts now draw the same gridlines/axis labels as the full
// detail chart, just smaller — 60dp was sized for a bare sparkline with no axis, which
// looked cramped once axes were added and made bar charts read as a mushed block of color.
private const val CARD_CHART_HEIGHT_DP = 130
private const val LEFT_AXIS_WIDTH_DP = 42
private const val BOTTOM_AXIS_HEIGHT_DP = 20

/** Geometry of the plotting area after reserving space for the value axis (left) and date axis (bottom). */
internal data class PlotArea(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

// Takes `size` explicitly (rather than reading a `DrawScope.size`/`PointerInputScope.size`
// implicitly) so this single helper resolves both inside a Canvas draw block (a `DrawScope`)
// and inside a `.pointerInput { detectTapGestures { ... } }` tap handler (a `PointerInputScope`)
// — the two scopes expose incompatible `size` properties (`Size` vs `IntSize`) and share no
// common supertype beyond `Density`, so a zero-arg, `DrawScope`-only version cannot be called
// from the tap handlers at all.
internal fun Density.plotArea(size: Size): PlotArea {
    val leftAxisPx = LEFT_AXIS_WIDTH_DP.dp.toPx()
    val bottomAxisPx = BOTTOM_AXIS_HEIGHT_DP.dp.toPx()
    return PlotArea(left = leftAxisPx, top = 0f, right = size.width, bottom = size.height - bottomAxisPx)
}

@Composable
internal fun rememberAxisPaint(color: Color): Paint = remember(color) {
    Paint().apply {
        this.color = color.toArgb()
        textSize = 26f
        isAntiAlias = true
    }
}

/** Draws horizontal gridlines + value labels at min/mid/max, and date labels at first/mid/last. */
internal fun DrawScope.drawAxes(
    plot: PlotArea,
    minV: Float,
    maxV: Float,
    labels: List<String>,
    axisColor: Color,
    textPaint: Paint,
) {
    val range = (maxV - minV).takeIf { it > 0f } ?: 1f
    val gridlineWidth = 1.dp.toPx()
    listOf(minV, (minV + maxV) / 2f, maxV).distinct().forEach { v ->
        val y = plot.bottom - ((v - minV) / range) * plot.height
        drawLine(axisColor.copy(alpha = 0.15f), Offset(plot.left, y), Offset(plot.right, y), strokeWidth = gridlineWidth)
        val label = if (v >= 1000f) "%,.0f".format(v) else "%.1f".format(v)
        drawContext.canvas.nativeCanvas.drawText(label, 0f, (y + 9f).coerceIn(10f, size.height - 2f), textPaint)
    }
    if (labels.size >= 2) {
        listOf(0, labels.size / 2, labels.size - 1).distinct().forEach { i ->
            val x = plot.left + (i.toFloat() / (labels.size - 1)) * plot.width
            val label = labels[i].takeLast(5) // last 5 chars of an ISO "yyyy-MM-dd" (-> "MM-dd") or a week label
            drawContext.canvas.nativeCanvas.drawText(label, (x - 24f).coerceAtLeast(plot.left), size.height, textPaint)
        }
    }
}

/**
 * Draws a smoothly-curved line through [points] as one continuous [Path] with round joins,
 * instead of N independent straight-segment [DrawScope.drawLine] calls. Independent segments
 * left visible notches/gaps at sharp-angle joints once the stroke was thick enough to notice
 * (fixed separately from a near-invisible 1px hairline to a real density-scaled width) — a
 * butt-capped segment doesn't know about its neighbor, so two segments meeting at a sharp
 * angle don't fill the wedge between them. A single Path with round joins/caps has no such
 * gaps by construction.
 *
 * Uses a Catmull-Rom spline (converted to cubic Bezier segments) rather than a simpler
 * midpoint-quadratic technique: a first attempt at the latter curved through the *midpoint*
 * of each consecutive pair, using the real data points only as off-curve control points —
 * which put the tap-to-inspect dot (drawn at the real point) visibly off the curve at any
 * local peak or valley, since the curve never actually touched interior points. Catmull-Rom
 * passes exactly through every point, so the same tap-marker code lands exactly on the curve.
 */
internal fun DrawScope.drawSmoothLine(points: List<Offset>, color: Color, strokeWidth: Float) {
    if (points.size < 2) return
    if (points.size == 2) {
        drawLine(color, points[0], points[1], strokeWidth = strokeWidth, cap = StrokeCap.Round)
        return
    }
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        for (i in 0 until points.size - 1) {
            val p0 = points[(i - 1).coerceAtLeast(0)]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points[(i + 2).coerceAtMost(points.size - 1)]
            val control1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
            val control2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
            cubicTo(control1.x, control1.y, control2.x, control2.y, p2.x, p2.y)
        }
    }
    drawPath(path, color = color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Finds the nearest data index to a tap's x-offset within an evenly-spaced plot area. */
internal fun nearestIndex(tapX: Float, plot: PlotArea, count: Int): Int {
    if (count < 2) return 0
    val stepX = plot.width / (count - 1)
    return ((tapX - plot.left) / stepX).roundToInt().coerceIn(0, count - 1)
}

/**
 * Draws the tap-to-inspect legend as a filled, rounded chip behind its text rather than bare
 * text laid directly over the chart — bare text was frequently unreadable against whatever
 * chart line/bar/gridline happened to sit underneath it.
 */
internal fun DrawScope.drawTapLabel(label: String, anchorX: Float, anchorY: Float, plot: PlotArea, textPaint: Paint, chipColor: Color) {
    val textWidth = textPaint.measureText(label)
    val paddingH = 8.dp.toPx()
    val paddingV = 5.dp.toPx()
    val chipWidth = textWidth + paddingH * 2
    val chipHeight = textPaint.textSize + paddingV * 2
    val left = (anchorX - chipWidth / 2f).coerceIn(plot.left, (plot.right - chipWidth).coerceAtLeast(plot.left))
    val top = (anchorY - chipHeight).coerceIn(0f, (size.height - chipHeight).coerceAtLeast(0f))
    drawRoundRect(
        color = chipColor,
        topLeft = Offset(left, top),
        size = Size(chipWidth, chipHeight),
        cornerRadius = CornerRadius(6.dp.toPx()),
    )
    drawContext.canvas.nativeCanvas.drawText(label, left + paddingH, top + chipHeight - paddingV - 2f, textPaint)
}

@Composable
fun LineChart(
    series: ChartSeries,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    compact: Boolean = false,
) {
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val chipColor = MaterialTheme.colorScheme.surfaceVariant
    val textPaint = rememberAxisPaint(axisColor)
    var selectedIndex by remember(series) { mutableStateOf<Int?>(null) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height((if (compact) CARD_CHART_HEIGHT_DP else CHART_DETAIL_HEIGHT_DP).dp)
            .let { base ->
                if (compact) base else base.pointerInput(series) {
                    detectTapGestures { offset ->
                        if (series.values.size >= 2) {
                            selectedIndex = nearestIndex(offset.x, plotArea(size.toSize()), series.values.size)
                        }
                    }
                }
            },
    ) {
        if (series.values.size < 2) return@Canvas
        val plot = plotArea(size)
        val minV = series.values.min()
        val maxV = series.values.max()
        val range = (maxV - minV).takeIf { it > 0f } ?: 1f
        val stepX = plot.width / (series.values.size - 1)

        drawAxes(plot, minV, maxV, series.labels, axisColor, textPaint)

        val points = series.values.mapIndexed { index, value ->
            Offset(plot.left + index * stepX, plot.bottom - ((value - minV) / range) * plot.height)
        }
        // strokeWidth/radius below are dp-scaled, not raw pixel counts: on a high-density
        // display (e.g. a Fold's ~3-4x density) a literal `4f` is under 1.5dp — a near-invisible
        // hairline. .dp.toPx() keeps the line the same physical thickness on every device.
        val lineStrokeWidth = 3.dp.toPx()
        drawSmoothLine(points, lineColor, lineStrokeWidth)

        if (!compact) {
            selectedIndex?.let { idx ->
                val point = points.getOrNull(idx) ?: return@let
                drawCircle(lineColor, radius = 5.dp.toPx(), center = point)
                drawLine(lineColor.copy(alpha = 0.3f), Offset(point.x, plot.top), Offset(point.x, plot.bottom), strokeWidth = 1.dp.toPx())
                val label = "${series.labels.getOrNull(idx) ?: ""}: ${"%.1f".format(series.values[idx])}"
                drawTapLabel(label, point.x, point.y - 14f, plot, textPaint, chipColor)
            }
        }
    }
}

@Composable
fun BarChart(
    series: ChartSeries,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    compact: Boolean = false,
) {
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val chipColor = MaterialTheme.colorScheme.surfaceVariant
    val textPaint = rememberAxisPaint(axisColor)
    var selectedIndex by remember(series) { mutableStateOf<Int?>(null) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height((if (compact) CARD_CHART_HEIGHT_DP else CHART_DETAIL_HEIGHT_DP).dp)
            .let { base ->
                if (compact) base else base.pointerInput(series) {
                    detectTapGestures { offset ->
                        if (series.values.isNotEmpty()) {
                            val plot = plotArea(size.toSize())
                            val barWidth = plot.width / series.values.size
                            selectedIndex = (((offset.x - plot.left) / barWidth).toInt()).coerceIn(0, series.values.size - 1)
                        }
                    }
                }
            },
    ) {
        if (series.values.isEmpty()) return@Canvas
        val plot = plotArea(size)
        val maxV = series.values.max().takeIf { it > 0f } ?: 1f
        val barWidth = plot.width / series.values.size

        drawAxes(plot, 0f, maxV, series.labels, axisColor, textPaint)

        series.values.forEachIndexed { index, value ->
            val barHeight = (value / maxV) * plot.height
            drawRect(
                color = barColor,
                topLeft = Offset(plot.left + index * barWidth + barWidth * 0.1f, plot.bottom - barHeight),
                size = Size(barWidth * 0.8f, barHeight),
            )
        }

        if (!compact) {
            selectedIndex?.let { idx ->
                val value = series.values.getOrNull(idx) ?: return@let
                val x = plot.left + idx * barWidth + barWidth / 2f
                val barHeight = (value / maxV) * plot.height
                val label = "${series.labels.getOrNull(idx) ?: ""}: ${"%,.0f".format(value)}"
                drawTapLabel(label, x, plot.bottom - barHeight - 10f, plot, textPaint, chipColor)
            }
        }
    }
}

@Composable
fun StackedBarChart(
    seriesByStack: Map<String, ChartSeries>,
    colors: Map<String, Color>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val chipColor = MaterialTheme.colorScheme.surfaceVariant
    val textPaint = rememberAxisPaint(axisColor)
    val labels = seriesByStack.values.firstOrNull()?.labels ?: emptyList()
    var selectedIndex by remember(seriesByStack) { mutableStateOf<Int?>(null) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height((if (compact) CARD_CHART_HEIGHT_DP else CHART_DETAIL_HEIGHT_DP).dp)
            .let { base ->
                if (compact) base else base.pointerInput(seriesByStack) {
                    detectTapGestures { offset ->
                        if (labels.isNotEmpty()) {
                            val plot = plotArea(size.toSize())
                            val barWidth = plot.width / labels.size
                            selectedIndex = (((offset.x - plot.left) / barWidth).toInt()).coerceIn(0, labels.size - 1)
                        }
                    }
                }
            },
    ) {
        if (labels.isEmpty()) return@Canvas
        val plot = plotArea(size)
        val totalsPerDay = labels.indices.map { dayIndex ->
            seriesByStack.values.sumOf { it.values.getOrElse(dayIndex) { 0f }.toDouble() }
        }
        val maxTotal = totalsPerDay.max().takeIf { it > 0.0 } ?: 1.0
        val barWidth = plot.width / labels.size

        drawAxes(plot, 0f, maxTotal.toFloat(), labels, axisColor, textPaint)

        labels.indices.forEach { dayIndex ->
            var yOffset = plot.bottom
            for ((stackName, series) in seriesByStack) {
                val value = series.values.getOrElse(dayIndex) { 0f }
                val barHeight = (value / maxTotal).toFloat() * plot.height
                drawRect(
                    color = colors[stackName] ?: Color.Gray,
                    topLeft = Offset(plot.left + dayIndex * barWidth + barWidth * 0.1f, yOffset - barHeight),
                    size = Size(barWidth * 0.8f, barHeight),
                )
                yOffset -= barHeight
            }
        }

        if (!compact) {
            selectedIndex?.let { idx ->
                val breakdown = seriesByStack.entries.joinToString(", ") { (name, series) ->
                    "$name ${"%,.0f".format(series.values.getOrElse(idx) { 0f })}"
                }
                val label = "${labels.getOrNull(idx) ?: ""}: $breakdown"
                val x = plot.left + idx * barWidth + barWidth / 2f
                drawTapLabel(label, x, 20f + textPaint.textSize, plot, textPaint, chipColor)
            }
        }
    }
}

@Composable
fun MultiLineChart(
    seriesByLine: Map<String, ChartSeries>,
    colors: Map<String, Color>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val chipColor = MaterialTheme.colorScheme.surfaceVariant
    val textPaint = rememberAxisPaint(axisColor)
    val allValues = seriesByLine.values.flatMap { it.values }
    val allLabels = seriesByLine.values.maxByOrNull { it.labels.size }?.labels ?: emptyList()
    var selectedIndex by remember(seriesByLine) { mutableStateOf<Int?>(null) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height((if (compact) CARD_CHART_HEIGHT_DP else CHART_DETAIL_HEIGHT_DP).dp)
            .let { base ->
                if (compact) base else base.pointerInput(seriesByLine) {
                    detectTapGestures { offset ->
                        if (allLabels.size >= 2) {
                            selectedIndex = nearestIndex(offset.x, plotArea(size.toSize()), allLabels.size)
                        }
                    }
                }
            },
    ) {
        if (allValues.isEmpty()) return@Canvas
        val plot = plotArea(size)
        val minV = allValues.min()
        val maxV = allValues.max()
        val range = (maxV - minV).takeIf { it > 0f } ?: 1f

        drawAxes(plot, minV, maxV, allLabels, axisColor, textPaint)

        val lineStrokeWidth = 3.dp.toPx()
        val pointsByLine = mutableMapOf<String, List<Offset>>()
        for ((name, series) in seriesByLine) {
            if (series.values.size < 2) continue
            val stepX = plot.width / (series.values.size - 1)
            val points = series.values.mapIndexed { index, value ->
                Offset(plot.left + index * stepX, plot.bottom - ((value - minV) / range) * plot.height)
            }
            pointsByLine[name] = points
            val color = colors[name] ?: Color.Gray
            drawSmoothLine(points, color, lineStrokeWidth)
        }

        if (!compact) {
            selectedIndex?.let { idx ->
                // Paired (name, point) rather than two parallel lists indexed together: a
                // shorter line (fewer points than another at this index) is dropped by
                // mapNotNull, which would silently misalign a position-based color lookup.
                val anchorEntries = pointsByLine.mapNotNull { (name, points) -> points.getOrNull(idx)?.let { name to it } }
                if (anchorEntries.isEmpty()) return@let
                anchorEntries.forEach { (name, point) ->
                    drawCircle(colors[name] ?: Color.Gray, radius = 5.dp.toPx(), center = point)
                }
                val breakdown = seriesByLine.entries.joinToString(", ") { (name, series) ->
                    val value = series.values.getOrNull(idx)
                    "$name ${value?.let { "%,.0f".format(it) } ?: "–"}"
                }
                val label = "${allLabels.getOrNull(idx) ?: ""}: $breakdown"
                val anchorX = anchorEntries.first().second.x
                val anchorY = anchorEntries.minOf { it.second.y }
                drawTapLabel(label, anchorX, anchorY - 14f, plot, textPaint, chipColor)
            }
        }
    }
}
