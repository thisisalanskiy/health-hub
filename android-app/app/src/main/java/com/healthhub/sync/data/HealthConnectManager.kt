package com.healthhub.sync.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.healthhub.sync.dashboard.DashboardRawData
import java.time.Instant
import kotlin.reflect.KClass

private val RECORD_TYPES: List<KClass<out androidx.health.connect.client.records.Record>> = listOf(
    WeightRecord::class,
    NutritionRecord::class,
    ExerciseSessionRecord::class,
    HeartRateRecord::class,
    RestingHeartRateRecord::class,
    StepsRecord::class,
    SleepSessionRecord::class,
)

class HealthConnectManager(private val context: Context) {

    val requiredPermissions: Set<String> = RECORD_TYPES
        .map { HealthPermission.getReadPermission(it) }
        .toSet()

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasAllPermissions(): Boolean {
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(requiredPermissions)
    }

    /** One changes-token per record type, keyed by simple class name. */
    suspend fun getOrCreateChangesToken(prefs: AppPreferences): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (type in RECORD_TYPES) {
            val key = type.simpleName!!
            val existing = prefs.getChangesToken(key)
            result[key] = existing ?: client.getChangesToken(ChangesTokenRequest(setOf(type)))
        }
        return result
    }

    suspend fun fetchChangesAndBuildPayload(tokens: Map<String, String>): Pair<IngestPayload?, Map<String, String>> {
        val weights = mutableListOf<WeightRecord>()
        val nutrition = mutableListOf<NutritionRecord>()
        val exercises = mutableListOf<ExerciseSessionRecord>()
        val heartRates = mutableListOf<HeartRateRecord>()
        val restingHeartRates = mutableListOf<RestingHeartRateRecord>()
        val steps = mutableListOf<StepsRecord>()
        val sleeps = mutableListOf<SleepSessionRecord>()
        val newTokens = mutableMapOf<String, String>()

        for ((typeKey, token) in tokens) {
            var currentToken = token
            while (true) {
                val response = client.getChanges(currentToken)
                for (change in response.changes) {
                    if (change !is UpsertionChange) continue
                    when (val record = change.record) {
                        is WeightRecord -> weights += record
                        is NutritionRecord -> nutrition += record
                        is ExerciseSessionRecord -> exercises += record
                        is HeartRateRecord -> heartRates += record
                        is RestingHeartRateRecord -> restingHeartRates += record
                        is StepsRecord -> steps += record
                        is SleepSessionRecord -> sleeps += record
                    }
                }
                currentToken = response.nextChangesToken
                if (!response.hasMore) break
            }
            newTokens[typeKey] = currentToken
        }

        val payload = buildIngestPayload(weights, nutrition, exercises, heartRates, restingHeartRates, steps, sleeps)
        return payload to newTokens
    }

    suspend fun readForDashboard(start: Instant, end: Instant): DashboardRawData {
        val range = TimeRangeFilter.between(start, end)
        return DashboardRawData(
            weights = client.readRecords(ReadRecordsRequest(WeightRecord::class, range)).records,
            nutrition = client.readRecords(ReadRecordsRequest(NutritionRecord::class, range)).records,
            exercises = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range)).records,
            heartRates = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, range)).records,
            restingHeartRates = client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, range)).records,
            steps = client.readRecords(ReadRecordsRequest(StepsRecord::class, range)).records,
            sleeps = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range)).records,
        )
    }

}
