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

            val baseUrl = prefs.getBaseUrl()
            if (baseUrl.isNullOrBlank()) {
                return SyncOutcome.Failure("no server URL configured")
            }
            val apiKey = prefs.getApiKey()

            val tokens = healthConnect.getOrCreateChangesToken(prefs)
            val (payload, newTokens) = healthConnect.fetchChangesAndBuildPayload(tokens)

            if (payload == null) {
                // Nothing changed — still safe to advance tokens, since there was
                // no payload that could have failed to send.
                newTokens.forEach { (key, token) -> prefs.setChangesToken(key, token) }
                return SyncOutcome.Success(recordsSent = false)
            }

            val client = IngestApiClient(baseUrl = baseUrl, apiKey = apiKey)
            val result = client.post(payload)

            result.fold(
                onSuccess = {
                    // Only advance the changes tokens after a confirmed successful
                    // POST — see Global Constraints in the plan this came from.
                    newTokens.forEach { (key, token) -> prefs.setChangesToken(key, token) }
                    SyncOutcome.Success(recordsSent = true)
                },
                onFailure = { e -> SyncOutcome.Failure(e.message ?: "unknown ingest failure") },
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            SyncOutcome.Failure(e.message ?: e::class.simpleName ?: "unexpected sync error")
        }
    }
}
