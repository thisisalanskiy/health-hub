package com.healthhub.sync.dashboard

import com.healthhub.sync.digest.TdeeResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardMetricTest {

    @Test
    fun `there are exactly 8 metrics in the existing dashboard order`() {
        assertEquals(
            listOf("weight", "steps", "heart_rate", "resting_heart_rate", "sleep", "workouts", "nutrition", "tdee"),
            DashboardMetric.values().map { it.id },
        )
    }

    @Test
    fun `weight preview value formats the last value in kg`() {
        val data = MetricSeriesData.Line(ChartSeries(listOf("2026-09-01"), listOf(77.2f)))
        assertEquals("77.2 kg", previewValueText(DashboardMetric.WEIGHT, data, null, null))
    }

    @Test
    fun `weight preview value reports no data when the series is empty`() {
        val data = MetricSeriesData.Line(ChartSeries(emptyList(), emptyList()))
        assertEquals("no data", previewValueText(DashboardMetric.WEIGHT, data, null, null))
    }

    @Test
    fun `steps preview value formats the last value as a comma-grouped whole number`() {
        val data = MetricSeriesData.Bar(ChartSeries(listOf("2026-09-01"), listOf(8342f)))
        assertEquals("8,342", previewValueText(DashboardMetric.STEPS, data, null, null))
    }

    @Test
    fun `resting heart rate preview value formats bpm`() {
        val data = MetricSeriesData.Line(ChartSeries(listOf("2026-09-01"), listOf(58.0f)))
        assertEquals("58 bpm", previewValueText(DashboardMetric.RESTING_HEART_RATE, data, null, null))
    }

    @Test
    fun `sleep preview value sums the most recent night's stage minutes`() {
        val data = MetricSeriesData.Stacked(
            seriesByStack = mapOf(
                "deep" to ChartSeries(listOf("2026-09-01"), listOf(60f)),
                "rem" to ChartSeries(listOf("2026-09-01"), listOf(90f)),
                "core" to ChartSeries(listOf("2026-09-01"), listOf(252f)),
                "awake" to ChartSeries(listOf("2026-09-01"), listOf(0f)),
                "other" to ChartSeries(listOf("2026-09-01"), listOf(0f)),
            ),
            colors = emptyMap(),
        )
        assertEquals("6h 42m last night", previewValueText(DashboardMetric.SLEEP, data, null, null))
    }

    @Test
    fun `nutrition preview value sums the most recent day's macros`() {
        val data = MetricSeriesData.Stacked(
            seriesByStack = mapOf(
                "protein" to ChartSeries(listOf("2026-09-01"), listOf(120f)),
                "fat" to ChartSeries(listOf("2026-09-01"), listOf(60f)),
                "carbs" to ChartSeries(listOf("2026-09-01"), listOf(200f)),
            ),
            colors = emptyMap(),
        )
        assertEquals("380g today", previewValueText(DashboardMetric.NUTRITION, data, null, null))
    }

    @Test
    fun `tdee preview value reports the latest calculated value`() {
        val tdee = TdeeResponse(
            dates = listOf("2026-09-01"),
            calories = listOf(2100.0),
            calculatedTdee = listOf(2450.0),
            macrofactorTdee = listOf(2400.0),
        )
        assertEquals("2,450 kcal", previewValueText(DashboardMetric.TDEE, null, tdee, null))
    }

    @Test
    fun `tdee preview value falls back to an error message`() {
        assertEquals("TDEE unavailable", previewValueText(DashboardMetric.TDEE, null, null, "TDEE unavailable: 500"))
    }

    @Test
    fun `tdee preview value points at Settings when no server is configured`() {
        assertEquals(
            "Set server URL in Settings",
            previewValueText(DashboardMetric.TDEE, null, null, null, serverConfigured = false),
        )
    }

    @Test
    fun `tdee preview value reports no data when the server is configured but empty`() {
        val tdee = TdeeResponse(
            dates = emptyList(),
            calories = emptyList(),
            calculatedTdee = emptyList(),
            macrofactorTdee = emptyList(),
        )
        assertEquals("no data", previewValueText(DashboardMetric.TDEE, null, tdee, null, serverConfigured = true))
    }

    @Test
    fun `tdee preview value prefers a real error over the no-server message`() {
        assertEquals(
            "TDEE unavailable",
            previewValueText(DashboardMetric.TDEE, null, null, "TDEE unavailable: 500", serverConfigured = false),
        )
    }
}
