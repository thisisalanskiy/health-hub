package com.healthhub.sync.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.healthhub.sync.data.AppPreferences
import com.healthhub.sync.data.HealthConnectManager
import com.healthhub.sync.data.IngestApiClient

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = AppPreferences(applicationContext)
        val healthConnect = HealthConnectManager(applicationContext)
        val outcome = runSync(prefs, healthConnect)
        prefs.appendSyncLog(describeOutcome(outcome))
        return when (outcome) {
            is SyncOutcome.Success -> Result.success()
            is SyncOutcome.Failure -> Result.retry()
        }
    }

    private suspend fun runSync(prefs: AppPreferences, healthConnect: HealthConnectManager): SyncOutcome {
        return try {
            if (!healthConnect.isAvailable()) {
                return SyncOutcome.Failure("Health Connect not available on this device")
            }
            if (!healthConnect.hasAllPermissions()) {
                return SyncOutcome.Failure("Health Connect permissions not granted")
            }

            // Backend sync is opt-in. With it off this worker has nothing to do: its
            // entire job is the POST below, so it returns before any network call —
            // and before the changes fetch too, deliberately. Reading changes without
            // a POST can't advance the tokens (they may only advance after a confirmed
            // send), so every run would re-page the same ever-growing backlog and throw
            // it away. Nothing local depends on this read: the dashboard reads Health
            // Connect directly, and the MacroFactor import writes Room on its own.
            if (!prefs.getBackendSyncEnabled()) {
                return SyncOutcome.Success(recordsSent = false)
            }

            val baseUrl = prefs.getBaseUrl()
            if (baseUrl.isNullOrBlank()) {
                return SyncOutcome.Failure("no server URL configured")
            }
            val apiKey = prefs.getApiKey()

            val tokens = healthConnect.getOrCreateChangesToken(prefs)
            val fetchResult = healthConnect.fetchChangesAndBuildPayload(tokens)
            val payload = fetchResult.payload
            val newTokens = fetchResult.newTokens

            if (payload == null) {
                // Nothing changed — still safe to advance tokens, since there was
                // no payload that could have failed to send.
                newTokens.forEach { (key, token) -> prefs.setChangesToken(key, token) }
                return SyncOutcome.Success(recordsSent = false, tokensReset = fetchResult.anyTokenExpired)
            }

            val client = IngestApiClient(baseUrl = baseUrl, apiKey = apiKey)
            val result = client.post(payload)

            result.fold(
                onSuccess = { skippedByServer ->
                    // Only advance the changes tokens after a confirmed successful
                    // POST — see Global Constraints in the plan this came from.
                    newTokens.forEach { (key, token) -> prefs.setChangesToken(key, token) }
                    SyncOutcome.Success(
                        recordsSent = true,
                        tokensReset = fetchResult.anyTokenExpired,
                        skippedByServer = skippedByServer,
                    )
                },
                onFailure = { e -> SyncOutcome.Failure(e.message ?: "unknown ingest failure") },
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            SyncOutcome.Failure(e.message ?: e::class.simpleName ?: "unexpected sync error")
        }
    }
}
