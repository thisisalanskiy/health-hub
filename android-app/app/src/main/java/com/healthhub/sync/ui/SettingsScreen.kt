package com.healthhub.sync.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.healthhub.sync.data.AppPreferences
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(appPreferences: AppPreferences) {
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        baseUrl = appPreferences.getBaseUrl() ?: ""
        apiKey = appPreferences.getApiKey() ?: ""
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Text(
            "This app reads Weight, Nutrition, Exercise, Heart Rate, Resting " +
                "Heart Rate, Steps, and Sleep from Health Connect, and sends new " +
                "readings to your own health-hub server below. Nothing is shared " +
                "with anyone else.",
            modifier = Modifier.padding(vertical = 8.dp),
        )

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Server base URL (e.g. http://your-tailscale-host:8000)") },
            modifier = Modifier.padding(top = 8.dp),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key") },
            modifier = Modifier.padding(top = 8.dp),
        )

        Button(
            onClick = {
                scope.launch {
                    appPreferences.setBaseUrl(baseUrl.trimEnd('/'))
                    appPreferences.setApiKey(apiKey)
                }
            },
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text("Save")
        }
    }
}
