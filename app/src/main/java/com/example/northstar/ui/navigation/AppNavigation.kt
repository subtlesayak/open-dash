package com.example.northstar.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.northstar.ui.NorthstarIcons
import com.example.northstar.ui.screens.*
import com.example.northstar.ui.theme.*
import com.example.northstar.viewmodel.AppViewModel
import com.example.northstar.viewmodel.AuthViewModel
import com.example.northstar.viewmodel.ConnStage
import com.example.northstar.viewmodel.ConnectionState
import com.example.northstar.viewmodel.DashViewModel
import com.example.northstar.viewmodel.RouteViewModel

sealed class Screen(val route: String) {
    object Login    : Screen("login")
    object Home     : Screen("home")
    object Route    : Screen("route")
    object Dash     : Screen("dash")
    object Garage   : Screen("garage")
    object Rides    : Screen("rides")
    object Settings : Screen("settings")
}

private data class NavTab(val screen: Screen, val icon: ImageVector, val label: String)

private val bottomTabs = listOf(
    NavTab(Screen.Home,   NorthstarIcons.Home,    "Home"),
    NavTab(Screen.Route,  NorthstarIcons.Navi,    "Route"),
    NavTab(Screen.Dash,   NorthstarIcons.Dash,    "Dash"),
    NavTab(Screen.Garage, NorthstarIcons.Wrench,  "Garage"),
    NavTab(Screen.Rides,  NorthstarIcons.History, "Rides"),
)

private val bottomRoutes = bottomTabs.map { it.screen.route }

@Composable
fun AppNavigation(
    authViewModel: AuthViewModel = viewModel(),
    appViewModel: AppViewModel = viewModel(),
    dashViewModel: DashViewModel = viewModel(),
    routeViewModel: RouteViewModel = viewModel(),
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomNav = currentRoute in listOf(
        Screen.Home.route, Screen.Route.route, Screen.Dash.route,
        Screen.Garage.route, Screen.Rides.route,
    )

    val garageTab by appViewModel.garageTab.collectAsState()
    val routeState by routeViewModel.state.collectAsState()

    // Single source of truth for connection status: the real dash stage. Fixes Home
    // claiming "Connected" while the Dash screen says it isn't.
    val dashUi by dashViewModel.ui.collectAsState()
    val conn = when (dashUi.stage) {
        ConnStage.STREAMING -> ConnectionState.Connected
        ConnStage.WIFI, ConnStage.AUTH -> ConnectionState.Searching
        else -> ConnectionState.Offline
    }

    // Prefetch map tiles the moment a destination resolves (internet still reachable here)
    LaunchedEffect(routeState.destination?.lat, routeState.destination?.lng) {
        val d = routeState.destination
        if (d?.lat != null && d.lng != null) dashViewModel.prefetchTiles(d.lat, d.lng)
    }

    // Auto-navigate to Route when a Maps share arrives
    LaunchedEffect(routeState.pendingNavigate, currentRoute) {
        if (routeState.pendingNavigate && currentRoute != null && currentRoute != Screen.Login.route) {
            if (currentRoute != Screen.Route.route) {
                navController.navigate(Screen.Route.route) { launchSingleTop = true }
            }
            routeViewModel.onNavigated()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(
            Modifier
                .weight(1f)
                .pointerInput(showBottomNav, currentRoute) {
                    if (!showBottomNav) return@pointerInput
                    var dragX = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragX = 0f },
                        onHorizontalDrag = { _, amount -> dragX += amount },
                        onDragEnd = {
                            val currentIndex = bottomRoutes.indexOf(currentRoute)
                            if (currentIndex == -1 || kotlin.math.abs(dragX) < 80f) return@detectHorizontalDragGestures
                            val nextIndex = if (dragX < 0) currentIndex + 1 else currentIndex - 1
                            val next = bottomTabs.getOrNull(nextIndex)?.screen ?: return@detectHorizontalDragGestures
                            navController.navigate(next.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
        ) {
            NavHost(
                navController = navController,
                startDestination = Screen.Login.route,
            ) {
                composable(Screen.Login.route) {
                    LoginScreen(
                        authViewModel = authViewModel,
                        onSignedIn = {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Login.route) { inclusive = true }
                            }
                        },
                        onSkip = {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Login.route) { inclusive = true }
                            }
                        },
                    )
                }

                composable(Screen.Home.route) {
                    HomeScreen(
                        conn = conn,
                        onNavigate = { dest ->
                            when (dest) {
                                "route" -> navController.navigate(Screen.Route.route)
                                "dash" -> navController.navigate(Screen.Dash.route)
                                "garage" -> navController.navigate(Screen.Garage.route)
                                "settings" -> navController.navigate(Screen.Settings.route)
                            }
                        },
                        routeViewModel = routeViewModel,
                    )
                }

                composable(Screen.Route.route) {
                    RouteScreen(
                        routeViewModel = routeViewModel,
                        onBack = { navController.popBackStack() },
                        onSentToDash = { destName ->
                            dashViewModel.setDestination(
                                name = destName,
                                lat  = routeState.destination?.lat,
                                lng  = routeState.destination?.lng,
                            )
                            // Start navigation: open the dash view and begin the
                            // WiFi → auth → stream flow (no-op if already streaming).
                            dashViewModel.connect()
                            navController.navigate(Screen.Dash.route) {
                                popUpTo(Screen.Home.route)
                            }
                        },
                    )
                }

                composable(Screen.Dash.route) {
                    DashScreen(vm = dashViewModel)
                }

                composable(Screen.Garage.route) {
                    GarageScreen(
                        tab = garageTab,
                        onTabChange = { appViewModel.setGarageTab(it) },
                    )
                }

                composable(Screen.Rides.route) {
                    RidesScreen()
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(
                        conn = conn,
                        onConnChange = { appViewModel.setConn(it) },
                        authViewModel = authViewModel,
                        dashViewModel = dashViewModel,
                        onSignedOut = {
                            navController.navigate(Screen.Login.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        if (showBottomNav) {
            NorthstarBottomNav(
                currentRoute = currentRoute,
                onNavSelect = { screen ->
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }
    }
}

@Composable
private fun NorthstarBottomNav(
    currentRoute: String?,
    onNavSelect: (Screen) -> Unit,
) {
    NavigationBar(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        bottomTabs.forEach { tab ->
            val active = currentRoute == tab.screen.route
            NavigationBarItem(
                selected = active,
                onClick = { onNavSelect(tab.screen) },
                icon = {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        modifier = Modifier.size(23.dp),
                    )
                },
                label = {
                    Text(
                        tab.label,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GeistFamily,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
