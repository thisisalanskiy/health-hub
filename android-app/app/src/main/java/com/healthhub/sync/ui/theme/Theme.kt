package com.healthhub.sync.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Health Hub is dark-only by design (user preference) — it does not
// follow the system light/dark setting.
private val HealthHubDarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF00325A),
    secondary = Color(0xFFB0C6DC),
    background = Color(0xFF121317),
    surface = Color(0xFF1B1C20),
    surfaceVariant = Color(0xFF26272C),
    onBackground = Color(0xFFE3E2E6),
    onSurface = Color(0xFFE3E2E6),
    onSurfaceVariant = Color(0xFFC4C6CF),
)

@Composable
fun HealthHubSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HealthHubDarkColors,
        content = content,
    )
}
