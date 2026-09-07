package com.healthhub.sync.dashboard

import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

private fun meta(id: String): Metadata {
    val constructor = Metadata::class.java.getDeclaredConstructor(
        Int::class.javaPrimitiveType,
        String::class.java,
        DataOrigin::class.java,
        Instant::class.java,
        String::class.java,
        Long::class.javaPrimitiveType,
        Device::class.java,
    )
    constructor.isAccessible = true
    return constructor.newInstance(0, id, DataOrigin("test.pkg"), Instant.now(), null, 0L, null) as Metadata
}

class DashboardAggregatorTest {

    private val zone = ZoneOffset.UTC

    @Test
    fun `daily weight trend averages same-day readings`() {
        val day1 = Instant.parse("2026-09-01T08:00:00Z")
        val day1b = Instant.parse("2026-09-01T20:00:00Z")
        val day2 = Instant.parse("2026-09-02T08:00:00Z")
        val data = DashboardRawData(
            weights = listOf(
                WeightRecord(day1, null, Mass.kilograms(80.0), meta("w1")),
                WeightRecord(day1b, null, Mass.kilograms(82.0), meta("w2")),
                WeightRecord(day2, null, Mass.kilograms(81.0), meta("w3")),
            ),
        )

        val series = DashboardAggregator.dailyWeightTrend(data, zone)

        assertEquals(listOf("2026-09-01", "2026-09-02"), series.labels)
        assertEquals(81.0f, series.values[0], 0.01f)
        assertEquals(81.0f, series.values[1], 0.01f)
    }

    @Test
    fun `daily steps sums same-day totals`() {
        val day1Start = Instant.parse("2026-09-01T00:00:00Z")
        val day1End = Instant.parse("2026-09-01T01:00:00Z")
        val day1bStart = Instant.parse("2026-09-01T10:00:00Z")
        val day1bEnd = Instant.parse("2026-09-01T11:00:00Z")
        val data = DashboardRawData(
            steps = listOf(
                StepsRecord(
                    startTime = day1Start,
                    startZoneOffset = null,
                    endTime = day1End,
                    endZoneOffset = null,
                    count = 1000,
                    metadata = meta("s1"),
                ),
                StepsRecord(
                    startTime = day1bStart,
                    startZoneOffset = null,
                    endTime = day1bEnd,
                    endZoneOffset = null,
                    count = 500,
                    metadata = meta("s2"),
                ),
            ),
        )

        val series = DashboardAggregator.dailySteps(data, zone)

        assertEquals(listOf("2026-09-01"), series.labels)
        assertEquals(1500f, series.values[0], 0.01f)
    }

    @Test
    fun `weekly workout minutes sums session durations within the same ISO week`() {
        val mondayStart = Instant.parse("2026-09-07T08:00:00Z") // a Monday
        val mondayEnd = Instant.parse("2026-09-07T08:30:00Z")   // 30 min session
        val wednesdayStart = Instant.parse("2026-09-09T08:00:00Z")
        val wednesdayEnd = Instant.parse("2026-09-09T09:00:00Z") // 60 min session
        val data = DashboardRawData(
            exercises = listOf(
                ExerciseSessionRecord(mondayStart, null, mondayEnd, null, meta("w1"), 56),
                ExerciseSessionRecord(wednesdayStart, null, wednesdayEnd, null, meta("w2"), 56),
            ),
        )

        val series = DashboardAggregator.weeklyWorkoutMinutes(data, zone)

        assertEquals(1, series.labels.size)
        assertEquals(90f, series.values[0], 0.1f)
    }

    @Test
    fun `daily nutrition macros sum grams per day`() {
        val day1 = Instant.parse("2026-09-01T08:00:00Z")
        val day1End = Instant.parse("2026-09-01T08:30:00Z")
        val data = DashboardRawData(
            nutrition = listOf(
                NutritionRecord(
                    startTime = day1, startZoneOffset = null, endTime = day1End, endZoneOffset = null,
                    protein = androidx.health.connect.client.units.Mass.grams(30.0),
                    totalFat = androidx.health.connect.client.units.Mass.grams(10.0),
                    totalCarbohydrate = androidx.health.connect.client.units.Mass.grams(50.0),
                    metadata = meta("n1"),
                ),
            ),
        )

        val (protein, fat, carbs) = DashboardAggregator.dailyNutritionMacros(data, zone)

        assertEquals(listOf("2026-09-01"), protein.labels)
        assertEquals(30f, protein.values[0], 0.1f)
        assertEquals(10f, fat.values[0], 0.1f)
        assertEquals(50f, carbs.values[0], 0.1f)
    }

    @Test
    fun `daily resting heart rate averages same-day readings`() {
        val day1a = Instant.parse("2026-09-01T06:00:00Z")
        val day1b = Instant.parse("2026-09-01T18:00:00Z")
        val data = DashboardRawData(
            restingHeartRates = listOf(
                RestingHeartRateRecord(day1a, null, 58, meta("r1")),
                RestingHeartRateRecord(day1b, null, 62, meta("r2")),
            ),
        )

        val series = DashboardAggregator.dailyRestingHeartRate(data, zone)

        assertEquals(listOf("2026-09-01"), series.labels)
        assertEquals(60f, series.values[0], 0.1f)
    }

    @Test
    fun `daily vo2 max averages same-day readings`() {
        val day1a = Instant.parse("2026-01-01T08:00:00Z")
        val day1b = Instant.parse("2026-01-01T20:00:00Z")
        val day2 = Instant.parse("2026-01-02T08:00:00Z")
        val data = DashboardRawData(
            vo2MaxReadings = listOf(
                Vo2MaxRecord(day1a, null, meta("v1"), 40.0, Vo2MaxRecord.MEASUREMENT_METHOD_OTHER),
                Vo2MaxRecord(day1b, null, meta("v2"), 42.0, Vo2MaxRecord.MEASUREMENT_METHOD_OTHER),
                Vo2MaxRecord(day2, null, meta("v3"), 44.0, Vo2MaxRecord.MEASUREMENT_METHOD_OTHER),
            ),
        )

        val series = DashboardAggregator.dailyVo2Max(data, zone)

        assertEquals(listOf("2026-01-01", "2026-01-02"), series.labels)
        assertEquals(listOf(41.0f, 44.0f), series.values)
    }

    @Test
    fun `daily hrv averages same-day readings`() {
        val day1 = Instant.parse("2026-01-01T08:00:00Z")
        val data = DashboardRawData(
            hrvReadings = listOf(
                HeartRateVariabilityRmssdRecord(day1, null, 50.0, meta("h1")),
            ),
        )

        val series = DashboardAggregator.dailyHrv(data, zone)

        assertEquals(listOf("2026-01-01"), series.labels)
        assertEquals(listOf(50.0f), series.values)
    }

    @Test
    fun `daily body fat reports the human-readable 0-100 percentage`() {
        val day1 = Instant.parse("2026-01-01T08:00:00Z")
        val data = DashboardRawData(
            bodyFatReadings = listOf(
                BodyFatRecord(day1, null, Percentage(25.7), meta("b1")),
            ),
        )

        val series = DashboardAggregator.dailyBodyFat(data, zone)

        assertEquals(listOf("2026-01-01"), series.labels)
        assertEquals(25.7f, series.values.single(), 0.01f)
    }

    @Test
    fun `daily bmr converts to kcal per day`() {
        val day1 = Instant.parse("2026-01-01T08:00:00Z")
        val data = DashboardRawData(
            bmrReadings = listOf(
                BasalMetabolicRateRecord(day1, null, Power.kilocaloriesPerDay(1680.0), meta("m1")),
            ),
        )

        val series = DashboardAggregator.dailyBmr(data, zone)

        assertEquals(listOf("2026-01-01"), series.labels)
        assertEquals(1680.0f, series.values.single())
    }
}
