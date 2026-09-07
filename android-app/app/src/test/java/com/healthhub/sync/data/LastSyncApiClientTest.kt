package com.healthhub.sync.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

class LastSyncApiClientTest {

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

    @Test
    fun `parses a real timestamp`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"latest":"2026-09-05T06:38:38+09:00"}"""))
        val client = LastSyncApiClient(baseUrl = server.url("/").toString().trimEnd('/'))

        val result = client.getLastSync()

        assertEquals(Instant.parse("2026-09-04T21:38:38Z"), result)
        val request = server.takeRequest()
        assertEquals("/api/last-sync", request.path)
    }

    @Test
    fun `returns null when latest is null`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"latest":null}"""))
        val client = LastSyncApiClient(baseUrl = server.url("/").toString().trimEnd('/'))

        assertNull(client.getLastSync())
    }

    @Test
    fun `returns null on a non-2xx response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val client = LastSyncApiClient(baseUrl = server.url("/").toString().trimEnd('/'))

        assertNull(client.getLastSync())
    }
}
