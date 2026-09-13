package com.healthhub.sync.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    /**
     * POSTs [payload] to the ingest endpoint. On a successful (2xx) response, returns the
     * number of records the server silently skipped as malformed (0 if the field is absent,
     * or if the body couldn't be parsed for any reason — a body-parsing hiccup shouldn't fail
     * a sync whose HTTP status was itself successful).
     */
    suspend fun post(payload: IngestPayload): Result<Int> = withContext(Dispatchers.IO) {
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
                    Result.success(parseSkippedCount(response.body?.string()))
                } else {
                    Result.failure(IllegalStateException("ingest failed: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseSkippedCount(responseBody: String?): Int = try {
        responseBody
            ?.let { json.parseToJsonElement(it) }
            ?.jsonObject
            ?.get("ingested")
            ?.jsonObject
            ?.get("skipped")
            ?.jsonPrimitive
            ?.intOrNull
            ?: 0
    } catch (e: Exception) {
        0
    }
}
