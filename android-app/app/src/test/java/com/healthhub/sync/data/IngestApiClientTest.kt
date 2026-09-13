package com.healthhub.sync.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IngestApiClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun samplePayload() = buildIngestPayload(
        weights = emptyList(),
    ) ?: IngestPayload(nutrition = NutritionBucket(emptyList()))

    @Test
    fun `sends api key header and returns success on 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val client = IngestApiClient(baseUrl = server.url("/").toString().trimEnd('/'), apiKey = "secret123")

        val result = client.post(samplePayload())

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("/ingest", request.path)
        assertEquals("secret123", request.getHeader("X-API-Key"))
        assertEquals("POST", request.method)
    }

    @Test
    fun `omits header when no api key configured`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val client = IngestApiClient(baseUrl = server.url("/").toString().trimEnd('/'), apiKey = null)

        client.post(samplePayload())

        val request = server.takeRequest()
        assertEquals(null, request.getHeader("X-API-Key"))
    }

    @Test
    fun `returns failure on non-2xx response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val client = IngestApiClient(baseUrl = server.url("/").toString().trimEnd('/'), apiKey = "wrong")

        val result = client.post(samplePayload())

        assertTrue(result.isFailure)
    }

    @Test
    fun `parses skipped count from a successful response body`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"status":"ok","ingested":{"weight":1,"skipped":2}}"""),
        )
        val client = IngestApiClient(baseUrl = server.url("/").toString().trimEnd('/'), apiKey = null)

        val result = client.post(samplePayload())

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull())
    }

    @Test
    fun `defaults skipped count to zero when absent from the response body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val client = IngestApiClient(baseUrl = server.url("/").toString().trimEnd('/'), apiKey = null)

        val result = client.post(samplePayload())

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull())
    }
}
