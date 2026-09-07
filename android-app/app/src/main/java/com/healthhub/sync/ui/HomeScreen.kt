package com.healthhub.sync.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.healthhub.sync.data.AppPreferences
import com.healthhub.sync.data.HealthConnectManager
import com.healthhub.sync.sync.SyncScheduler

@Composable
fun HomeScreen(healthConnectManager: HealthConnectManager, appPreferences: AppPreferences) {
    val context = LocalContext.current
    var hasForegroundPermissions by remember { mutableStateOf(false) }
    var hasAllPermissions by remember { mutableStateOf(false) }

    // Foreground read permissions — requested first, as their own batch.
    val foregroundLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        hasForegroundPermissions = granted.containsAll(healthConnectManager.foregroundPermissions)
        hasAllPermissions = granted.containsAll(healthConnectManager.requiredPermissions)
    }

    // Background read permission — must be requested separately, only after
    // the foreground permissions are already granted (see HealthConnectManager).
    val backgroundLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        hasAllPermissions = granted.containsAll(healthConnectManager.requiredPermissions)
    }

    LaunchedEffect(Unit) {
        hasForegroundPermissions = healthConnectManager.hasForegroundPermissions()
        hasAllPermissions = healthConnectManager.hasAllPermissions()
        SyncScheduler.schedulePeriodic(context)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Health Hub Sync", style = MaterialTheme.typography.headlineSmall)

        if (!hasForegroundPermissions) {
            Text("Health Connect permissions not granted yet.")
            Button(onClick = { foregroundLauncher.launch(healthConnectManager.foregroundPermissions) }) {
                Text("Grant permissions")
            }
        } else if (!hasAllPermissions) {
            Text("Health Connect permissions granted — background sync needs one more step.")
            Button(onClick = { backgroundLauncher.launch(setOf(healthConnectManager.backgroundPermission)) }) {
                Text("Enable background sync")
            }
        } else {
            Text("Health Connect permissions granted.")
        }

        Button(
            onClick = { SyncScheduler.syncNow(context) },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("Sync Now")
        }

        Text(
            "Sync history moved to Settings.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}
