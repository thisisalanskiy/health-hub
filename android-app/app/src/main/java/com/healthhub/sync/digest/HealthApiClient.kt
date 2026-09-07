package com.healthhub.sync.digest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class HealthApiClient(
    private val baseUrl: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun fetchBody(path: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl$path").get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("$path failed: HTTP ${response.code}")
            }
            response.body?.string() ?: throw IllegalStateException("$path returned an empty body")
        }
    }

    suspend fun getSnapshot(): SnapshotResponse = json.decodeFromString(fetchBody("/api/snapshot"))
    suspend fun getWeight(days: Int): WeightResponse = json.decodeFromString(fetchBody("/api/weight?days=$days"))
    suspend fun getTdee(days: Int): TdeeResponse = json.decodeFromString(fetchBody("/api/tdee?days=$days"))
    suspend fun getNutrition(days: Int): NutritionResponse = json.decodeFromString(fetchBody("/api/nutrition?days=$days"))
    suspend fun getRecovery(days: Int): RecoveryResponse = json.decodeFromString(fetchBody("/api/recovery?days=$days"))
    suspend fun getSleep(days: Int): SleepResponse = json.decodeFromString(fetchBody("/api/sleep?days=$days"))
    suspend fun getWorkouts(days: Int): WorkoutsResponse = json.decodeFromString(fetchBody("/api/workouts?days=$days"))
}
