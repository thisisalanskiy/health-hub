package com.healthhub.sync

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.healthhub.sync.dashboard.DashboardMetric
import com.healthhub.sync.data.AppPreferences
import com.healthhub.sync.data.HealthConnectManager
import com.healthhub.sync.ui.ChartDetailScreen
import com.healthhub.sync.ui.DashboardScreen
import com.healthhub.sync.ui.SettingsScreen
import com.healthhub.sync.ui.theme.HealthHubSyncTheme

private enum class Destination(val route: String, val label: String, val icon: String) {
    DASHBOARD("dashboard", "Dashboard", "📊"),
    SETTINGS("settings", "Settings", "⚙️"),
}

/**
 * The chart detail route template, exactly as registered with [composable] — which is
 * also what `NavDestination.route` reports for it (the pattern, not the instance path
 * with a real metric id substituted), so transition lambdas can compare against it.
 */
private const val CHART_DETAIL_ROUTE = "chart_detail/{metricId}"

/** Material's standard compact/expanded width breakpoint. */
private val WIDE_SCREEN_BREAKPOINT = 600.dp

/** Caps content width so text/lists don't stretch edge-to-edge on a large unfolded screen. */
private val CONTENT_MAX_WIDTH = 640.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
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
                            // Tapping Dashboard while a chart detail screen is open: pop back to
                            // it via the same popBackStack() the explicit back arrow uses, rather
                            // than a fresh navigate() — that reuses the shared-element reverse
                            // morph and reliably lands on Dashboard, instead of an unanimated cut
                            // that could look like the tap did nothing.
                            if (destination == Destination.DASHBOARD && currentRoute?.route == CHART_DETAIL_ROUTE) {
                                navController.popBackStack(Destination.DASHBOARD.route, inclusive = false)
                                return
                            }
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun NavHostContent(
    navController: NavHostController,
    healthConnectManager: HealthConnectManager,
    appPreferences: AppPreferences,
    isWideScreen: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(contentAlignment = Alignment.TopCenter, modifier = modifier) {
        SharedTransitionLayout {
            NavHost(
                navController = navController,
                startDestination = Destination.DASHBOARD.route,
                modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxSize(),
            ) {
                // Dashboard and chart_detail suppress NavHost's default per-destination
                // horizontal slide *only for each other*: it would play at the same time
                // as (and visually fight) the sharedBounds morph between the tapped card
                // and the detail chart. Against any other destination (Settings, via the
                // bottom nav) they keep a real crossfade — suppressing it unconditionally
                // made Dashboard hard-cut while only Settings faded.
                composable(
                    route = Destination.DASHBOARD.route,
                    enterTransition = {
                        if (initialState.destination.route == CHART_DETAIL_ROUTE) EnterTransition.None else fadeIn()
                    },
                    exitTransition = {
                        if (targetState.destination.route == CHART_DETAIL_ROUTE) ExitTransition.None else fadeOut()
                    },
                    popEnterTransition = {
                        if (initialState.destination.route == CHART_DETAIL_ROUTE) EnterTransition.None else fadeIn()
                    },
                    popExitTransition = {
                        if (targetState.destination.route == CHART_DETAIL_ROUTE) ExitTransition.None else fadeOut()
                    },
                ) {
                    DashboardScreen(
                        healthConnectManager = healthConnectManager,
                        appPreferences = appPreferences,
                        isWideScreen = isWideScreen,
                        onMetricClick = { metric -> navController.navigate("chart_detail/${metric.id}") },
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this,
                    )
                }
                composable(
                    route = CHART_DETAIL_ROUTE,
                    arguments = listOf(navArgument("metricId") { type = NavType.StringType }),
                    // Mirror of Dashboard's rule: no transition against Dashboard (the
                    // shared-element morph owns that one), a crossfade against anything
                    // else — reachable because the bottom nav stays visible here, so the
                    // user can jump straight from a chart to Settings.
                    enterTransition = {
                        if (initialState.destination.route == Destination.DASHBOARD.route) EnterTransition.None else fadeIn()
                    },
                    exitTransition = {
                        if (targetState.destination.route == Destination.DASHBOARD.route) ExitTransition.None else fadeOut()
                    },
                    popEnterTransition = {
                        if (initialState.destination.route == Destination.DASHBOARD.route) EnterTransition.None else fadeIn()
                    },
                    popExitTransition = {
                        if (targetState.destination.route == Destination.DASHBOARD.route) ExitTransition.None else fadeOut()
                    },
                ) { backStackEntry ->
                    val metricId = backStackEntry.arguments?.getString("metricId")
                    val metric = DashboardMetric.values().firstOrNull { it.id == metricId }
                    if (metric != null) {
                        ChartDetailScreen(
                            metric = metric,
                            healthConnectManager = healthConnectManager,
                            appPreferences = appPreferences,
                            onBack = { navController.popBackStack() },
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this,
                        )
                    } else {
                        // Unknown metric id (e.g. a restored back stack whose enum id no
                        // longer exists). Rendering nothing would strand the user on a
                        // blank screen with no back affordance, so bounce to the dashboard.
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    }
                }
                // Settings has no shared element, so it keeps a real transition — a
                // plain crossfade.
                composable(
                    Destination.SETTINGS.route,
                    enterTransition = { fadeIn() },
                    exitTransition = { fadeOut() },
                ) {
                    SettingsScreen(appPreferences, healthConnectManager)
                }
            }
        }
    }
}
