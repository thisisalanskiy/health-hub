package com.healthhub.sync.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant

class LastSyncApiClient(
    private val baseUrl: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getLastSync(): Instant? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/last-sync").get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val latest = json.parseToJsonElement(body).jsonObject["latest"]?.jsonPrimitive?.contentOrNull
                latest?.let { Instant.parse(it) }
            }
        } catch (e: Exception) {
            null
        }
    }
}
