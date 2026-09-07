package com.healthhub.sync.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.healthhub.sync.dashboard.ChartSeries
import com.healthhub.sync.dashboard.DashboardAggregator
import com.healthhub.sync.dashboard.DashboardRawData
import com.healthhub.sync.dashboard.trendDelta
import com.healthhub.sync.data.AppPreferences
import com.healthhub.sync.data.HealthConnectManager
import com.healthhub.sync.digest.HealthApiClient
import com.healthhub.sync.digest.TdeeResponse
import com.healthhub.sync.digest.prepareForClaude
import com.healthhub.sync.sync.SyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private val RANGE_OPTIONS = listOf(7, 30, 90)

private const val HEALTH_CONNECT_UNAVAILABLE =
    "Health Connect isn't available on this device. Install or update it, then reopen this screen."

@Composable
fun DashboardScreen(
    healthConnectManager: HealthConnectManager,
    appPreferences: AppPreferences,
    isWideScreen: Boolean,
) {
    val context = LocalContext.current
    var selectedDays by remember { mutableStateOf(30) }
    var rawData by remember { mutableStateOf<DashboardRawData?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var rangeMenuExpanded by remember { mutableStateOf(false) }
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

    // Keyed on hasForegroundPermissions as well as selectedDays: without that key
    // a user who grants permissions from the banner above would sit on "Loading…"
    // forever, because the read below is skipped until permissions actually exist.
    LaunchedEffect(selectedDays, hasForegroundPermissions) {
        if (!hasForegroundPermissions) return@LaunchedEffect
        loadError = null
        val end = Instant.now()
        val start = end.minus(selectedDays.toLong(), ChronoUnit.DAYS)
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

    // Separate from the Health-Connect-gated effect above: TDEE comes from the
    // server over Tailscale, not Health Connect, so it depends only on the
    // selected date range and the configured server URL — not on permissions.
    LaunchedEffect(selectedDays) {
        tdeeError = null
        val baseUrl = appPreferences.getBaseUrl()
        if (baseUrl.isNullOrBlank()) {
            tdeeData = null
            return@LaunchedEffect
        }
        tdeeData = try {
            HealthApiClient(baseUrl).getTdee(selectedDays)
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
                    Spacer(Modifier.width(8.dp))
                    Box {
                        OutlinedButton(onClick = { rangeMenuExpanded = true }) {
                            Text("${selectedDays}d ▾")
                        }
                        DropdownMenu(expanded = rangeMenuExpanded, onDismissRequest = { rangeMenuExpanded = false }) {
                            RANGE_OPTIONS.forEach { days ->
                                DropdownMenuItem(
                                    text = { Text("${days}d") },
                                    onClick = {
                                        selectedDays = days
                                        rangeMenuExpanded = false
                                    },
                                )
                            }
                        }
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
                val charts: List<@Composable () -> Unit> = listOf(
                    {
                        val series = DashboardAggregator.dailyWeightTrend(data, zone)
                        val current = series.values.lastOrNull()?.let { "%.1f kg".format(it) } ?: "no data"
                        ChartHeader("Weight", "kg", current, trendDelta(series), "kg")
                        LineChart(series = series)
                    },
                    {
                        val series = DashboardAggregator.dailySteps(data, zone)
                        val current = series.values.lastOrNull()?.let { "${it.toInt()}" } ?: "no data"
                        ChartHeader("Steps", "count", current, trendDelta(series), "steps/day")
                        BarChart(series = series)
                    },
                    {
                        val (_, avg, _) = DashboardAggregator.dailyHeartRate(data, zone)
                        val current = avg.values.lastOrNull()?.let { "${it.toInt()} bpm" } ?: "no data"
                        ChartHeader("Heart rate (avg)", "bpm", current, trendDelta(avg), "bpm")
                        LineChart(series = avg)
                    },
                    {
                        val series = DashboardAggregator.dailyRestingHeartRate(data, zone)
                        val current = series.values.lastOrNull()?.let { "${it.toInt()} bpm" } ?: "no data"
                        ChartHeader("Resting heart rate", "bpm", current, trendDelta(series), "bpm")
                        LineChart(series = series)
                    },
                    {
                        Text("Sleep stages (min/night)", style = MaterialTheme.typography.titleMedium)
                        StackedBarChart(
                            seriesByStack = DashboardAggregator.nightlySleepStages(data, zone),
                            colors = mapOf(
                                "deep" to Color(0xFF1A237E),
                                "rem" to Color(0xFF4A148C),
                                "core" to Color(0xFF1565C0),
                                "awake" to Color(0xFFB71C1C),
                                "other" to Color(0xFF888888),
                            ),
                        )
                    },
                    {
                        val series = DashboardAggregator.weeklyWorkoutMinutes(data, zone)
                        val current = series.values.lastOrNull()?.let { "${it.toInt()} min this week" } ?: "no data"
                        Text("Workouts (min/week)", style = MaterialTheme.typography.titleMedium)
                        Text(current, style = MaterialTheme.typography.bodyMedium)
                        BarChart(series = series)
                    },
                    {
                        val (protein, fat, carbs) = DashboardAggregator.dailyNutritionMacros(data, zone)
                        Text("Nutrition macros (g/day)", style = MaterialTheme.typography.titleMedium)
                        StackedBarChart(
                            seriesByStack = mapOf("protein" to protein, "fat" to fat, "carbs" to carbs),
                            colors = mapOf(
                                "protein" to Color(0xFF1565C0),
                                "fat" to Color(0xFFE65100),
                                "carbs" to Color(0xFF2E7D32),
                            ),
                        )
                    },
                    {
                        Text("TDEE (kcal/day)", style = MaterialTheme.typography.titleMedium)
                        val tdee = tdeeData
                        val err = tdeeError
                        when {
                            tdee != null && (tdee.calculatedTdee.any { it != null } || tdee.macrofactorTdee.any { it != null }) -> {
                                val calculated = buildTdeeSeries(tdee.dates, tdee.calculatedTdee)
                                val macrofactor = buildTdeeSeries(tdee.dates, tdee.macrofactorTdee)
                                val latestCalculated = tdee.calculatedTdee.lastOrNull { it != null }
                                val latestMacrofactor = tdee.macrofactorTdee.lastOrNull { it != null }
                                Text(
                                    "Calculated: ${latestCalculated?.let { "%.0f".format(it) } ?: "n/a"} · MacroFactor: ${latestMacrofactor?.let { "%.0f".format(it) } ?: "n/a"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                MultiLineChart(
                                    seriesByLine = mapOf("calculated" to calculated, "macrofactor" to macrofactor),
                                    colors = mapOf(
                                        "calculated" to Color(0xFF1565C0),
                                        "macrofactor" to Color(0xFFE65100),
                                    ),
                                )
                            }
                            err != null -> Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            else -> Text("Set your server URL in Settings to see TDEE.", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                )

                if (isWideScreen) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.padding(top = 16.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(charts.size) { index -> charts[index]() }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.padding(top = 16.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(charts.size) { index -> charts[index]() }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            horizontalAlignment = Alignment.End,
        ) {
            digestStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
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
                    // Generic sparkle/asterisk glyph — NOT Anthropic's actual
                    // trademarked Claude logo. Swap for a real asset if you have one.
                    Text("✳️")
                }
            }
        }
    }
}

private fun buildTdeeSeries(dates: List<String>, values: List<Double?>): ChartSeries {
    val pairs = dates.zip(values).filter { it.second != null }
    return ChartSeries(pairs.map { it.first }, pairs.map { it.second!!.toFloat() })
}

@Composable
private fun ChartHeader(title: String, unit: String, currentValue: String, trend: Float?, trendUnit: String) {
    Column {
        Text("$title ($unit)", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(currentValue, style = MaterialTheme.typography.bodyMedium)
            if (trend != null && kotlin.math.abs(trend) > 0.01f) {
                val arrow = if (trend > 0) "▲" else "▼"
                Box(modifier = Modifier.width(6.dp))
                Text(
                    "$arrow ${"%+.1f".format(trend)} $trendUnit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
