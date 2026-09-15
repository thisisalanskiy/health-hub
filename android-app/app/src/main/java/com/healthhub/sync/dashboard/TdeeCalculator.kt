package com.healthhub.sync.dashboard

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Direct Kotlin port of `api/tdee.py`. Kept behaviorally identical, including the
 * zero-denominator -> 0.0 case in [linregSlope].
 */
private const val KCAL_PER_KG = 7700

/** Least-squares slope of value-per-day, x measured in days from the first point. */
fun linregSlope(points: List<Pair<LocalDate, Double>>): Double {
    val x0 = points[0].first
    val xs = points.map { (d, _) -> ChronoUnit.DAYS.between(x0, d).toDouble() }
    val ys = points.map { (_, v) -> v }
    val n = points.size
    val meanX = xs.sum() / n
    val meanY = ys.sum() / n
    val numerator = xs.zip(ys).sumOf { (x, y) -> (x - meanX) * (y - meanY) }
    val denominator = xs.sumOf { x -> (x - meanX) * (x - meanX) }
    if (denominator == 0.0) {
        return 0.0
    }
    return numerator / denominator
}

/**
 * For each target date, a trailing `windowDays`-day window (ending at and including the
 * target date). Returns `null` for a target date if fewer than `minReadings` weight or
 * intake points fall in that window; otherwise `avgIntake - (kgPerDay * KCAL_PER_KG)`.
 */
fun computeTdeeSeries(
    dailyWeights: Map<LocalDate, Double>,
    dailyIntake: Map<LocalDate, Double>,
    targetDates: List<LocalDate>,
    windowDays: Int = 14,
    minReadings: Int = 7,
): Map<LocalDate, Double?> {
    val result = LinkedHashMap<LocalDate, Double?>()
    for (target in targetDates) {
        val windowStart = target.minusDays((windowDays - 1).toLong())
        val window = (0 until windowDays).map { windowStart.plusDays(it.toLong()) }

        val weightPoints = window.filter { it in dailyWeights }.map { it to dailyWeights.getValue(it) }
        val intakePoints = window.filter { it in dailyIntake }.map { dailyIntake.getValue(it) }

        if (weightPoints.size < minReadings || intakePoints.size < minReadings) {
            result[target] = null
            continue
        }

        val avgIntake = intakePoints.sum() / intakePoints.size
        val kgPerDay = linregSlope(weightPoints)
        result[target] = avgIntake - (kgPerDay * KCAL_PER_KG)
    }

    return result
}
