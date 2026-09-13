package com.healthhub.sync.digest

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object ClaudeDigestFormatter {

    /**
     * Every numeric format in this file goes through here so the digest is pinned to
     * Locale.US. This text is pasted into a Claude chat, so it must not swing to
     * comma decimal separators (or other locale digit grouping) on a non-US device.
     */
    private fun fmt(pattern: String, vararg args: Any?): String = String.format(Locale.US, pattern, *args)

    fun format(
        days: Int,
        snapshot: SnapshotResponse,
        weight: WeightResponse,
        tdee: TdeeResponse,
        nutrition: NutritionResponse,
        recovery: RecoveryResponse,
        sleep: SleepResponse,
        workouts: WorkoutsResponse,
    ): String {
        val lines = mutableListOf<String>()
        val today = LocalDate.now()
        val rangeStart = today.minusDays((days - 1).toLong())
        val dateFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        lines += "Here's my health data from ${rangeStart.format(dateFmt)} to ${today.format(dateFmt)} " +
            "(last $days days) — please give me an analysis and any insights."
        lines += ""

        val weightTrend = weightTrendDelta(weight)
        lines += buildString {
            append("Weight: ")
            append(snapshot.weightKg?.let { fmt("%.1f kg", it) } ?: "no recent reading")
            weightTrend?.let { append(fmt(" (trend: %+.1f kg over %d days)", it, days)) }
        }

        val avgCalories = average(nutrition.calories)
        val latestCalculatedTdee = tdee.calculatedTdee.lastOrNull { it != null }
        val latestMacrofactorTdee = tdee.macrofactorTdee.lastOrNull { it != null }
        lines += buildString {
            append("Calories: ")
            append(avgCalories?.let { fmt("avg %,.0f kcal/day", it) } ?: "no data")
            if (latestCalculatedTdee != null || latestMacrofactorTdee != null) {
                append(" (TDEE calculated: ${latestCalculatedTdee?.let { fmt("%,.0f", it) } ?: "n/a"}")
                append(" / MacroFactor: ${latestMacrofactorTdee?.let { fmt("%,.0f", it) } ?: "n/a"})")
            }
        }

        val avgProtein = average(nutrition.protein)
        val avgFat = average(nutrition.fat)
        val avgCarbs = average(nutrition.carbs)
        if (avgProtein != null || avgFat != null || avgCarbs != null) {
            lines += fmt(
                "Protein/Fat/Carbs: avg %.0fg / %.0fg / %.0fg per day",
                avgProtein ?: 0.0, avgFat ?: 0.0, avgCarbs ?: 0.0,
            )
        }

        val nightlySleepMinutes = sleep.deep.indices.map { i ->
            sleep.deep.getOrElse(i) { 0.0 } + sleep.rem.getOrElse(i) { 0.0 } +
                sleep.core.getOrElse(i) { 0.0 } + sleep.other.getOrElse(i) { 0.0 }
        }
        val avgSleepHours = if (nightlySleepMinutes.isNotEmpty()) nightlySleepMinutes.average() / 60.0 else null
        val avgDeepHours = if (sleep.deep.isNotEmpty()) sleep.deep.average() / 60.0 else null
        val avgRemHours = if (sleep.rem.isNotEmpty()) sleep.rem.average() / 60.0 else null
        lines += buildString {
            append("Sleep: ")
            append(avgSleepHours?.let { fmt("avg %.1fh/night", it) } ?: "no data")
            if (avgDeepHours != null && avgRemHours != null) {
                append(fmt(" (deep %.1fh, REM %.1fh)", avgDeepHours, avgRemHours))
            }
        }

        val avgRhr = average(recovery.rhr.map { it.v })
        val avgHrv = average(recovery.hrv.map { it.v })
        lines += buildString {
            append("Recovery: ")
            val parts = mutableListOf<String>()
            avgRhr?.let { parts += fmt("RHR avg %.0f bpm", it) }
            avgHrv?.let { parts += fmt("HRV avg %.0f ms", it) }
            append(if (parts.isEmpty()) "no recovery data" else parts.joinToString(", "))
        }

        val totalSessions = workouts.weekly.sumOf { it.sessions }
        val totalMin = workouts.weekly.sumOf { it.totalMin }
        lines += if (totalSessions > 0) {
            "Workouts: $totalSessions sessions, ${totalMin.toInt()} min total over the last $days days"
        } else {
            "Workouts: no workouts logged"
        }

        return lines.joinToString("\n")
    }

    private fun average(values: List<Double?>): Double? {
        val nonNull = values.filterNotNull()
        return if (nonNull.isEmpty()) null else nonNull.average()
    }

    private fun weightTrendDelta(weight: WeightResponse): Double? {
        val readings = weight.readings
        if (readings.size < 2) return null
        return readings.last().v - readings.first().v
    }
}
