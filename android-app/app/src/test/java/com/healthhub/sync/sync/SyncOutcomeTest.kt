package com.healthhub.sync.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncOutcomeTest {

    @Test
    fun `success with no records produces a positive log entry`() {
        val entry = describeOutcome(SyncOutcome.Success(recordsSent = false))
        assertTrue(entry.success)
        assertEquals("synced, nothing new", entry.message)
    }

    @Test
    fun `success with records sent mentions it`() {
        val entry = describeOutcome(SyncOutcome.Success(recordsSent = true))
        assertTrue(entry.success)
        assertEquals("synced new records", entry.message)
    }

    @Test
    fun `failure carries the reason and is marked unsuccessful`() {
        val entry = describeOutcome(SyncOutcome.Failure("HTTP 401"))
        assertEquals(false, entry.success)
        assertEquals("HTTP 401", entry.message)
    }
}
