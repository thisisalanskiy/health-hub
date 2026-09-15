package com.healthhub.sync.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.healthhub.sync.dashboard.DashboardMetric
import com.healthhub.sync.dashboard.DashboardRawData
import com.healthhub.sync.dashboard.loadLocalTdee
import com.healthhub.sync.data.AppPreferences
import com.healthhub.sync.data.HealthConnectManager
import com.healthhub.sync.data.local.AppDatabase
import com.healthhub.sync.digest.TdeeResponse
import com.healthhub.sync.digest.prepareForClaude
import com.healthhub.sync.sync.SyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val HEALTH_CONNECT_UNAVAILABLE =
    "Health Connect isn't available on this device. Install or update it, then reopen this screen."

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun DashboardScreen(
    healthConnectManager: HealthConnectManager,
    appPreferences: AppPreferences,
    isWideScreen: Boolean,
    onMetricClick: (DashboardMetric) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val context = LocalContext.current
    var rawData by remember { mutableStateOf<DashboardRawData?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var hasForegroundPermissions by remember { mutableStateOf(false) }
    var hasAllPermissions by remember { mutableStateOf(false) }
    var digestStatus by remember { mutableStateOf<String?>(null) }
    var digestRunning by remember { mutableStateOf(false) }
    var tdeeData by remember { mutableStateOf<TdeeResponse?>(null) }
    var tdeeError by remember { mutableStateOf<String?>(null) }
    val zone = remember { ZoneId.systemDefault() }
    val scope = rememberCoroutineScope()
    val log by appPreferences.syncLogFlow().collectAsState(initial = emptyList())

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

    // Run-once-per-screen-entry: permission check + scheduling the periodic
    // background worker. Deliberately a SEPARATE LaunchedEffect from the one
    // below — combining them would re-check permissions and re-enqueue the
    // periodic work every time the user just changes the date range.
    LaunchedEffect(Unit) {
        try {
            // Guarded for the same reason as the read below: these go through
            // HealthConnectClient.getOrCreate, which throws IllegalStateException
            // when Health Connect isn't installed — uncaught inside a
            // LaunchedEffect that would crash the app on first frame.
            if (healthConnectManager.isAvailable()) {
                hasForegroundPermissions = healthConnectManager.hasForegroundPermissions()
                hasAllPermissions = healthConnectManager.hasAllPermissions()
            } else {
                loadError = HEALTH_CONNECT_UNAVAILABLE
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = "Couldn't reach Health Connect: ${e.message ?: e::class.simpleName}"
        }
        SyncScheduler.schedulePeriodic(context)
    }

    // Keyed on hasForegroundPermissions: without that key a user who grants
    // permissions from the banner above would sit on "Loading…" forever,
    // because the read below is skipped until permissions actually exist.
    LaunchedEffect(hasForegroundPermissions) {
        if (!hasForegroundPermissions) return@LaunchedEffect
        loadError = null
        val end = Instant.now()
        val start = end.minus(7, ChronoUnit.DAYS)
        rawData = try {
            if (!healthConnectManager.isAvailable()) {
                loadError = HEALTH_CONNECT_UNAVAILABLE
                null
            } else {
                // readRecords throws SecurityException if a permission was revoked
                // between the check and the read, and the client itself can throw
                // IllegalStateException if Health Connect goes away — an uncaught
                // throw here would take down the whole app.
                healthConnectManager.readAllRecords(start, end)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = "Couldn't read from Health Connect: ${e.message ?: e::class.simpleName}"
            null
        }
    }

    // TDEE is computed on-device now (Health Connect weight + nutrition, plus any
    // imported MacroFactor expenditure for the comparison line), so it needs no
    // server — but unlike before it does need Health Connect permissions, hence
    // the same key as the read above. Kept as its own effect because it reaches
    // further back than that 7-day window and also touches Room.
    LaunchedEffect(hasForegroundPermissions) {
        if (!hasForegroundPermissions) return@LaunchedEffect
        tdeeError = null
        tdeeData = try {
            // Both the Health Connect reads and the Room query below can throw
            // (revoked permission, Health Connect gone, a disk failure) — an uncaught
            // throw out of this LaunchedEffect would crash the screen.
            loadLocalTdee(
                days = 7,
                healthConnectManager = healthConnectManager,
                macroFactorDailyDao = AppDatabase.getInstance(context).macroFactorDailyDao(),
                zone = zone,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tdeeError = "TDEE unavailable: ${e.message ?: e::class.simpleName}"
            null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Health Dashboard", style = MaterialTheme.typography.headlineSmall)
                    SyncStatusLine(log)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { SyncScheduler.syncNow(context) }) {
                        Text("Sync Now")
                    }
                }
            }

            if (!hasAllPermissions) {
                Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        if (!hasForegroundPermissions) {
                            Text("Health Connect permissions not granted yet.")
                            Button(onClick = { foregroundLauncher.launch(healthConnectManager.foregroundPermissions) }) {
                                Text("Grant permissions")
                            }
                        } else {
                            Text("Health Connect permissions granted — background sync needs one more step.")
                            Button(onClick = { backgroundLauncher.launch(setOf(healthConnectManager.backgroundPermission)) }) {
                                Text("Enable background sync")
                            }
                        }
                    }
                }
            }

            val data = rawData
            val error = loadError
            if (data == null) {
                Text(
                    text = when {
                        error != null -> error
                        !hasForegroundPermissions -> "Grant Health Connect permissions to load your dashboard."
                        else -> "Loading from Health Connect…"
                    },
                    color = if (error != null) MaterialTheme.colorScheme.error else Color.Unspecified,
                    modifier = Modifier.padding(top = 16.dp),
                )
            } else {
                val metrics = DashboardMetric.values()

                // Bottom padding is bumped to 88.dp (was 16.dp) so the last row of cards clears the
                // FAB (56.dp button + its own 16.dp padding, plus room for the status line above it)
                // instead of the FAB visually overlapping the final card.
                if (isWideScreen) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.padding(top = 16.dp),
                        contentPadding = PaddingValues(bottom = 88.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(metrics.size) { index ->
                            val metric = metrics[index]
                            DashboardMetricCard(
                                metric, data, tdeeData, tdeeError, zone,
                                onClick = { onMetricClick(metric) },
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.padding(top = 16.dp),
                        contentPadding = PaddingValues(bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(metrics.size) { index ->
                            val metric = metrics[index]
                            DashboardMetricCard(
                                metric, data, tdeeData, tdeeError, zone,
                                onClick = { onMetricClick(metric) },
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            horizontalAlignment = Alignment.End,
        ) {
            digestStatus?.let {
                // Auto-dismiss after a few seconds, toast-style, rather than sitting there
                // until overwritten by the next run — keyed on the message itself so a new
                // status resets the clock instead of inheriting a countdown already in flight.
                LaunchedEffect(it) {
                    delay(4000)
                    digestStatus = null
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            FloatingActionButton(
                onClick = {
                    if (!digestRunning) {
                        digestRunning = true
                        digestStatus = null
                        scope.launch {
                            digestStatus = prepareForClaude(appPreferences, context)
                            digestRunning = false
                        }
                    }
                },
            ) {
                if (digestRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    // Generic sparkle glyph — NOT Anthropic's actual trademarked Claude logo
                    Text("✨️")
                }
            }
        }
    }
}
