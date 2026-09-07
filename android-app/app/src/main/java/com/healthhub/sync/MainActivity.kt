package com.healthhub.sync

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.healthhub.sync.data.AppPreferences
import com.healthhub.sync.data.HealthConnectManager
import com.healthhub.sync.ui.DashboardScreen
import com.healthhub.sync.ui.SettingsScreen
import com.healthhub.sync.ui.theme.HealthHubSyncTheme

private enum class Destination(val route: String, val label: String, val icon: String) {
    DASHBOARD("dashboard", "Dashboard", "📊"),
    SETTINGS("settings", "Settings", "⚙️"),
}

/** Material's standard compact/expanded width breakpoint. */
private val WIDE_SCREEN_BREAKPOINT = 600.dp

/** Caps content width so text/lists don't stretch edge-to-edge on a large unfolded screen. */
private val CONTENT_MAX_WIDTH = 640.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Dark-only app: force light (white) status/nav bar icons regardless of the
        // system's own light/dark setting, so they stay legible against our always-dark
        // background rather than merging into it.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )

        val healthConnectManager = HealthConnectManager(applicationContext)
        val appPreferences = AppPreferences(applicationContext)

        setContent {
            HealthHubSyncTheme {
                Surface {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val isWideScreen = maxWidth >= WIDE_SCREEN_BREAKPOINT
                        val navController = rememberNavController()
                        val backStackEntry by navController.currentBackStackEntryAsState()
                        val currentRoute = backStackEntry?.destination

                        fun isSelected(destination: Destination) =
                            currentRoute?.hierarchy?.any { it.route == destination.route } == true

                        fun navigateTo(destination: Destination) {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }

                        if (isWideScreen) {
                            Row(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                                NavigationRail {
                                    Destination.values().forEach { destination ->
                                        NavigationRailItem(
                                            selected = isSelected(destination),
                                            onClick = { navigateTo(destination) },
                                            icon = { Text(destination.icon) },
                                            label = { Text(destination.label) },
                                        )
                                    }
                                }
                                NavHostContent(
                                    navController = navController,
                                    healthConnectManager = healthConnectManager,
                                    appPreferences = appPreferences,
                                    isWideScreen = isWideScreen,
                                    modifier = Modifier.weight(1f).fillMaxSize(),
                                )
                            }
                        } else {
                            Scaffold(
                                bottomBar = {
                                    NavigationBar {
                                        Destination.values().forEach { destination ->
                                            NavigationBarItem(
                                                selected = isSelected(destination),
                                                onClick = { navigateTo(destination) },
                                                icon = { Text(destination.icon) },
                                                label = { Text(destination.label) },
                                            )
                                        }
                                    }
                                },
                            ) { padding ->
                                NavHostContent(
                                    navController = navController,
                                    healthConnectManager = healthConnectManager,
                                    appPreferences = appPreferences,
                                    isWideScreen = isWideScreen,
                                    modifier = Modifier.padding(padding).fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavHostContent(
    navController: NavHostController,
    healthConnectManager: HealthConnectManager,
    appPreferences: AppPreferences,
    isWideScreen: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(contentAlignment = Alignment.TopCenter, modifier = modifier) {
        NavHost(
            navController = navController,
            startDestination = Destination.DASHBOARD.route,
            modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxSize(),
        ) {
            composable(Destination.DASHBOARD.route) {
                DashboardScreen(healthConnectManager, appPreferences, isWideScreen)
            }
            composable(Destination.SETTINGS.route) {
                SettingsScreen(appPreferences, healthConnectManager)
            }
        }
    }
}
