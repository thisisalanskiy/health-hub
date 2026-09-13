package com.healthhub.sync.dashboard

import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

class MetricSeriesLoaderTest {

    private val zone = ZoneOffset.UTC

    @Test
    fun `preview series data for weight delegates to the existing aggregator`() {
        val data = DashboardRawData(
            weights = listOf(
                WeightRecord(Instant.parse("2026-09-01T08:00:00Z"), null, Mass.kilograms(77.2), meta("w1")),
            ),
        )
        val result = previewSeriesData(DashboardMetric.WEIGHT, data, zone) as MetricSeriesData.Line
        assertEquals(listOf("2026-09-01"), result.series.labels)
        assertEquals(listOf(77.2f), result.series.values)
    }

    @Test
    fun `preview series data for steps delegates to the existing aggregator`() {
        val data = DashboardRawData(
            steps = listOf(
                StepsRecord(Instant.parse("2026-09-01T08:00:00Z"), null, Instant.parse("2026-09-01T09:00:00Z"), null, 8342, meta("s1")),
            ),
        )
        val result = previewSeriesData(DashboardMetric.STEPS, data, zone) as MetricSeriesData.Bar
        assertEquals(listOf("2026-09-01"), result.series.labels)
        assertEquals(listOf(8342f), result.series.values)
    }

    @Test
    fun `preview series data for sleep produces a stacked series with all 5 stage names`() {
        val result = previewSeriesData(DashboardMetric.SLEEP, DashboardRawData(), zone) as MetricSeriesData.Stacked
        assertEquals(setOf("deep", "rem", "core", "awake", "other"), result.seriesByStack.keys)
    }

    @Test
    fun `preview series data for nutrition produces a stacked series with protein fat carbs`() {
        val result = previewSeriesData(DashboardMetric.NUTRITION, DashboardRawData(), zone) as MetricSeriesData.Stacked
        assertEquals(setOf("protein", "fat", "carbs"), result.seriesByStack.keys)
    }

    @Test
    fun `preview series data for tdee is null since it has no offline Health Connect data`() {
        assertTrue(previewSeriesData(DashboardMetric.TDEE, DashboardRawData(), zone) == null)
    }
}
