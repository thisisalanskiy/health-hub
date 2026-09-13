package com.healthhub.sync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.healthhub.sync.dashboard.ChartSeries
import com.healthhub.sync.dashboard.DashboardAggregator
import com.healthhub.sync.dashboard.DashboardRawData
import com.healthhub.sync.dashboard.trendDelta
import com.healthhub.sync.digest.TdeeResponse
import java.time.ZoneId

// Moved here from DashboardScreen.kt, verbatim, along with their only callers
// (the chart list below) -- both were `private` to that file, which would have
// made them uncallable from here otherwise (Kotlin's `private` on a top-level
// declaration is file-scoped, not package-scoped).

private fun buildTdeeSeries(dates: List<String>, values: List<Double?>): ChartSeries {
    val pairs = dates.zip(values).filter { it.second != null }
    return ChartSeries(pairs.map { it.first }, pairs.map { it.second!!.toFloat() })
}

@Composable
private fun ChartHeader(title: String, unit: String, currentValue: String, trend: Float?, trendUnit: String) {
    Column {
        Text("$title ($unit)", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(currentValue, style = MaterialTheme.typography.bodyMedium)
            if (trend != null && kotlin.math.abs(trend) > 0.01f) {
                val arrow = if (trend > 0) "▲" else "▼"
                Box(modifier = Modifier.width(6.dp))
                Text(
                    "$arrow ${"%+.1f".format(trend)} $trendUnit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

fun buildDashboardCharts(
    data: DashboardRawData,
    tdeeData: TdeeResponse?,
    tdeeError: String?,
    zone: ZoneId,
): List<@Composable () -> Unit> = listOf(
    {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val series = DashboardAggregator.dailyWeightTrend(data, zone)
            val current = series.values.lastOrNull()?.let { "%.1f kg".format(it) } ?: "no data"
            ChartHeader("Weight", "kg", current, trendDelta(series), "kg")
            LineChart(series = series)
        }
    },
    {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val series = DashboardAggregator.dailySteps(data, zone)
            val current = series.values.lastOrNull()?.let { "${it.toInt()}" } ?: "no data"
            ChartHeader("Steps", "count", current, trendDelta(series), "steps/day")
            BarChart(series = series)
        }
    },
    {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val (_, avg, _) = DashboardAggregator.dailyHeartRate(data, zone)
            val current = avg.values.lastOrNull()?.let { "${it.toInt()} bpm" } ?: "no data"
            ChartHeader("Heart rate (avg)", "bpm", current, trendDelta(avg), "bpm")
            LineChart(series = avg)
        }
    },
    {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val series = DashboardAggregator.dailyRestingHeartRate(data, zone)
            val current = series.values.lastOrNull()?.let { "${it.toInt()} bpm" } ?: "no data"
            ChartHeader("Resting heart rate", "bpm", current, trendDelta(series), "bpm")
            LineChart(series = series)
        }
    },
    {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Sleep stages (min/night)", style = MaterialTheme.typography.titleMedium)
            StackedBarChart(
                seriesByStack = DashboardAggregator.nightlySleepStages(data, zone),
                colors = mapOf(
                    "deep" to Color(0xFF1A237E),
                    "rem" to Color(0xFF4A148C),
                    "core" to Color(0xFF1565C0),
                    "awake" to Color(0xFFB71C1C),
                    "other" to Color(0xFF888888),
                ),
            )
        }
    },
    {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val series = DashboardAggregator.weeklyWorkoutMinutes(data, zone)
            val current = series.values.lastOrNull()?.let { "${it.toInt()} min this week" } ?: "no data"
            Text("Workouts (min/week)", style = MaterialTheme.typography.titleMedium)
            Text(current, style = MaterialTheme.typography.bodyMedium)
            BarChart(series = series)
        }
    },
    {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val (protein, fat, carbs) = DashboardAggregator.dailyNutritionMacros(data, zone)
            Text("Nutrition macros (g/day)", style = MaterialTheme.typography.titleMedium)
            StackedBarChart(
                seriesByStack = mapOf("protein" to protein, "fat" to fat, "carbs" to carbs),
                colors = mapOf(
                    "protein" to Color(0xFF1565C0),
                    "fat" to Color(0xFFE65100),
                    "carbs" to Color(0xFF2E7D32),
                ),
            )
        }
    },
    {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("TDEE (kcal/day)", style = MaterialTheme.typography.titleMedium)
            val tdee = tdeeData
            val err = tdeeError
            when {
                tdee != null && (tdee.calculatedTdee.any { it != null } || tdee.macrofactorTdee.any { it != null }) -> {
                    val calculated = buildTdeeSeries(tdee.dates, tdee.calculatedTdee)
                    val macrofactor = buildTdeeSeries(tdee.dates, tdee.macrofactorTdee)
                    val latestCalculated = tdee.calculatedTdee.lastOrNull { it != null }
                    val latestMacrofactor = tdee.macrofactorTdee.lastOrNull { it != null }
                    Text(
                        "Calculated: ${latestCalculated?.let { "%.0f".format(it) } ?: "n/a"} · MacroFactor: ${latestMacrofactor?.let { "%.0f".format(it) } ?: "n/a"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    MultiLineChart(
                        seriesByLine = mapOf("calculated" to calculated, "macrofactor" to macrofactor),
                        colors = mapOf(
                            "calculated" to Color(0xFF1565C0),
                            "macrofactor" to Color(0xFFE65100),
                        ),
                    )
                }
                err != null -> Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else -> Text("Set your server URL in Settings to see TDEE.", style = MaterialTheme.typography.bodySmall)
            }
        }
    },
)
