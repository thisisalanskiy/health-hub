package com.healthhub.sync.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.healthhub.sync.dashboard.ChartSeries

private const val DEFAULT_HEIGHT_DP = 160

@Composable
fun LineChart(
    series: ChartSeries,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(DEFAULT_HEIGHT_DP.dp)) {
        if (series.values.size < 2) return@Canvas
        val minV = series.values.min()
        val maxV = series.values.max()
        val range = (maxV - minV).takeIf { it > 0f } ?: 1f
        val stepX = size.width / (series.values.size - 1)

        val points = series.values.mapIndexed { index, value ->
            val x = index * stepX
            val y = size.height - ((value - minV) / range) * size.height
            Offset(x, y)
        }
        for (i in 0 until points.size - 1) {
            drawLine(color = lineColor, start = points[i], end = points[i + 1], strokeWidth = 4f)
        }
    }
}

@Composable
fun BarChart(
    series: ChartSeries,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(DEFAULT_HEIGHT_DP.dp)) {
        if (series.values.isEmpty()) return@Canvas
        val maxV = series.values.max().takeIf { it > 0f } ?: 1f
        val barWidth = size.width / series.values.size
        series.values.forEachIndexed { index, value ->
            val barHeight = (value / maxV) * size.height
            drawRect(
                color = barColor,
                topLeft = Offset(index * barWidth + barWidth * 0.1f, size.height - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth * 0.8f, barHeight),
            )
        }
    }
}

@Composable
fun StackedBarChart(
    seriesByStack: Map<String, ChartSeries>,
    colors: Map<String, Color>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(DEFAULT_HEIGHT_DP.dp)) {
        val labels = seriesByStack.values.firstOrNull()?.labels ?: return@Canvas
        if (labels.isEmpty()) return@Canvas
        val totalsPerDay = labels.indices.map { dayIndex ->
            seriesByStack.values.sumOf { it.values.getOrElse(dayIndex) { 0f }.toDouble() }
        }
        val maxTotal = totalsPerDay.max().takeIf { it > 0.0 } ?: 1.0
        val barWidth = size.width / labels.size

        labels.indices.forEach { dayIndex ->
            var yOffset = size.height
            for ((stackName, series) in seriesByStack) {
                val value = series.values.getOrElse(dayIndex) { 0f }
                val barHeight = (value / maxTotal).toFloat() * size.height
                drawRect(
                    color = colors[stackName] ?: Color.Gray,
                    topLeft = Offset(dayIndex * barWidth + barWidth * 0.1f, yOffset - barHeight),
                    size = androidx.compose.ui.geometry.Size(barWidth * 0.8f, barHeight),
                )
                yOffset -= barHeight
            }
        }
    }
}
