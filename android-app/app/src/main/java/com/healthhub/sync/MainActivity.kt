package com.healthhub.sync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.healthhub.sync.data.AppPreferences
import com.healthhub.sync.data.HealthConnectManager
import com.healthhub.sync.ui.DashboardScreen
import com.healthhub.sync.ui.HomeScreen
import com.healthhub.sync.ui.SettingsScreen

private enum class Destination(val route: String, val label: String) {
    HOME("home", "Home"),
    DASHBOARD("dashboard", "Dashboard"),
    SETTINGS("settings", "Settings"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val healthConnectManager = HealthConnectManager(applicationContext)
        val appPreferences = AppPreferences(applicationContext)

        setContent {
            MaterialTheme {
                Surface {
                    val navController = rememberNavController()
                    Scaffold(
                        bottomBar = {
                            NavigationBar {
                                val backStackEntry by navController.currentBackStackEntryAsState()
                                val currentRoute = backStackEntry?.destination
                                Destination.values().forEach { destination ->
                                    NavigationBarItem(
                                        selected = currentRoute?.hierarchy?.any { it.route == destination.route } == true,
                                        onClick = {
                                            navController.navigate(destination.route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = {},
                                        label = { Text(destination.label) },
                                    )
                                }
                            }
                        },
                    ) { padding ->
                        NavHost(
                            navController = navController,
                            startDestination = Destination.HOME.route,
                            modifier = androidx.compose.ui.Modifier.padding(padding),
                        ) {
                            composable(Destination.HOME.route) {
                                HomeScreen(healthConnectManager, appPreferences)
                            }
                            composable(Destination.DASHBOARD.route) {
                                DashboardScreen(healthConnectManager)
                            }
                            composable(Destination.SETTINGS.route) {
                                SettingsScreen(appPreferences)
                            }
                        }
                    }
                }
            }
        }
    }
}
