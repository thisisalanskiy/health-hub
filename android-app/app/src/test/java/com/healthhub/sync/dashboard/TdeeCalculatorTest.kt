package com.healthhub.sync.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TdeeCalculatorTest {

    @Test
    fun `flat weight over the window yields TDEE equal to average intake`() {
        val start = LocalDate.of(2026, 8, 1)
        val weights = (0 until 14).associate { start.plusDays(it.toLong()) to 80.0 }
        val intake = (0 until 14).associate { start.plusDays(it.toLong()) to 2000.0 }
        val target = start.plusDays(13)
        val result = computeTdeeSeries(weights, intake, listOf(target))
        assertEquals(2000.0, result[target]!!, 0.01)
    }

    @Test
    fun `fewer than minReadings weight points in the window returns null`() {
        val start = LocalDate.of(2026, 8, 1)
        val weights = mapOf(start to 80.0, start.plusDays(1) to 79.9) // only 2, need 7
        val intake = (0 until 14).associate { start.plusDays(it.toLong()) to 2000.0 }
        val target = start.plusDays(13)
        val result = computeTdeeSeries(weights, intake, listOf(target))
        assertNull(result[target])
    }

    @Test
    fun `linreg slope of a single-x-value window is zero, not a division error`() {
        val d = LocalDate.of(2026, 8, 1)
        assertEquals(0.0, linregSlope(listOf(d to 80.0, d to 81.0)), 0.0)
    }

    @Test
    fun `linreg slope detects a real trend`() {
        val start = LocalDate.of(2026, 8, 1)
        // Exactly -0.1 kg/day over 10 days.
        val points = (0 until 10).map { start.plusDays(it.toLong()) to (80.0 - it * 0.1) }
        assertEquals(-0.1, linregSlope(points), 0.001)
    }
}
