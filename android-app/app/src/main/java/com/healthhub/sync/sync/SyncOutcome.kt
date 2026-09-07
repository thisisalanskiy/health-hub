package com.healthhub.sync.sync

import com.healthhub.sync.data.SyncLogEntry
import java.time.Clock

sealed class SyncOutcome {
    data class Success(
        val recordsSent: Boolean,
        /** True if one or more Health Connect changes tokens had expired and were reset this sync. */
        val tokensReset: Boolean = false,
        /** Number of records the server silently skipped as malformed, from a successful POST. */
        val skippedByServer: Int = 0,
    ) : SyncOutcome()
    data class Failure(val reason: String) : SyncOutcome()
}

fun describeOutcome(outcome: SyncOutcome, clock: Clock = Clock.systemUTC()): SyncLogEntry = when (outcome) {
    is SyncOutcome.Success -> {
        val base = when {
            outcome.tokensReset ->
                "synced, but one or more Health Connect changes tokens had expired and were reset"
            outcome.recordsSent -> "synced new records"
            else -> "synced, nothing new"
        }
        val message = if (outcome.skippedByServer > 0) {
            "$base (${outcome.skippedByServer} skipped by server)"
        } else {
            base
        }
        SyncLogEntry(timestampMillis = clock.millis(), success = true, message = message)
    }
    is SyncOutcome.Failure -> SyncLogEntry(
        timestampMillis = clock.millis(),
        success = false,
        message = outcome.reason,
    )
}
