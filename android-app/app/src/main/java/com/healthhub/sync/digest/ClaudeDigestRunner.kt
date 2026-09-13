package com.healthhub.sync.digest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.healthhub.sync.data.AppPreferences
import kotlinx.coroutines.CancellationException

private const val DIGEST_DAYS = 7

suspend fun prepareForClaude(appPreferences: AppPreferences, context: Context): String {
    val baseUrl = appPreferences.getBaseUrl()
    if (baseUrl.isNullOrBlank()) return "Set a server URL in Settings first."

    return try {
        val client = HealthApiClient(baseUrl)
        val text = ClaudeDigestFormatter.format(
            days = DIGEST_DAYS,
            snapshot = client.getSnapshot(),
            weight = client.getWeight(DIGEST_DAYS),
            tdee = client.getTdee(DIGEST_DAYS),
            nutrition = client.getNutrition(DIGEST_DAYS),
            recovery = client.getRecovery(DIGEST_DAYS),
            sleep = client.getSleep(DIGEST_DAYS),
            workouts = client.getWorkouts(DIGEST_DAYS),
        )
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Health digest", text))
        "Copied $DIGEST_DAYS days of data to clipboard."
    } catch (e: CancellationException) {
        // Never swallow coroutine cancellation.
        throw e
    } catch (e: Exception) {
        "Couldn't prepare digest: ${e.message ?: e::class.simpleName}"
    }
}
