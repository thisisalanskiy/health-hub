package com.healthhub.sync.sync

import com.healthhub.sync.data.SyncLogEntry
import java.time.Clock

sealed class SyncOutcome {
    data class Success(
        val recordsSent: Boolean,
        /** True if one or more Health Connect changes tokens had expired and were reset this sync. */
        val tokensReset: Boolean = false,
    ) : SyncOutcome()
    data class Failure(val reason: String) : SyncOutcome()
}

fun describeOutcome(outcome: SyncOutcome, clock: Clock = Clock.systemUTC()): SyncLogEntry = when (outcome) {
    is SyncOutcome.Success -> SyncLogEntry(
        timestampMillis = clock.millis(),
        success = true,
        message = when {
            outcome.tokensReset ->
                "synced, but one or more Health Connect changes tokens had expired and were reset"
            outcome.recordsSent -> "synced new records"
            else -> "synced, nothing new"
        },
    )
    is SyncOutcome.Failure -> SyncLogEntry(
        timestampMillis = clock.millis(),
        success = false,
        message = outcome.reason,
    )
}
