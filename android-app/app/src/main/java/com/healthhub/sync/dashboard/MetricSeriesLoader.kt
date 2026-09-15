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
import com.healthhub.sync.data.local.AppDatabase
import com.healthhub.sync.data.local.MacroFactorDailyDao
import com.healthhub.sync.digest.TdeeResponse
import java.time.Instant
import java.time.LocalDate
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

// Same values api/main.py's /api/tdee endpoint uses, so the on-device series matches
// what the server would have returned for the same underlying data.
private const val TDEE_WINDOW_DAYS = 14
private const val TDEE_MIN_READINGS = 7

private fun buildTdeeSeries(dates: List<String>, values: List<Double?>): ChartSeries {
    val pairs = dates.zip(values).filter { it.second != null }
    return ChartSeries(pairs.map { it.first }, pairs.map { it.second!!.toFloat() })
}

/**
 * TDEE computed entirely on-device: [computeTdeeSeries] (the Kotlin port of `api/tdee.py`)
 * fed by Health Connect weight + nutrition reads, with MacroFactor's own expenditure column
 * — imported into Room from the app's .xlsx import — as the comparison line.
 *
 * Replaces `/api/tdee`, and is used unconditionally: backend sync being on or off only
 * decides whether data is additionally POSTed to a server, never where the UI reads TDEE
 * from, so the chart keeps working when the backend is unreachable.
 *
 * Returns the same [TdeeResponse] shape the server endpoint returned, so every existing
 * consumer (dashboard card, chart detail screen, Claude digest) keeps working unchanged.
 * `dates` are ISO-8601 local dates, ascending and gap-free, ending today; the three value
 * lists are index-aligned with it, `null` where that day has no value.
 *
 * @param days how many days of output, ending today (inclusive) — the read reaches
 * [TDEE_WINDOW_DAYS] further back so the earliest target date still has a full trailing
 * window of history to regress over, exactly as the server's extra lookback did.
 */
suspend fun loadLocalTdee(
    days: Int,
    healthConnectManager: HealthConnectManager,
    macroFactorDailyDao: MacroFactorDailyDao,
    zone: ZoneId = ZoneId.systemDefault(),
): TdeeResponse {
    val today = LocalDate.now(zone)
    // coerceAtLeast keeps the list non-empty for days <= 0, so the .first()/.last() below
    // can't throw on a caller that computed its window arithmetically.
    val targetDates = ((days - 1).coerceAtLeast(0) downTo 0).map { today.minusDays(it.toLong()) }
    val readStart = targetDates.first().minusDays(TDEE_WINDOW_DAYS.toLong()).atStartOfDay(zone).toInstant()
    val readEnd = Instant.now()

    val weights = healthConnectManager.readRecordsInRange(WeightRecord::class, readStart, readEnd)
    val nutrition = healthConnectManager.readRecordsInRange(NutritionRecord::class, readStart, readEnd)

    val dailyWeights: Map<LocalDate, Double> = weights
        .groupBy { it.time.atZone(zone).toLocalDate() }
        .mapValues { (_, records) -> records.map { it.weight.inKilograms }.average() }

    val dailyIntake: Map<LocalDate, Double> = nutrition
        .groupBy { it.startTime.atZone(zone).toLocalDate() }
        .mapValues { (_, records) -> records.sumOf { it.energy?.inKilocalories ?: 0.0 } }
        // A day whose records all lack an energy value is a day with no intake data, not a
        // 0 kcal day — keeping it would drag the window's average intake down. Mirrors the
        // server's `AND energy_kcal IS NOT NULL`.
        .filterValues { it > 0.0 }

    val calculated = computeTdeeSeries(
        dailyWeights = dailyWeights,
        dailyIntake = dailyIntake,
        targetDates = targetDates,
        windowDays = TDEE_WINDOW_DAYS,
        minReadings = TDEE_MIN_READINGS,
    )

    val macroFactorByDate = macroFactorDailyDao
        .getInRange(targetDates.first().toString(), targetDates.last().toString())
        .associate { it.date to it.expenditureKcal }

    return TdeeResponse(
        dates = targetDates.map { it.toString() },
        calories = targetDates.map { dailyIntake[it] },
        calculatedTdee = targetDates.map { calculated[it] },
        macrofactorTdee = targetDates.map { macroFactorByDate[it.toString()] },
    )
}

/** The TDEE chart's two-line shape, shared by the dashboard preview and the detail screen. */
fun tdeeMultiLine(tdee: TdeeResponse): MetricSeriesData.MultiLine = MetricSeriesData.MultiLine(
    mapOf(
        "calculated" to buildTdeeSeries(tdee.dates, tdee.calculatedTdee),
        "macrofactor" to buildTdeeSeries(tdee.dates, tdee.macrofactorTdee),
    ),
    TDEE_COLORS,
)

/** Pure: builds a metric's series from an already-fetched [DashboardRawData] window — used by the dashboard's compact preview cards. TDEE needs a longer trailing window than this 7-day snapshot plus its own Room lookup, so it returns null here and is loaded separately via [loadLocalTdee] (its card headline comes from the caller's tdeeData/tdeeError instead). */
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
        DashboardMetric.TDEE -> tdeeMultiLine(
            loadLocalTdee(
                days = days,
                healthConnectManager = healthConnectManager,
                macroFactorDailyDao = AppDatabase.getInstance(appPreferences.appContext).macroFactorDailyDao(),
                zone = zone,
            ),
        )
    }
}
