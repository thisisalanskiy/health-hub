package com.healthhub.sync.digest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.healthhub.sync.dashboard.DashboardAggregator
import com.healthhub.sync.dashboard.loadLocalTdee
import com.healthhub.sync.data.AppPreferences
import com.healthhub.sync.data.HealthConnectManager
import com.healthhub.sync.data.local.AppDatabase
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val DIGEST_DAYS = 7

/**
 * Assembles the last [DIGEST_DAYS] days of health data entirely from on-device sources
 * (Health Connect + the local MacroFactor Room table) and copies a Claude-ready summary to
 * the clipboard. No longer talks to the backend at all — [appPreferences] is kept only so
 * this function's signature (called from [com.healthhub.sync.ui.DashboardScreen]) doesn't
 * need to change; it isn't read here anymore.
 */
suspend fun prepareForClaude(appPreferences: AppPreferences, context: Context): String {
    return try {
        val healthConnectManager = HealthConnectManager(context)
        val zone = ZoneId.systemDefault()
        val end = Instant.now()
        val start = end.minus(DIGEST_DAYS.toLong(), ChronoUnit.DAYS)

        // Same single-shot 7-day read across all record types the dashboard itself uses
        // (DashboardScreen's own `hasForegroundPermissions`-gated LaunchedEffect).
        val raw = healthConnectManager.readAllRecords(start, end)

        // TDEE reaches further back than the digest's own 7-day window (it needs trailing
        // history to regress over) and touches Room for the MacroFactor comparison line, so
        // it's computed via the shared loader rather than from `raw` above.
        val tdee = loadLocalTdee(
            days = DIGEST_DAYS,
            healthConnectManager = healthConnectManager,
            macroFactorDailyDao = AppDatabase.getInstance(context).macroFactorDailyDao(),
            zone = zone,
        )

        val weightSeries = DashboardAggregator.dailyWeightTrend(raw, zone)
        val weight = WeightResponse(
            readings = weightSeries.labels.zip(weightSeries.values)
                .map { (day, kg) -> WeightPoint(day, kg.toDouble()) },
        )

        val (proteinSeries, fatSeries, carbsSeries) = DashboardAggregator.dailyNutritionMacros(raw, zone)
        val nutrition = NutritionResponse(
            // Calories reuse loadLocalTdee's own daily-intake series rather than recomputing
            // it: it's the exact same "kcal in on day X" figure the TDEE section already
            // derived from Health Connect nutrition records over this same window.
            days = tdee.dates,
            calories = tdee.calories,
            protein = proteinSeries.values.map { it.toDouble() },
            fat = fatSeries.values.map { it.toDouble() },
            carbs = carbsSeries.values.map { it.toDouble() },
        )

        // Recovery is RHR-only: HRV was dropped from this app entirely (no Room table, no
        // Health Connect read for it), reverting the digest's Recovery section to its
        // pre-HRV behavior. `hrv` is left at its empty-list default.
        val rhrSeries = DashboardAggregator.dailyRestingHeartRate(raw, zone)
        val recovery = RecoveryResponse(
            rhr = rhrSeries.labels.zip(rhrSeries.values).map { (day, bpm) -> RecoveryPoint(day, bpm.toDouble()) },
        )

        val sleepStages = DashboardAggregator.nightlySleepStages(raw, zone)
        val sleep = SleepResponse(
            dates = sleepStages.getValue("deep").labels,
            deep = sleepStages.getValue("deep").values.map { it.toDouble() },
            rem = sleepStages.getValue("rem").values.map { it.toDouble() },
            core = sleepStages.getValue("core").values.map { it.toDouble() },
            awake = sleepStages.getValue("awake").values.map { it.toDouble() },
            other = sleepStages.getValue("other").values.map { it.toDouble() },
        )

        val workouts = buildWorkoutsResponse(
            sessionDurationsMs = raw.exercises.map { it.startTime.toEpochMilli() to it.endTime.toEpochMilli() },
            windowLabel = "${start.atZone(zone).toLocalDate()}..${end.atZone(zone).toLocalDate()}",
        )

        val latestSteps = DashboardAggregator.dailySteps(raw, zone).values.lastOrNull()?.toLong()
        val snapshot = buildSnapshot(weight, nutrition, tdee, latestSteps, sleep)

        val text = ClaudeDigestFormatter.format(
            days = DIGEST_DAYS,
            snapshot = snapshot,
            weight = weight,
            tdee = tdee,
            nutrition = nutrition,
            recovery = recovery,
            sleep = sleep,
            workouts = workouts,
        )
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Health digest", text))
        "Copied $DIGEST_DAYS days of data to clipboard."
    } catch (e: CancellationException) {
        // Never swallow coroutine cancellation.
        throw e
    } catch (e: Exception) {
        "Couldn't prepare digest: ${e.message ?: e::class.simpleName}"
    }
}

/**
 * Pure: the "latest values" bundle, picked from the same already-assembled per-section
 * responses [prepareForClaude] builds for the rest of the digest (no separate Health Connect
 * read of its own). Mirrors [ClaudeDigestFormatter]'s own "latest non-null" convention for
 * calories/TDEE and its "deep+rem+core+other, excluding awake" definition of a night's sleep
 * total, so the snapshot line and the trend lines below it never disagree.
 */
internal fun buildSnapshot(
    weight: WeightResponse,
    nutrition: NutritionResponse,
    tdee: TdeeResponse,
    latestSteps: Long?,
    sleep: SleepResponse,
): SnapshotResponse {
    val lastNightIndex = sleep.deep.indices.lastOrNull()
    val latestSleepMin = lastNightIndex?.let { i ->
        sleep.deep.getOrElse(i) { 0.0 } + sleep.rem.getOrElse(i) { 0.0 } +
            sleep.core.getOrElse(i) { 0.0 } + sleep.other.getOrElse(i) { 0.0 }
    }
    return SnapshotResponse(
        weightKg = weight.readings.lastOrNull()?.v,
        caloriesKcal = nutrition.calories.lastOrNull { it != null },
        tdeeKcal = tdee.calculatedTdee.lastOrNull { it != null },
        steps = latestSteps,
        sleepMin = latestSleepMin,
    )
}

/**
 * Pure: turns raw exercise-session (start, end) epoch-millisecond pairs into the
 * [WorkoutsResponse] shape. Deliberately a single bucket spanning the whole digest window
 * rather than a per-ISO-week breakdown like [DashboardAggregator.weeklyWorkoutMinutes] (whose
 * week-grouping helper is private and doesn't track session counts) — harmless here because
 * [ClaudeDigestFormatter] only ever sums `sessions`/`totalMin` across every entry, regardless
 * of how many buckets there are.
 */
internal fun buildWorkoutsResponse(sessionDurationsMs: List<Pair<Long, Long>>, windowLabel: String): WorkoutsResponse {
    if (sessionDurationsMs.isEmpty()) return WorkoutsResponse()
    val totalMin = sessionDurationsMs.sumOf { (start, end) -> (end - start) / 60_000.0 }
    return WorkoutsResponse(weekly = listOf(WeeklyWorkout(week = windowLabel, totalMin = totalMin, sessions = sessionDurationsMs.size)))
}
