package com.healthhub.sync.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.healthhub.sync.dashboard.MetricSeriesData

@Composable
fun RenderMetricChart(data: MetricSeriesData, compact: Boolean, modifier: Modifier = Modifier) {
    when (data) {
        is MetricSeriesData.Line -> LineChart(series = data.series, compact = compact, modifier = modifier)
        is MetricSeriesData.Bar -> BarChart(series = data.series, compact = compact, modifier = modifier)
        is MetricSeriesData.Stacked -> StackedBarChart(
            seriesByStack = data.seriesByStack,
            colors = data.colors,
            compact = compact,
            modifier = modifier,
        )
        is MetricSeriesData.MultiLine -> MultiLineChart(
            seriesByLine = data.seriesByLine,
            colors = data.colors,
            compact = compact,
            modifier = modifier,
        )
    }
}
