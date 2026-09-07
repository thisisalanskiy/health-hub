package com.healthhub.sync.dashboard

import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
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
}
