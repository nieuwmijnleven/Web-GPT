package com.shortsmonitor.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.shortsmonitor.app.R
import com.shortsmonitor.app.ShortsMonitorApplication
import com.shortsmonitor.core.design.components.LoadingState
import com.shortsmonitor.core.design.components.ShortsMonitorBottomBar
import com.shortsmonitor.core.design.components.ShortsMonitorBottomBarItem
import com.shortsmonitor.core.design.components.ShortsMonitorTopBar
import com.shortsmonitor.feature.events.EventsScreen
import com.shortsmonitor.feature.observation.ObservationScreen
import com.shortsmonitor.feature.observation.ObservingScreen
import com.shortsmonitor.feature.onboarding.OnboardingScreen
import com.shortsmonitor.feature.profiles.ProfilesScreen
import com.shortsmonitor.feature.sessions.SessionsScreen
import com.shortsmonitor.feature.settings.SettingsScreen
import kotlinx.coroutines.launch

/** 쇼츠 관찰 중 화면 라우트. 세션 식별자를 인자로 받는다. 관찰 중에는 하단 내비게이션을 숨긴다. */
private const val OBSERVE_ROUTE = "observe/{sessionId}"

/**
 * 하단 내비게이션 5개 기본 경로.
 * 관찰 / 기록 / 이벤트 / 프로필 / 설정
 */
enum class ShortsMonitorDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Observation(
        route = "observation",
        labelRes = R.string.nav_observation,
        icon = Icons.Outlined.PlayArrow,
        selectedIcon = Icons.Filled.PlayArrow,
    ),
    Sessions(
        route = "sessions",
        labelRes = R.string.nav_sessions,
        icon = Icons.Outlined.DateRange,
        selectedIcon = Icons.Filled.DateRange,
    ),
    Events(
        route = "events",
        labelRes = R.string.nav_events,
        icon = Icons.Outlined.Warning,
        selectedIcon = Icons.Filled.Warning,
    ),
    Profiles(
        route = "profiles",
        labelRes = R.string.nav_profiles,
        icon = Icons.Outlined.Person,
        selectedIcon = Icons.Filled.Person,
    ),
    Settings(
        route = "settings",
        labelRes = R.string.nav_settings,
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Filled.Settings,
    ),
}

@Composable
fun ShortsMonitorApp() {
    val context = LocalContext.current
    val settingsRepository = remember {
        (context.applicationContext as ShortsMonitorApplication).settingsRepository
    }
    val scope = rememberCoroutineScope()
    val onboardingCompleted by settingsRepository.onboardingCompleted
        .collectAsStateWithLifecycle(initialValue = null)

    when (onboardingCompleted) {
        null -> LoadingState(modifier = Modifier.fillMaxSize())
        false -> OnboardingScreen(
            onStart = { scope.launch { settingsRepository.setOnboardingCompleted(true) } },
        )
        true -> ShortsMonitorMainApp()
    }
}

@Composable
private fun ShortsMonitorMainApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    Scaffold(
        topBar = {
            ShortsMonitorTopBar(title = stringResource(R.string.app_name))
        },
        bottomBar = {
            if (currentRoute != OBSERVE_ROUTE) {
                ShortsMonitorBottomBar(
                    items = ShortsMonitorDestination.entries.map { destination ->
                        ShortsMonitorBottomBarItem(
                            route = destination.route,
                            labelRes = destination.labelRes,
                            icon = destination.icon,
                            selectedIcon = destination.selectedIcon,
                        )
                    },
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = ShortsMonitorDestination.Observation.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(ShortsMonitorDestination.Observation.route) {
                ObservationScreen(
                    onNavigateToSessions = {
                        navController.navigate(ShortsMonitorDestination.Sessions.route) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToEvents = {
                        navController.navigate(ShortsMonitorDestination.Events.route) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToProfiles = {
                        navController.navigate(ShortsMonitorDestination.Profiles.route) {
                            launchSingleTop = true
                        }
                    },
                    onStartObservation = { sessionId ->
                        navController.navigate("observe/$sessionId") { launchSingleTop = true }
                    },
                    onResumeObservation = { sessionId ->
                        navController.navigate("observe/$sessionId") { launchSingleTop = true }
                    },
                )
            }
            composable(
                route = OBSERVE_ROUTE,
                arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
            ) { entry ->
                val sessionId = entry.arguments?.getLong("sessionId") ?: return@composable
                ObservingScreen(
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                    onOpenEvent = {
                        // 의심 이벤트 상세는 K단계에서 구현한다. 지금은 이벤트 목록으로 이동한다.
                        navController.navigate(ShortsMonitorDestination.Events.route) {
                            launchSingleTop = true
                        }
                    },
                    onOpenLog = {
                        navController.navigate(ShortsMonitorDestination.Sessions.route) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(ShortsMonitorDestination.Sessions.route) { SessionsScreen() }
            composable(ShortsMonitorDestination.Events.route) { EventsScreen() }
            composable(ShortsMonitorDestination.Profiles.route) { ProfilesScreen() }
            composable(ShortsMonitorDestination.Settings.route) { SettingsScreen() }
        }
    }
}
