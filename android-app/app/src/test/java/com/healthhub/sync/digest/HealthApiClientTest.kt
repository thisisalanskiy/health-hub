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
    fun `throws on a non-2xx response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val client = HealthApiClient(baseUrl = server.url("/").toString().trimEnd('/'))

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { client.getSnapshot() }
        }
    }
}
