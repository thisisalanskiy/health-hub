package com.healthhub.sync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.healthhub.sync.dashboard.DashboardMetric
import com.healthhub.sync.dashboard.MetricSeriesData
import com.healthhub.sync.dashboard.RangeOption
import com.healthhub.sync.dashboard.loadDetailSeriesData
import com.healthhub.sync.dashboard.previewValueText
import com.healthhub.sync.data.AppPreferences
import com.healthhub.sync.data.HealthConnectManager
import kotlinx.coroutines.CancellationException
import java.time.ZoneId

@Composable
fun ChartDetailScreen(
    metric: DashboardMetric,
    healthConnectManager: HealthConnectManager,
    appPreferences: AppPreferences,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedRange by remember { mutableStateOf(RangeOption.MONTH) }
    var data by remember { mutableStateOf<MetricSeriesData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val zone = remember { ZoneId.systemDefault() }

    LaunchedEffect(metric, selectedRange) {
        error = null
        data = try {
            loadDetailSeriesData(metric, selectedRange.days, healthConnectManager, appPreferences, zone)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Couldn't load ${metric.title}: ${e.message ?: e::class.simpleName}"
            null
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row {
            IconButton(onClick = onBack) { Text("←") }
            Text(metric.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 8.dp))
        }

        val currentData = data
        val currentError = error
        when {
            currentError != null -> Text(currentError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
            currentData == null -> Text("Loading…", modifier = Modifier.padding(top = 16.dp))
            else -> {
                val headline = if (metric == DashboardMetric.TDEE) {
                    val multiLine = currentData as? MetricSeriesData.MultiLine
                    val latest = multiLine?.seriesByLine?.get("calculated")?.values?.lastOrNull()
                        ?: multiLine?.seriesByLine?.get("macrofactor")?.values?.lastOrNull()
                    latest?.let { "${it.toInt()} kcal" } ?: "no data"
                } else {
                    previewValueText(metric, currentData, null, null)
                }
                Text(headline, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
                RenderMetricChart(data = currentData, compact = false, modifier = Modifier.padding(top = 16.dp))
            }
        }

        Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RangeOption.values().forEach { option ->
                OutlinedButton(onClick = { selectedRange = option }) {
                    Text(option.label)
                }
            }
        }
    }
}
