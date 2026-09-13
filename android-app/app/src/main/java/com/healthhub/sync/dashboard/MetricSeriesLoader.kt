package com.healthhub.sync.dashboard

import androidx.compose.ui.graphics.Color
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import com.healthhub.sync.data.AppPreferences
import com.healthhub.sync.data.HealthConnectManager
import com.healthhub.sync.digest.HealthApiClient
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private val SLEEP_COLORS = mapOf(
    "deep" to Color(0xFF1A237E),
    "rem" to Color(0xFF4A148C),
    "core" to Color(0xFF1565C0),
    "awake" to Color(0xFFB71C1C),
    "other" to Color(0xFF888888),
)

private val NUTRITION_COLORS = mapOf(
    "protein" to Color(0xFF1565C0),
    "fat" to Color(0xFFE65100),
    "carbs" to Color(0xFF2E7D32),
)

private val TDEE_COLORS = mapOf(
    "calculated" to Color(0xFF1565C0),
    "macrofactor" to Color(0xFFE65100),
)

private fun buildTdeeSeries(dates: List<String>, values: List<Double?>): ChartSeries {
    val pairs = dates.zip(values).filter { it.second != null }
    return ChartSeries(pairs.map { it.first }, pairs.map { it.second!!.toFloat() })
}

/** Pure: builds a metric's series from an already-fetched [DashboardRawData] window — used by the dashboard's compact preview cards. TDEE is network-only and has no offline representation, so it returns null here (its card headline comes directly from the caller's tdeeData/tdeeError instead). */
fun previewSeriesData(metric: DashboardMetric, data: DashboardRawData, zone: ZoneId): MetricSeriesData? = when (metric) {
    DashboardMetric.WEIGHT -> MetricSeriesData.Line(DashboardAggregator.dailyWeightTrend(data, zone))
    DashboardMetric.STEPS -> MetricSeriesData.Bar(DashboardAggregator.dailySteps(data, zone))
    DashboardMetric.HEART_RATE -> {
        val (_, avg, _) = DashboardAggregator.dailyHeartRate(data, zone)
        MetricSeriesData.Line(avg)
    }
    DashboardMetric.RESTING_HEART_RATE -> MetricSeriesData.Line(DashboardAggregator.dailyRestingHeartRate(data, zone))
    DashboardMetric.SLEEP -> MetricSeriesData.Stacked(DashboardAggregator.nightlySleepStages(data, zone), SLEEP_COLORS)
    DashboardMetric.WORKOUTS -> MetricSeriesData.Bar(DashboardAggregator.weeklyWorkoutMinutes(data, zone))
    DashboardMetric.NUTRITION -> {
        val (protein, fat, carbs) = DashboardAggregator.dailyNutritionMacros(data, zone)
        MetricSeriesData.Stacked(mapOf("protein" to protein, "fat" to fat, "carbs" to carbs), NUTRITION_COLORS)
    }
    DashboardMetric.TDEE -> null
}

/** The chart detail screen's independent, per-metric fetch over an arbitrary [days] window — deliberately NOT a reuse of the dashboard's shared 7-day/11-type [HealthConnectManager.readAllRecords]. */
suspend fun loadDetailSeriesData(
    metric: DashboardMetric,
    days: Int,
    healthConnectManager: HealthConnectManager,
    appPreferences: AppPreferences,
    zone: ZoneId,
): MetricSeriesData {
    val end = Instant.now()
    val start = end.minus(days.toLong(), ChronoUnit.DAYS)
    return when (metric) {
        DashboardMetric.WEIGHT -> MetricSeriesData.Line(
            DashboardAggregator.dailyWeightTrend(
                DashboardRawData(weights = healthConnectManager.readRecordsInRange(WeightRecord::class, start, end)),
                zone,
            ),
        )
        DashboardMetric.STEPS -> MetricSeriesData.Bar(
            DashboardAggregator.dailySteps(
                DashboardRawData(steps = healthConnectManager.readRecordsInRange(StepsRecord::class, start, end)),
                zone,
            ),
        )
        DashboardMetric.HEART_RATE -> {
            val (_, avg, _) = DashboardAggregator.dailyHeartRate(
                DashboardRawData(heartRates = healthConnectManager.readRecordsInRange(HeartRateRecord::class, start, end)),
                zone,
            )
            MetricSeriesData.Line(avg)
        }
        DashboardMetric.RESTING_HEART_RATE -> MetricSeriesData.Line(
            DashboardAggregator.dailyRestingHeartRate(
                DashboardRawData(restingHeartRates = healthConnectManager.readRecordsInRange(RestingHeartRateRecord::class, start, end)),
                zone,
            ),
        )
        DashboardMetric.SLEEP -> MetricSeriesData.Stacked(
            DashboardAggregator.nightlySleepStages(
                DashboardRawData(sleeps = healthConnectManager.readRecordsInRange(SleepSessionRecord::class, start, end)),
                zone,
            ),
            SLEEP_COLORS,
        )
        DashboardMetric.WORKOUTS -> MetricSeriesData.Bar(
            DashboardAggregator.weeklyWorkoutMinutes(
                DashboardRawData(exercises = healthConnectManager.readRecordsInRange(ExerciseSessionRecord::class, start, end)),
                zone,
            ),
        )
        DashboardMetric.NUTRITION -> {
            val (protein, fat, carbs) = DashboardAggregator.dailyNutritionMacros(
                DashboardRawData(nutrition = healthConnectManager.readRecordsInRange(NutritionRecord::class, start, end)),
                zone,
            )
            MetricSeriesData.Stacked(mapOf("protein" to protein, "fat" to fat, "carbs" to carbs), NUTRITION_COLORS)
        }
        DashboardMetric.TDEE -> {
            val baseUrl = appPreferences.getBaseUrl()
            val tdee = if (baseUrl.isNullOrBlank()) null else HealthApiClient(baseUrl).getTdee(days)
            MetricSeriesData.MultiLine(
                mapOf(
                    "calculated" to buildTdeeSeries(tdee?.dates.orEmpty(), tdee?.calculatedTdee.orEmpty()),
                    "macrofactor" to buildTdeeSeries(tdee?.dates.orEmpty(), tdee?.macrofactorTdee.orEmpty()),
                ),
                TDEE_COLORS,
            )
        }
    }
}
