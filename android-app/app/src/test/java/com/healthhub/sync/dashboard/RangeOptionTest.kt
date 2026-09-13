package com.healthhub.sync.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class RangeOptionTest {

    @Test
    fun `each range option maps to the correct day count`() {
        assertEquals(7, RangeOption.WEEK.days)
        assertEquals(30, RangeOption.MONTH.days)
        assertEquals(90, RangeOption.THREE_MONTHS.days)
        assertEquals(180, RangeOption.SIX_MONTHS.days)
        assertEquals(365, RangeOption.YEAR.days)
        assertEquals(1825, RangeOption.ALL.days)
    }

    @Test
    fun `each range option has its short display label`() {
        assertEquals("1W", RangeOption.WEEK.label)
        assertEquals("1M", RangeOption.MONTH.label)
        assertEquals("3M", RangeOption.THREE_MONTHS.label)
        assertEquals("6M", RangeOption.SIX_MONTHS.label)
        assertEquals("1Y", RangeOption.YEAR.label)
        assertEquals("All", RangeOption.ALL.label)
    }
}
