package com.healthhub.sync.dashboard

import com.healthhub.sync.digest.TdeeResponse

enum class DashboardMetric(val id: String, val title: String, val unit: String) {
    WEIGHT("weight", "Weight", "kg"),
    STEPS("steps", "Steps", "count"),
    HEART_RATE("heart_rate", "Heart rate (avg)", "bpm"),
    RESTING_HEART_RATE("resting_heart_rate", "Resting heart rate", "bpm"),
    SLEEP("sleep", "Sleep stages", "min/night"),
    WORKOUTS("workouts", "Workouts", "min/week"),
    NUTRITION("nutrition", "Nutrition macros", "g/day"),
    TDEE("tdee", "TDEE", "kcal/day"),
}

/**
 * The compact card's single headline value. Reuses each metric's existing
 * current-value text from the pre-drill-down dashboard, except: Sleep and
 * Nutrition previously had no headline value at all (added here — total
 * sleep and total macros for the most recent day, respectively), and TDEE
 * is simplified from "Calculated: X · MacroFactor: Y" to a single number
 * (the detail screen still shows the full calculated-vs-MacroFactor
 * breakdown) so every compact card shows exactly one headline value.
 *
 * @param serverConfigured whether a server base URL is set. TDEE comes from the
 * server, not Health Connect, so with no URL configured there is nothing to fetch
 * and no error either — that case gets its own actionable message instead of a
 * bare "no data" the user can't act on.
 */
fun previewValueText(
    metric: DashboardMetric,
    data: MetricSeriesData?,
    tdeeData: TdeeResponse?,
    tdeeError: String?,
    serverConfigured: Boolean = true,
): String = when (metric) {
    DashboardMetric.WEIGHT -> (data as? MetricSeriesData.Line)?.series?.values?.lastOrNull()
        ?.let { "%.1f kg".format(it) } ?: "no data"
    DashboardMetric.STEPS -> (data as? MetricSeriesData.Bar)?.series?.values?.lastOrNull()
        ?.let { "%,d".format(it.toInt()) } ?: "no data"
    DashboardMetric.HEART_RATE -> (data as? MetricSeriesData.Line)?.series?.values?.lastOrNull()
        ?.let { "${it.toInt()} bpm" } ?: "no data"
    DashboardMetric.RESTING_HEART_RATE -> (data as? MetricSeriesData.Line)?.series?.values?.lastOrNull()
        ?.let { "${it.toInt()} bpm" } ?: "no data"
    DashboardMetric.SLEEP -> {
        val stacked = data as? MetricSeriesData.Stacked
        val lastDay = stacked?.seriesByStack?.values?.firstOrNull()?.labels?.lastOrNull()
        val totalMinutes = lastDay?.let { day ->
            stacked.seriesByStack.values.sumOf { series ->
                val idx = series.labels.indexOf(day)
                if (idx >= 0) series.values[idx].toDouble() else 0.0
            }
        }
        totalMinutes?.let { "${(it / 60).toInt()}h ${(it % 60).toInt()}m last night" } ?: "no data"
    }
    DashboardMetric.WORKOUTS -> (data as? MetricSeriesData.Bar)?.series?.values?.lastOrNull()
        ?.let { "%,d min this week".format(it.toInt()) } ?: "no data"
    DashboardMetric.NUTRITION -> {
        val stacked = data as? MetricSeriesData.Stacked
        val lastDay = stacked?.seriesByStack?.values?.firstOrNull()?.labels?.lastOrNull()
        val total = lastDay?.let { day ->
            stacked.seriesByStack.values.sumOf { series ->
                val idx = series.labels.indexOf(day)
                if (idx >= 0) series.values[idx].toDouble() else 0.0
            }
        }
        total?.let { "%,dg today".format(it.toInt()) } ?: "no data"
    }
    DashboardMetric.TDEE -> {
        val latest = tdeeData?.calculatedTdee?.lastOrNull { it != null }
            ?: tdeeData?.macrofactorTdee?.lastOrNull { it != null }
        when {
            latest != null -> "%,d kcal".format(latest.toInt())
            tdeeError != null -> tdeeError.substringBefore(":")
            !serverConfigured -> "Set server URL in Settings"
            else -> "no data"
        }
    }
}
