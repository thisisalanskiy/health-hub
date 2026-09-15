package com.healthhub.sync.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "health_hub_sync_prefs")

class AppPreferences(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val baseUrlKey = stringPreferencesKey("base_url")
    private val apiKeyKey = stringPreferencesKey("api_key")
    private val syncLogKey = stringPreferencesKey("sync_log_json")
    private val backendSyncEnabledKey = booleanPreferencesKey("backend_sync_enabled")
    private fun changesTokenKey(recordTypeKey: String) = stringPreferencesKey("changes_token_$recordTypeKey")

    /**
     * The application context this instance was built with. Exposed so suspend helpers that
     * already receive an [AppPreferences] (the dashboard's local TDEE path, which needs Room)
     * can reach it without threading a second Context parameter through the Composables that
     * call them.
     */
    internal val appContext: Context get() = context.applicationContext

    suspend fun getBaseUrl(): String? = context.dataStore.data.first()[baseUrlKey]

    suspend fun setBaseUrl(value: String) {
        context.dataStore.edit { it[baseUrlKey] = value }
    }

    suspend fun getApiKey(): String? = context.dataStore.data.first()[apiKeyKey]

    suspend fun setApiKey(value: String) {
        context.dataStore.edit { it[apiKeyKey] = value }
    }

    /**
     * Whether synced data may additionally be POSTed to the self-hosted backend. Opt-in:
     * absent means false, so a fresh install never talks to a server. This gates only the
     * outbound POSTs — every on-device read (Health Connect, the dashboard's local TDEE)
     * runs regardless.
     */
    suspend fun getBackendSyncEnabled(): Boolean =
        context.dataStore.data.first()[backendSyncEnabledKey] ?: false

    suspend fun setBackendSyncEnabled(value: Boolean) {
        context.dataStore.edit { it[backendSyncEnabledKey] = value }
    }

    suspend fun getChangesToken(recordTypeKey: String): String? =
        context.dataStore.data.first()[changesTokenKey(recordTypeKey)]

    // Known gap: does not check ChangesResponse.changesTokenExpired (see spec's Known Gaps).
    suspend fun setChangesToken(recordTypeKey: String, token: String) {
        context.dataStore.edit { it[changesTokenKey(recordTypeKey)] = token }
    }

    fun syncLogFlow(): Flow<List<SyncLogEntry>> = context.dataStore.data.map { prefs ->
        prefs[syncLogKey]?.let { json.decodeFromString<List<SyncLogEntry>>(it) } ?: emptyList()
    }

    suspend fun appendSyncLog(entry: SyncLogEntry) {
        context.dataStore.edit { prefs ->
            val existing = prefs[syncLogKey]?.let { json.decodeFromString<List<SyncLogEntry>>(it) } ?: emptyList()
            prefs[syncLogKey] = json.encodeToString(trimSyncLog(existing, entry))
        }
    }
}
