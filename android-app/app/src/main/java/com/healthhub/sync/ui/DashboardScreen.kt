package com.healthhub.sync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.healthhub.sync.dashboard.DashboardAggregator
import com.healthhub.sync.dashboard.DashboardRawData
import com.healthhub.sync.data.AppPreferences
import com.healthhub.sync.data.HealthConnectManager
import com.healthhub.sync.data.SyncLogEntry
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private val RANGE_OPTIONS = listOf(7, 30, 90)

@Composable
fun DashboardScreen(
    healthConnectManager: HealthConnectManager,
    appPreferences: AppPreferences,
    isWideScreen: Boolean,
) {
    var selectedDays by remember { mutableStateOf(30) }
    var rawData by remember { mutableStateOf<DashboardRawData?>(null) }
    var rangeMenuExpanded by remember { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }
    val log by appPreferences.syncLogFlow().collectAsState(initial = emptyList())

    LaunchedEffect(selectedDays) {
        val end = Instant.now()
        val start = end.minus(selectedDays.toLong(), ChronoUnit.DAYS)
        rawData = healthConnectManager.readForDashboard(start, end)
    }

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

        val data = rawData
        if (data == null) {
            Text("Loading from Health Connect…", modifier = Modifier.padding(top = 16.dp))
            return@Column
        }

        // Same four charts (weight/steps/heart-rate/sleep-stages) either way; only the
        // container arranging them changes based on available width.
        val charts: List<@Composable () -> Unit> = listOf(
            {
                Text("Weight", style = MaterialTheme.typography.titleMedium)
                LineChart(series = DashboardAggregator.dailyWeightTrend(data, zone))
            },
            {
                Text("Steps", style = MaterialTheme.typography.titleMedium)
                BarChart(series = DashboardAggregator.dailySteps(data, zone))
            },
            {
                Text("Heart rate (avg)", style = MaterialTheme.typography.titleMedium)
                val (_, avg, _) = DashboardAggregator.dailyHeartRate(data, zone)
                LineChart(series = avg)
            },
            {
                Text("Sleep stages", style = MaterialTheme.typography.titleMedium)
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

@Composable
private fun SyncStatusLine(log: List<SyncLogEntry>) {
    val latest = log.firstOrNull()
    val dotColor: Color
    val label: String
    when {
        latest == null -> {
            dotColor = Color(0xFF9E9E9E)
            label = "Not synced yet"
        }
        latest.success -> {
            dotColor = Color(0xFF4CAF50)
            label = "Synced ${relativeTime(latest.timestampMillis)}"
        }
        else -> {
            dotColor = Color(0xFFE57373)
            label = "Sync failed ${relativeTime(latest.timestampMillis)}"
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("●", color = dotColor, style = MaterialTheme.typography.bodySmall)
        Box(modifier = Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun relativeTime(timestampMillis: Long): String {
    val minutes = (System.currentTimeMillis() - timestampMillis) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1_440 -> "${minutes / 60}h ago"
        else -> "${minutes / 1_440}d ago"
    }
}
