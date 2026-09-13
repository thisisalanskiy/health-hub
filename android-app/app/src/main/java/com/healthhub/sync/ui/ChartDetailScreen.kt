package com.healthhub.sync.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChartDetailScreen(
    metric: DashboardMetric,
    healthConnectManager: HealthConnectManager,
    appPreferences: AppPreferences,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    var selectedRange by remember { mutableStateOf(RangeOption.MONTH) }
    var data by remember { mutableStateOf<MetricSeriesData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val zone = remember { ZoneId.systemDefault() }

    LaunchedEffect(metric, selectedRange) {
        error = null
        // Clear first: otherwise the previous range's chart and headline stay on
        // screen for the whole fetch, so a range change looks like nothing happened
        // until the new data lands. Nulling here makes every load show "Loading…",
        // not just the first one (where `data` happened to start null).
        data = null
        data = try {
            loadDetailSeriesData(metric, selectedRange.days, healthConnectManager, appPreferences, zone)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Couldn't load ${metric.title}: ${e.message ?: e::class.simpleName}"
            null
        }
    }

    with(sharedTransitionScope) {
        Column(
            // Key must stay byte-identical to the one in DashboardMetricCard — a
            // mismatch silently disables the morph with no compile error.
            // .sharedBounds must come BEFORE .padding so the shared bounds describe
            // the full-size box, matching DashboardMetricCard where the card's shared
            // bounds cover its whole outer size and the 16.dp is applied inside. With
            // the padding first, the detail side's bounds were the already-inset rect
            // and the two sides morphed between differently-sized boxes.
            modifier = modifier
                .fillMaxSize()
                .sharedBounds(
                    rememberSharedContentState(key = "metric-${metric.id}"),
                    animatedVisibilityScope = animatedVisibilityScope,
                )
                .padding(16.dp),
        ) {
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

            // A plain Row of 6 OutlinedButtons overflowed a 360dp phone (~422dp of
            // buttons against ~328dp of usable width), starving "1Y"/"All" off the
            // edge where they couldn't be tapped. A segmented row divides the
            // available width evenly instead, so every option stays reachable, and
            // its `selected` flag gives the current range a visible selected state
            // the OutlinedButtons never had.
            val options = RangeOption.values()
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == selectedRange,
                        onClick = { selectedRange = option },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        // No leading check icon: with 6 segments on a phone its width
                        // would squeeze the labels themselves out of the segments.
                        icon = {},
                    ) {
                        // labelMedium (12sp) rather than the segment's default
                        // labelLarge (14sp): six segments leave only ~30dp of text
                        // room each inside their content padding on a 360dp phone.
                        Text(option.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }
    }
}
