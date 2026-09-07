package com.healthhub.sync.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrendCalculatorTest {

    @Test
    fun `returns null for fewer than 2 values`() {
        assertNull(trendDelta(ChartSeries(emptyList(), emptyList())))
        assertNull(trendDelta(ChartSeries(listOf("d1"), listOf(1f))))
    }

    @Test
    fun `computes second-half-average minus first-half-average for an even count`() {
        // first half [1,2] avg 1.5, second half [3,4] avg 3.5 -> delta 2.0
        val series = ChartSeries(listOf("1", "2", "3", "4"), listOf(1f, 2f, 3f, 4f))
        assertEquals(2.0f, trendDelta(series)!!, 0.01f)
    }

    @Test
    fun `computes delta for an odd count by splitting floor(n div 2) vs the rest`() {
        // n=5: first half = first 2 [1,2] avg 1.5, second half = last 3 [3,4,5] avg 4.0 -> delta 2.5
        val series = ChartSeries(listOf("1", "2", "3", "4", "5"), listOf(1f, 2f, 3f, 4f, 5f))
        assertEquals(2.5f, trendDelta(series)!!, 0.01f)
    }

    @Test
    fun `negative delta when the series is decreasing`() {
        val series = ChartSeries(listOf("1", "2"), listOf(10f, 4f))
        assertEquals(-6.0f, trendDelta(series)!!, 0.01f)
    }
}
