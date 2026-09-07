package com.healthhub.sync.digest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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
            bodyTrends = BodyTrendsResponse(),
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
            bodyTrends = BodyTrendsResponse(),
        )

        assertTrue(text.isNotBlank())
    }

    @Test
    fun `numbers stay US-formatted under a comma-decimal default locale`() {
        val original = Locale.getDefault()
        try {
            // Germany formats 77.4 as "77,4" and 2350 as "2.350" — the digest is
            // pasted into a chat, so it must not swing with the device locale.
            Locale.setDefault(Locale.GERMANY)
            val text = ClaudeDigestFormatter.format(
                days = 7,
                snapshot = SnapshotResponse(weightKg = 77.4, caloriesKcal = 2100.0, tdeeKcal = 2350.0, steps = 8200, sleepMin = 432.0),
                weight = WeightResponse(readings = listOf(WeightPoint("2026-09-01T00:00:00Z", 77.7), WeightPoint("2026-09-07T00:00:00Z", 77.4))),
                tdee = TdeeResponse(calculatedTdee = listOf(2340.0, 2350.0), macrofactorTdee = listOf(2300.0, 2300.0)),
                nutrition = NutritionResponse(calories = listOf(2050.0, 2100.0), protein = listOf(140.0, 145.0), fat = listOf(65.0, 70.0), carbs = listOf(200.0, 210.0)),
                recovery = RecoveryResponse(rhr = listOf(RecoveryPoint("2026-09-06", 58.0), RecoveryPoint("2026-09-07", 59.0))),
                sleep = SleepResponse(deep = listOf(60.0, 66.0), rem = listOf(90.0, 96.0), core = listOf(240.0, 250.0), awake = listOf(10.0, 8.0), other = listOf(0.0, 0.0)),
                workouts = WorkoutsResponse(weekly = listOf(WeeklyWorkout("2026-09-01", 210.0, 4))),
                bodyTrends = BodyTrendsResponse(),
            )

            assertTrue(text.contains("77.4 kg"))
            assertTrue(text.contains("trend: -0.3 kg over 7 days"))
            assertTrue(text.contains("2,350"))
            assertTrue(text.contains("avg 2,075 kcal/day"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `includes the actual calendar date range, not just a day count`() {
        val today = java.time.LocalDate.now()
        val expectedStart = today.minusDays(6)
        val fmt = java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.US)

        val text = ClaudeDigestFormatter.format(
            days = 7,
            snapshot = SnapshotResponse(), weight = WeightResponse(), tdee = TdeeResponse(),
            nutrition = NutritionResponse(), recovery = RecoveryResponse(), sleep = SleepResponse(),
            workouts = WorkoutsResponse(), bodyTrends = BodyTrendsResponse(),
        )

        assertTrue(text.contains(expectedStart.format(fmt)))
        assertTrue(text.contains(today.format(fmt)))
    }

    @Test
    fun `sums workouts across every returned week, not just the last one`() {
        val workouts = WorkoutsResponse(weekly = listOf(
            WeeklyWorkout(week = "2026-08-24", totalMin = 120.0, sessions = 3),
            WeeklyWorkout(week = "2026-08-31", totalMin = 40.0, sessions = 1),
        ))

        val text = ClaudeDigestFormatter.format(
            days = 7,
            snapshot = SnapshotResponse(), weight = WeightResponse(), tdee = TdeeResponse(),
            nutrition = NutritionResponse(), recovery = RecoveryResponse(), sleep = SleepResponse(),
            workouts = workouts, bodyTrends = BodyTrendsResponse(),
        )

        assertTrue(text.contains("4 sessions"))
        assertTrue(text.contains("160 min"))
        assertFalse(text.contains("1 sessions"))
    }

    @Test
    fun `includes body trends when present`() {
        val bodyTrends = BodyTrendsResponse(
            dates = listOf("2026-09-01", "2026-09-02"),
            vo2Max = listOf(42.5, null),
            bodyFatPercent = listOf(0.257, null),
            bmrKcal = listOf(1680.0, null),
        )

        val text = ClaudeDigestFormatter.format(
            days = 7,
            snapshot = SnapshotResponse(), weight = WeightResponse(), tdee = TdeeResponse(),
            nutrition = NutritionResponse(), recovery = RecoveryResponse(), sleep = SleepResponse(),
            workouts = WorkoutsResponse(), bodyTrends = bodyTrends,
        )

        assertTrue(text.contains("42.5"))
        assertTrue(text.contains("25.7"))
        assertTrue(text.contains("1680"))
    }
}
