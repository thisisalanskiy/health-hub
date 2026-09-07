package com.healthhub.sync.data

import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

private fun meta(id: String, packageName: String): Metadata {
    val constructor = Metadata::class.java.getDeclaredConstructor(
        Int::class.javaPrimitiveType,
        String::class.java,
        DataOrigin::class.java,
        Instant::class.java,
        String::class.java,
        Long::class.javaPrimitiveType,
        Device::class.java,
    )
    constructor.isAccessible = true
    return constructor.newInstance(0, id, DataOrigin(packageName), Instant.now(), null, 0L, null) as Metadata
}

class SyncPayloadMapperTest {

    @Test
    fun `returns null when everything is empty`() {
        val payload = buildIngestPayload()
        assertNull(payload)
    }

    @Test
    fun `maps a single weight record into the weight bucket only`() {
        val record = WeightRecord(
            time = Instant.ofEpochMilli(1_786_148_049_000),
            zoneOffset = null,
            weight = Mass.kilograms(78.6),
            metadata = meta("b8b78fbd-61f6-393e-bc1e-6d0b101a168a", "com.oceanwing.smarthome"),
        )

        val payload = buildIngestPayload(weights = listOf(record))

        requireNotNull(payload)
        assertNull(payload.nutrition)
        assertEquals(1, payload.weight!!.records.size)
        val dto = payload.weight.records[0]
        assertEquals("b8b78fbd-61f6-393e-bc1e-6d0b101a168a", dto.metadata.id)
        assertEquals("com.oceanwing.smarthome", dto.metadata.dataOrigin.packageName)
        assertEquals(1_786_148_049_000L, dto.time)
        assertEquals(78.6, dto.weight.kilograms, 0.0001)
    }

    @Test
    fun `maps steps record fields`() {
        val record = StepsRecord(
            startTime = Instant.ofEpochMilli(1_786_150_000_000),
            endTime = Instant.ofEpochMilli(1_786_153_600_000),
            startZoneOffset = null,
            endZoneOffset = null,
            count = 1234,
            metadata = meta("steps-1", "com.garmin.android.apps.connectmobile"),
        )

        val payload = buildIngestPayload(steps = listOf(record))

        requireNotNull(payload)
        val dto = payload.steps!!.records[0]
        assertEquals(1234L, dto.count)
        assertEquals(1_786_150_000_000L, dto.startTime)
        assertEquals(1_786_153_600_000L, dto.endTime)
    }

    @Test
    fun `maps resting heart rate fields`() {
        val record = RestingHeartRateRecord(
            time = Instant.ofEpochMilli(1_786_150_000_000),
            zoneOffset = null,
            beatsPerMinute = 58,
            metadata = meta("rhr-1", "com.whoop.android"),
        )

        val payload = buildIngestPayload(restingHeartRates = listOf(record))

        requireNotNull(payload)
        val dto = payload.restingHeartRate!!.records[0]
        assertEquals(58L, dto.beatsPerMinute)
    }
}
