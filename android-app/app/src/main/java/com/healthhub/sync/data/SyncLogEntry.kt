package com.healthhub.sync.data

import kotlinx.serialization.Serializable

@Serializable
data class SyncLogEntry(
    val timestampMillis: Long,
    val success: Boolean,
    val message: String,
)
