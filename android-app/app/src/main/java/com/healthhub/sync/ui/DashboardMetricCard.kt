package com.healthhub.sync.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(metric.title, style = MaterialTheme.typography.titleMedium)
                // The unit rides on the subtitle line: the compact card shows only the
                // title and a bare headline number, so without this e.g. Steps reads as
                // an unlabelled "8342" (the old dashboard put it in the title instead).
                Text("Last 7 days · ${metric.unit}", style = MaterialTheme.typography.bodySmall)
                data?.let { RenderMetricChart(data = it, compact = true) }
                Text(currentValue, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}
