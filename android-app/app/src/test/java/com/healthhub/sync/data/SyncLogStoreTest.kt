package com.healthhub.sync.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncLogStoreTest {

    @Test
    fun `new entry is prepended`() {
        val existing = listOf(SyncLogEntry(1000, true, "ok"))
        val updated = trimSyncLog(existing, SyncLogEntry(2000, true, "ok again"))

        assertEquals(2, updated.size)
        assertEquals(2000L, updated[0].timestampMillis)
        assertEquals(1000L, updated[1].timestampMillis)
    }

    @Test
    fun `list is capped at maxEntries, dropping oldest`() {
        val existing = (0 until 20).map { SyncLogEntry(it.toLong(), true, "e$it") }

        val updated = trimSyncLog(existing, SyncLogEntry(20, true, "newest"), maxEntries = 20)

        assertEquals(20, updated.size)
        assertEquals(20L, updated.first().timestampMillis)
        // oldest entry (timestamp 0) should have been dropped
        assertEquals(false, updated.any { it.timestampMillis == 0L })
    }
}
