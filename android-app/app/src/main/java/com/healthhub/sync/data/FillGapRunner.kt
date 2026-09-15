package com.healthhub.sync.data

import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * A multi-week backfill can carry tens of thousands of samples in one request, which
 * OkHttp's default 10s connect/read/write timeouts can't cover. Scoped to this path
 * only — the periodic sync moves small payloads and keeps the default client.
 */
private val backfillHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
}

suspend fun runFillGap(
    baseUrl: String,
    apiKey: String,
    healthConnectManager: HealthConnectManager,
    appPreferences: AppPreferences,
): String {
    // Backfill exists only to push history to the self-hosted backend, so with the
    // opt-in off this returns before any network call at all — including the
    // last-sync probe below, which is itself a request to that server.
    if (!appPreferences.getBackendSyncEnabled()) {
        return "Backend sync is off — turn on \"Sync to self-hosted server\" to backfill."
    }
    if (baseUrl.isBlank()) return "Set a server URL first."
    val since = LastSyncApiClient(baseUrl, backfillHttpClient).getLastSync()
        ?: return "Couldn't reach the server, or no data in Postgres yet."
    val now = Instant.now()
    if (!since.isBefore(now)) return "Already up to date."

    return try {
        // readAllRecords throws (SecurityException without permissions,
        // IllegalStateException if Health Connect is unavailable) — an uncaught
        // throw here propagates out of the caller's scope.launch and crashes the app.
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

        val result = IngestApiClient(baseUrl, apiKey.ifBlank { null }, backfillHttpClient).post(payload)
        result.fold(
            onSuccess = { skipped ->
                if (skipped > 0) "Backfilled records ($skipped skipped by server)." else "Backfill complete."
            },
            onFailure = { e -> "Backfill failed: ${e.message}" },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "Backfill failed: ${e.message ?: e::class.simpleName}"
    }
}
