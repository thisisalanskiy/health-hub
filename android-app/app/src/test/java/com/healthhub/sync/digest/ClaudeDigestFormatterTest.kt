package com.healthhub.sync.digest

import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeDigestFormatterTest {

    @Test
    fun `includes a framing line and the requested day count`() {
        val text = ClaudeDigestFormatter.format(
            days = 7,
            snapshot = SnapshotResponse(weightKg = 77.4, caloriesKcal = 2100.0, tdeeKcal = 2350.0, steps = 8200, sleepMin = 432.0),
            weight = WeightResponse(readings = listOf(WeightPoint("2026-09-01T00:00:00Z", 77.7), WeightPoint("2026-09-07T00:00:00Z", 77.4))),
            tdee = TdeeResponse(calculatedTdee = listOf(2340.0, 2350.0), macrofactorTdee = listOf(2300.0, 2300.0)),
            nutrition = NutritionResponse(calories = listOf(2050.0, 2100.0), protein = listOf(140.0, 145.0), fat = listOf(65.0, 70.0), carbs = listOf(200.0, 210.0)),
            recovery = RecoveryResponse(rhr = listOf(RecoveryPoint("2026-09-06", 58.0), RecoveryPoint("2026-09-07", 59.0))),
            sleep = SleepResponse(deep = listOf(60.0, 66.0), rem = listOf(90.0, 96.0), core = listOf(240.0, 250.0), awake = listOf(10.0, 8.0), other = listOf(0.0, 0.0)),
            workouts = WorkoutsResponse(weekly = listOf(WeeklyWorkout("2026-09-01", 210.0, 4))),
        )

        assertTrue(text.contains("last 7 days"))
        assertTrue(text.contains("77.4 kg"))
        assertTrue(text.contains("2,350") || text.contains("2350"))
        assertTrue(text.contains("4 sessions") || text.contains("4 workout"))
    }

    @Test
    fun `handles entirely missing data without crashing`() {
        val text = ClaudeDigestFormatter.format(
            days = 7,
            snapshot = SnapshotResponse(),
            weight = WeightResponse(),
            tdee = TdeeResponse(),
            nutrition = NutritionResponse(),
            recovery = RecoveryResponse(),
            sleep = SleepResponse(),
            workouts = WorkoutsResponse(),
        )

        assertTrue(text.isNotBlank())
    }
}
