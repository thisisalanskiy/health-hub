package com.healthhub.sync.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.healthhub.sync.BuildConfig
import com.healthhub.sync.data.AppPreferences
import com.healthhub.sync.data.HealthConnectManager
import com.healthhub.sync.data.runFillGap
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(appPreferences: AppPreferences, healthConnectManager: HealthConnectManager) {
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var showServerDialog by remember { mutableStateOf(false) }
    var fillGapStatus by remember { mutableStateOf<String?>(null) }
    var fillGapRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val log by appPreferences.syncLogFlow().collectAsState(initial = emptyList())

    LaunchedEffect(Unit) {
        baseUrl = appPreferences.getBaseUrl() ?: ""
        apiKey = appPreferences.getApiKey() ?: ""
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        // Section 1: Server settings
        Text("Server settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
        SyncStatusLine(log)
        log.firstOrNull()?.let { latest ->
            Text(
                "Last sync: ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(latest.timestampMillis))}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(Modifier.padding(top = 8.dp)) {
            OutlinedButton(
                enabled = !fillGapRunning,
                onClick = {
                    fillGapRunning = true
                    fillGapStatus = null
                    scope.launch {
                        fillGapStatus = runFillGap(baseUrl, apiKey, healthConnectManager)
                        fillGapRunning = false
                    }
                },
            ) {
                Text(if (fillGapRunning) "Filling gap…" else "Fill Gap")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { showServerDialog = true }) { Text("Change") }
        }
        fillGapStatus?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }

        // Section 2: Sync Log
        Text("Sync Log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(log) { entry ->
                val formatted = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(entry.timestampMillis))
                Text("$formatted — ${if (entry.success) "OK" else "FAILED"}: ${entry.message}")
            }
        }

        Text(
            "Health Hub v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    if (showServerDialog) {
        var draftBaseUrl by remember { mutableStateOf(baseUrl) }
        var draftApiKey by remember { mutableStateOf(apiKey) }
        AlertDialog(
            onDismissRequest = { showServerDialog = false },
            title = { Text("Server settings") },
            text = {
                Column {
                    OutlinedTextField(
                        value = draftBaseUrl,
                        onValueChange = { draftBaseUrl = it },
                        label = { Text("Server base URL (e.g. http://your-tailscale-host:8000)") },
                    )
                    OutlinedTextField(
                        value = draftApiKey,
                        onValueChange = { draftApiKey = it },
                        label = { Text("API key") },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmedUrl = draftBaseUrl.trimEnd('/')
                    scope.launch {
                        appPreferences.setBaseUrl(trimmedUrl)
                        appPreferences.setApiKey(draftApiKey)
                    }
                    baseUrl = trimmedUrl
                    apiKey = draftApiKey
                    showServerDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showServerDialog = false }) { Text("Cancel") } },
        )
    }
}
