package com.healthhub.sync.digest

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class HealthApiClientTest {

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
    fun `getSnapshot parses the real response shape`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"weight_kg":77.4,"calories_kcal":2100.0,"tdee_kcal":2350.0,"steps":8200,"sleep_min":432.0}""",
            ),
        )
        val client = HealthApiClient(baseUrl = server.url("/").toString().trimEnd('/'))

        val snapshot = client.getSnapshot()

        assertEquals(77.4, snapshot.weightKg!!, 0.01)
        assertEquals(8200L, snapshot.steps)
    }

    @Test
    fun `getWorkouts parses nested weekly entries`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"weekly":[{"week":"2026-09-01","total_min":210.0,"sessions":4}],"recent":[]}""",
            ),
        )
        val client = HealthApiClient(baseUrl = server.url("/").toString().trimEnd('/'))

        val workouts = client.getWorkouts(7)

        assertEquals(1, workouts.weekly.size)
        assertEquals(4, workouts.weekly[0].sessions)
        val request = server.takeRequest()
        assertEquals("/api/workouts?days=7", request.path)
    }

    @Test
    fun `getBodyTrends parses the response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"dates":["2026-01-01","2026-01-02"],"vo2_max":[42.5,null],"body_fat_percent":[0.257,null],"bmr_kcal":[1680.0,null]}"""
        ))
        val client = HealthApiClient(baseUrl = server.url("/").toString().trimEnd('/'))

        val result = client.getBodyTrends(7)

        assertEquals(listOf("2026-01-01", "2026-01-02"), result.dates)
        assertEquals(listOf(42.5, null), result.vo2Max)
        assertEquals(listOf(0.257, null), result.bodyFatPercent)
        assertEquals(listOf(1680.0, null), result.bmrKcal)
        val request = server.takeRequest()
        assertEquals("/api/body-trends?days=7", request.path)
    }

    @Test
    fun `throws on a non-2xx response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val client = HealthApiClient(baseUrl = server.url("/").toString().trimEnd('/'))

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { client.getSnapshot() }
        }
    }
}
