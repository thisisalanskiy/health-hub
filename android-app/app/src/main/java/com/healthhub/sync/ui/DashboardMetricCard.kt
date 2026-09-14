package com.healthhub.sync.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.healthhub.sync.dashboard.DashboardMetric
import com.healthhub.sync.dashboard.DashboardRawData
import com.healthhub.sync.dashboard.previewSeriesData
import com.healthhub.sync.dashboard.previewValueText
import com.healthhub.sync.digest.TdeeResponse
import java.time.ZoneId

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun DashboardMetricCard(
    metric: DashboardMetric,
    rawData: DashboardRawData,
    tdeeData: TdeeResponse?,
    tdeeError: String?,
    zone: ZoneId,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
    serverConfigured: Boolean = true,
) {
    val data = previewSeriesData(metric, rawData, zone)
    val currentValue = previewValueText(metric, data, tdeeData, tdeeError, serverConfigured)

    with(sharedTransitionScope) {
        Card(
            onClick = onClick,
            // Key must stay byte-identical to the one in ChartDetailScreen — a
            // mismatch silently disables the morph with no compile error.
            modifier = modifier
                .fillMaxWidth()
                .sharedBounds(
                    rememberSharedContentState(key = "metric-${metric.id}"),
                    animatedVisibilityScope = animatedVisibilityScope,
                ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(metric.title, style = MaterialTheme.typography.titleMedium)
                    // The unit rides up here rather than the title: a bare headline
                    // number like "8342" for Steps reads as unlabelled otherwise.
                    Text("Last 7 days · ${metric.unit}", style = MaterialTheme.typography.bodySmall)
                }
                data?.let { RenderMetricChart(data = it, compact = true) }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(currentValue, style = MaterialTheme.typography.headlineSmall)
                    // Disclosure chevron: the card's own ripple already signals it's
                    // tappable, but this makes "opens something" explicit at a glance.
                    Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
