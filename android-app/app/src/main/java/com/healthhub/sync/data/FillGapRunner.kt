package com.healthhub.sync.data

import java.time.Instant

suspend fun runFillGap(baseUrl: String, apiKey: String, healthConnectManager: HealthConnectManager): String {
    if (baseUrl.isBlank()) return "Set a server URL first."
    val since = LastSyncApiClient(baseUrl).getLastSync()
        ?: return "Couldn't reach the server, or no data in Postgres yet."
    val now = Instant.now()
    if (!since.isBefore(now)) return "Already up to date."

    val data = healthConnectManager.readAllRecords(since, now)
    val payload = buildIngestPayload(
        weights = data.weights,
        nutrition = data.nutrition,
        exercises = data.exercises,
        heartRates = data.heartRates,
        restingHeartRates = data.restingHeartRates,
        steps = data.steps,
        sleeps = data.sleeps,
    ) ?: return "Nothing to backfill — already caught up."

    val result = IngestApiClient(baseUrl, apiKey.ifBlank { null }).post(payload)
    return result.fold(
        onSuccess = { skipped ->
            if (skipped > 0) "Backfilled records ($skipped skipped by server)." else "Backfill complete."
        },
        onFailure = { e -> "Backfill failed: ${e.message}" },
    )
}
