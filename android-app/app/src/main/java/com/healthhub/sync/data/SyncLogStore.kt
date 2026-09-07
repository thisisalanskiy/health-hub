package com.healthhub.sync.data

fun trimSyncLog(existing: List<SyncLogEntry>, new: SyncLogEntry, maxEntries: Int = 20): List<SyncLogEntry> {
    return (existing + listOf(new)).takeLast(maxEntries).reversed()
}
