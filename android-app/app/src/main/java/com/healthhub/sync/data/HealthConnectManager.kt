package com.healthhub.sync.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
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
    Vo2MaxRecord::class,
    HeartRateVariabilityRmssdRecord::class,
    BodyFatRecord::class,
    BasalMetabolicRateRecord::class,
)

private val RECORD_TYPES_BY_KEY: Map<String, KClass<out androidx.health.connect.client.records.Record>> =
    RECORD_TYPES.associateBy { it.simpleName!! }

/**
 * Result of [HealthConnectManager.fetchChangesAndBuildPayload].
 *
 * @property anyTokenExpired true if at least one record type's changes token had expired
 * (per Health Connect's ~30 day retention window) and was reset to a fresh baseline during
 * this call, meaning any changes between the expiry and now for that type were unavoidably lost.
 */
data class FetchChangesResult(
    val payload: IngestPayload?,
    val newTokens: Map<String, String>,
    val anyTokenExpired: Boolean,
)

class HealthConnectManager(private val context: Context) {

    // Health Connect rejects a permission request that bundles
    // READ_HEALTH_DATA_IN_BACKGROUND together with first-time foreground read
    // permissions (its request activity finishes itself immediately, granting
    // nothing) — background access must be requested as its own separate step,
    // after the foreground permissions are already granted. Hence the split.
    val foregroundPermissions: Set<String> = RECORD_TYPES
        .map { HealthPermission.getReadPermission(it) }
        .toSet()

    val backgroundPermission: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    val requiredPermissions: Set<String> = foregroundPermissions + backgroundPermission

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasForegroundPermissions(): Boolean {
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(foregroundPermissions)
    }

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
            val token = existing ?: client.getChangesToken(ChangesTokenRequest(setOf(type))).also { newToken ->
                // Persist immediately: otherwise every failed sync attempt before
                // the first successful one mints and discards a fresh baseline
                // token, silently skipping everything recorded since install.
                prefs.setChangesToken(key, newToken)
            }
            result[key] = token
        }
        return result
    }

    suspend fun fetchChangesAndBuildPayload(tokens: Map<String, String>): FetchChangesResult {
        val weights = mutableListOf<WeightRecord>()
        val nutrition = mutableListOf<NutritionRecord>()
        val exercises = mutableListOf<ExerciseSessionRecord>()
        val heartRates = mutableListOf<HeartRateRecord>()
        val restingHeartRates = mutableListOf<RestingHeartRateRecord>()
        val steps = mutableListOf<StepsRecord>()
        val sleeps = mutableListOf<SleepSessionRecord>()
        val vo2MaxReadings = mutableListOf<Vo2MaxRecord>()
        val hrvReadings = mutableListOf<HeartRateVariabilityRmssdRecord>()
        val bodyFatReadings = mutableListOf<BodyFatRecord>()
        val bmrReadings = mutableListOf<BasalMetabolicRateRecord>()
        val newTokens = mutableMapOf<String, String>()
        var anyTokenExpired = false

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
                        is Vo2MaxRecord -> vo2MaxReadings += record
                        is HeartRateVariabilityRmssdRecord -> hrvReadings += record
                        is BodyFatRecord -> bodyFatReadings += record
                        is BasalMetabolicRateRecord -> bmrReadings += record
                    }
                }
                if (response.changesTokenExpired) {
                    // The token outlived Health Connect's retention window and is no
                    // longer usable. Recover by minting a fresh baseline for this
                    // record type — equivalent to a first-ever sync for it — rather
                    // than getting permanently stuck advancing a dead token forever.
                    // Any changes between the expiry and now are unavoidably lost.
                    anyTokenExpired = true
                    val recordType = RECORD_TYPES_BY_KEY.getValue(typeKey)
                    currentToken = client.getChangesToken(ChangesTokenRequest(setOf(recordType)))
                    break
                }
                currentToken = response.nextChangesToken
                if (!response.hasMore) break
            }
            newTokens[typeKey] = currentToken
        }

        val payload = buildIngestPayload(
            weights, nutrition, exercises, heartRates, restingHeartRates, steps, sleeps,
            vo2MaxReadings, hrvReadings, bodyFatReadings, bmrReadings,
        )
        return FetchChangesResult(payload, newTokens, anyTokenExpired)
    }

    /**
     * Reads all 11 Health Connect record types directly (not via the Changes API) over
     * [start, end]. Used both for the offline dashboard and for one-shot historical
     * backfill (Task 6) — Health Connect retains actual records indefinitely regardless
     * of the Changes API's ~30-day changes-log window, so this can reach arbitrarily far
     * into the past.
     */
    suspend fun readAllRecords(start: Instant, end: Instant): DashboardRawData {
        val range = TimeRangeFilter.between(start, end)
        return DashboardRawData(
            weights = client.readRecords(ReadRecordsRequest(WeightRecord::class, range)).records,
            nutrition = client.readRecords(ReadRecordsRequest(NutritionRecord::class, range)).records,
            exercises = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range)).records,
            heartRates = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, range)).records,
            restingHeartRates = client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, range)).records,
            steps = client.readRecords(ReadRecordsRequest(StepsRecord::class, range)).records,
            sleeps = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range)).records,
            vo2MaxReadings = client.readRecords(ReadRecordsRequest(Vo2MaxRecord::class, range)).records,
            hrvReadings = client.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, range)).records,
            bodyFatReadings = client.readRecords(ReadRecordsRequest(BodyFatRecord::class, range)).records,
            bmrReadings = client.readRecords(ReadRecordsRequest(BasalMetabolicRateRecord::class, range)).records,
        )
    }

}
