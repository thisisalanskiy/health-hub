package com.healthhub.sync.digest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudeDigestRunnerTest {

    @Test
    fun `buildSnapshot picks the latest reading from each already-built section`() {
        val weight = WeightResponse(readings = listOf(WeightPoint("2026-09-06", 77.7), WeightPoint("2026-09-07", 77.4)))
        val nutrition = NutritionResponse(calories = listOf(2050.0, 2100.0))
        val tdee = TdeeResponse(calculatedTdee = listOf(2340.0, 2350.0))
        val sleep = SleepResponse(
            deep = listOf(60.0, 66.0),
            rem = listOf(90.0, 96.0),
            core = listOf(240.0, 250.0),
            awake = listOf(10.0, 8.0),
            other = listOf(0.0, 5.0),
        )

        val snapshot = buildSnapshot(weight, nutrition, tdee, latestSteps = 8200L, sleep = sleep)

        assertEquals(77.4, snapshot.weightKg!!, 0.001)
        assertEquals(2100.0, snapshot.caloriesKcal!!, 0.001)
        assertEquals(2350.0, snapshot.tdeeKcal!!, 0.001)
        assertEquals(8200L, snapshot.steps)
        // deep + rem + core + other for the last night, excluding awake — same definition
        // ClaudeDigestFormatter uses for its own "avg sleep" line.
        assertEquals(66.0 + 96.0 + 250.0 + 5.0, snapshot.sleepMin!!, 0.001)
    }

    @Test
    fun `buildSnapshot skips trailing nulls to find the latest actual calories and tdee reading`() {
        val nutrition = NutritionResponse(calories = listOf(2050.0, null))
        val tdee = TdeeResponse(calculatedTdee = listOf(2340.0, null))

        val snapshot = buildSnapshot(WeightResponse(), nutrition, tdee, latestSteps = null, sleep = SleepResponse())

        assertEquals(2050.0, snapshot.caloriesKcal!!, 0.001)
        assertEquals(2340.0, snapshot.tdeeKcal!!, 0.001)
    }

    @Test
    fun `buildSnapshot returns all nulls when every section is empty`() {
        val snapshot = buildSnapshot(WeightResponse(), NutritionResponse(), TdeeResponse(), latestSteps = null, sleep = SleepResponse())

        assertNull(snapshot.weightKg)
        assertNull(snapshot.caloriesKcal)
        assertNull(snapshot.tdeeKcal)
        assertNull(snapshot.steps)
        assertNull(snapshot.sleepMin)
    }

    @Test
    fun `buildWorkoutsResponse sums session durations into one bucket`() {
        val sessions = listOf(
            0L to 30 * 60_000L, // 30 min
            0L to 60 * 60_000L, // 60 min
        )

        val workouts = buildWorkoutsResponse(sessions, windowLabel = "2026-09-01..2026-09-07")

        assertEquals(1, workouts.weekly.size)
        assertEquals(2, workouts.weekly[0].sessions)
        assertEquals(90.0, workouts.weekly[0].totalMin, 0.001)
        assertEquals("2026-09-01..2026-09-07", workouts.weekly[0].week)
    }

    @Test
    fun `buildWorkoutsResponse returns an empty list when there are no sessions`() {
        val workouts = buildWorkoutsResponse(emptyList(), windowLabel = "2026-09-01..2026-09-07")

        assertEquals(emptyList<WeeklyWorkout>(), workouts.weekly)
    }
}
