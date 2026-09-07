package com.healthhub.sync.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val json = Json { encodeDefaults = false }

class IngestApiClient(
    private val baseUrl: String,
    private val apiKey: String?,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    suspend fun post(payload: IngestPayload): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = json.encodeToString(payload).toRequestBody("application/json".toMediaType())
            val requestBuilder = Request.Builder()
                .url("$baseUrl/ingest")
                .post(body)
            if (!apiKey.isNullOrBlank()) {
                requestBuilder.addHeader("X-API-Key", apiKey)
            }
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("ingest failed: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
