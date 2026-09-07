package com.healthhub.sync.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.healthhub.sync.data.SyncLogEntry

@Composable
fun SyncStatusLine(log: List<SyncLogEntry>) {
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

fun relativeTime(timestampMillis: Long): String {
    val minutes = (System.currentTimeMillis() - timestampMillis) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1_440 -> "${minutes / 60}h ago"
        else -> "${minutes / 1_440}d ago"
    }
}
