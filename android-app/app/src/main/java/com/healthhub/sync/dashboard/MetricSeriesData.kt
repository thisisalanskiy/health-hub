package com.healthhub.sync.dashboard

import androidx.compose.ui.graphics.Color

sealed class MetricSeriesData {
    data class Line(val series: ChartSeries) : MetricSeriesData()
    data class Bar(val series: ChartSeries) : MetricSeriesData()
    data class Stacked(val seriesByStack: Map<String, ChartSeries>, val colors: Map<String, Color>) : MetricSeriesData()
    data class MultiLine(val seriesByLine: Map<String, ChartSeries>, val colors: Map<String, Color>) : MetricSeriesData()
}
