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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.healthhub.sync.dashboard.ChartSeries
import kotlin.math.roundToInt

private const val DEFAULT_HEIGHT_DP = 180
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
    listOf(minV, (minV + maxV) / 2f, maxV).distinct().forEach { v ->
        val y = plot.bottom - ((v - minV) / range) * plot.height
        drawLine(axisColor.copy(alpha = 0.15f), Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1f)
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

/** Finds the nearest data index to a tap's x-offset within an evenly-spaced plot area. */
internal fun nearestIndex(tapX: Float, plot: PlotArea, count: Int): Int {
    if (count < 2) return 0
    val stepX = plot.width / (count - 1)
    return ((tapX - plot.left) / stepX).roundToInt().coerceIn(0, count - 1)
}

@Composable
fun LineChart(
    series: ChartSeries,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    compact: Boolean = false,
) {
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textPaint = rememberAxisPaint(axisColor)
    var selectedIndex by remember(series) { mutableStateOf<Int?>(null) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height((if (compact) 60 else DEFAULT_HEIGHT_DP).dp)
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

        if (!compact) drawAxes(plot, minV, maxV, series.labels, axisColor, textPaint)

        val points = series.values.mapIndexed { index, value ->
            Offset(plot.left + index * stepX, plot.bottom - ((value - minV) / range) * plot.height)
        }
        for (i in 0 until points.size - 1) {
            drawLine(color = lineColor, start = points[i], end = points[i + 1], strokeWidth = 4f)
        }

        if (!compact) {
            selectedIndex?.let { idx ->
                val point = points.getOrNull(idx) ?: return@let
                drawCircle(lineColor, radius = 6f, center = point)
                drawLine(lineColor.copy(alpha = 0.3f), Offset(point.x, plot.top), Offset(point.x, plot.bottom), strokeWidth = 1f)
                val label = "${series.labels.getOrNull(idx) ?: ""}: ${"%.1f".format(series.values[idx])}"
                val textX = (point.x - 70f).coerceIn(plot.left, (plot.right - 140f).coerceAtLeast(plot.left))
                val textY = (point.y - 14f).coerceAtLeast(16f)
                drawContext.canvas.nativeCanvas.drawText(label, textX, textY, textPaint)
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
    val textPaint = rememberAxisPaint(axisColor)
    var selectedIndex by remember(series) { mutableStateOf<Int?>(null) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height((if (compact) 60 else DEFAULT_HEIGHT_DP).dp)
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

        if (!compact) drawAxes(plot, 0f, maxV, series.labels, axisColor, textPaint)

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
                val label = "${series.labels.getOrNull(idx) ?: ""}: ${"%.0f".format(value)}"
                val textX = (x - 70f).coerceIn(plot.left, (plot.right - 140f).coerceAtLeast(plot.left))
                val textY = (plot.bottom - barHeight - 10f).coerceAtLeast(16f)
                drawContext.canvas.nativeCanvas.drawText(label, textX, textY, textPaint)
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
    val textPaint = rememberAxisPaint(axisColor)
    val labels = seriesByStack.values.firstOrNull()?.labels ?: emptyList()
    var selectedIndex by remember(seriesByStack) { mutableStateOf<Int?>(null) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height((if (compact) 60 else DEFAULT_HEIGHT_DP).dp)
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

        if (!compact) drawAxes(plot, 0f, maxTotal.toFloat(), labels, axisColor, textPaint)

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
                    "$name ${"%.0f".format(series.values.getOrElse(idx) { 0f })}"
                }
                val label = "${labels.getOrNull(idx) ?: ""}: $breakdown"
                val x = plot.left + idx * barWidth + barWidth / 2f
                val textX = (x - 90f).coerceIn(plot.left, (plot.right - 180f).coerceAtLeast(plot.left))
                drawContext.canvas.nativeCanvas.drawText(label, textX, 20f, textPaint)
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
    val textPaint = rememberAxisPaint(axisColor)
    val allValues = seriesByLine.values.flatMap { it.values }
    val allLabels = seriesByLine.values.maxByOrNull { it.labels.size }?.labels ?: emptyList()

    Canvas(modifier = modifier.fillMaxWidth().height((if (compact) 60 else 180).dp)) {
        if (allValues.isEmpty()) return@Canvas
        val plot = plotArea(size)
        val minV = allValues.min()
        val maxV = allValues.max()
        val range = (maxV - minV).takeIf { it > 0f } ?: 1f

        if (!compact) drawAxes(plot, minV, maxV, allLabels, axisColor, textPaint)

        for ((name, series) in seriesByLine) {
            if (series.values.size < 2) continue
            val stepX = plot.width / (series.values.size - 1)
            val points = series.values.mapIndexed { index, value ->
                Offset(plot.left + index * stepX, plot.bottom - ((value - minV) / range) * plot.height)
            }
            val color = colors[name] ?: Color.Gray
            for (i in 0 until points.size - 1) {
                drawLine(color = color, start = points[i], end = points[i + 1], strokeWidth = 4f)
            }
        }
    }
}
