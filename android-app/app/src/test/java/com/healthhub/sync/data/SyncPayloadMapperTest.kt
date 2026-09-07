package com.healthhub.sync.data

import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    /**
     * Contract test for [buildIngestPayload]: every record-type parameter must land in
     * its own bucket. All 11 parameters default to `emptyList()`, so a call site that
     * forgets one compiles cleanly and silently drops that metric — exactly the bug that
     * shipped in FillGapRunner, where the 4 body-metric lists were never passed and the
     * only path that can recover their pre-upgrade history sent nothing.
     */
    @Test
    fun `populates all 11 buckets when every record type is supplied`() {
        val t = Instant.ofEpochMilli(1_786_150_000_000)
        val tEnd = Instant.ofEpochMilli(1_786_153_600_000)

        val payload = buildIngestPayload(
            weights = listOf(
                WeightRecord(
                    time = t,
                    zoneOffset = null,
                    weight = Mass.kilograms(78.6),
                    metadata = meta("w-1", "com.oceanwing.smarthome"),
                ),
            ),
            nutrition = listOf(
                NutritionRecord(
                    startTime = t,
                    startZoneOffset = null,
                    endTime = tEnd,
                    endZoneOffset = null,
                    protein = Mass.grams(30.0),
                    totalFat = Mass.grams(10.0),
                    totalCarbohydrate = Mass.grams(50.0),
                    metadata = meta("n-1", "com.macrofactorapp"),
                ),
            ),
            exercises = listOf(
                ExerciseSessionRecord(t, null, tEnd, null, meta("e-1", "com.garmin"), 56),
            ),
            heartRates = listOf(
                HeartRateRecord(
                    startTime = t,
                    startZoneOffset = null,
                    endTime = tEnd,
                    endZoneOffset = null,
                    samples = listOf(HeartRateRecord.Sample(t, 72)),
                    metadata = meta("hr-1", "com.garmin"),
                ),
            ),
            restingHeartRates = listOf(
                RestingHeartRateRecord(t, null, 58, meta("rhr-1", "com.whoop.android")),
            ),
            steps = listOf(
                StepsRecord(
                    startTime = t,
                    startZoneOffset = null,
                    endTime = tEnd,
                    endZoneOffset = null,
                    count = 1234,
                    metadata = meta("s-1", "com.garmin"),
                ),
            ),
            sleeps = listOf(
                SleepSessionRecord(
                    startTime = t,
                    startZoneOffset = null,
                    endTime = tEnd,
                    endZoneOffset = null,
                    metadata = meta("sl-1", "com.whoop.android"),
                ),
            ),
            vo2MaxReadings = listOf(
                Vo2MaxRecord(t, null, meta("v-1", "com.garmin"), 42.5, Vo2MaxRecord.MEASUREMENT_METHOD_OTHER),
            ),
            hrvReadings = listOf(
                HeartRateVariabilityRmssdRecord(t, null, 50.0, meta("hrv-1", "com.whoop.android")),
            ),
            bodyFatReadings = listOf(
                BodyFatRecord(t, null, Percentage(25.7), meta("bf-1", "com.oceanwing.smarthome")),
            ),
            bmrReadings = listOf(
                BasalMetabolicRateRecord(
                    t,
                    null,
                    Power.kilocaloriesPerDay(1680.0),
                    meta("bmr-1", "com.oceanwing.smarthome"),
                ),
            ),
        )

        requireNotNull(payload)
        assertNotNull("weight bucket missing", payload.weight)
        assertNotNull("nutrition bucket missing", payload.nutrition)
        assertNotNull("exercise bucket missing", payload.exercise)
        assertNotNull("heartRate bucket missing", payload.heartRate)
        assertNotNull("restingHeartRate bucket missing", payload.restingHeartRate)
        assertNotNull("steps bucket missing", payload.steps)
        assertNotNull("sleep bucket missing", payload.sleep)
        assertNotNull("vo2Max bucket missing", payload.vo2Max)
        assertNotNull("heartRateVariability bucket missing", payload.heartRateVariability)
        assertNotNull("bodyFat bucket missing", payload.bodyFat)
        assertNotNull("bmr bucket missing", payload.bmr)

        // Each bucket carries exactly the one record it was given.
        assertEquals(1, payload.weight!!.records.size)
        assertEquals(1, payload.nutrition!!.records.size)
        assertEquals(1, payload.exercise!!.records.size)
        assertEquals(1, payload.heartRate!!.records.size)
        assertEquals(1, payload.restingHeartRate!!.records.size)
        assertEquals(1, payload.steps!!.records.size)
        assertEquals(1, payload.sleep!!.records.size)
        assertEquals(1, payload.vo2Max!!.records.size)
        assertEquals(1, payload.heartRateVariability!!.records.size)
        assertEquals(1, payload.bodyFat!!.records.size)
        assertEquals(1, payload.bmr!!.records.size)
    }
}
