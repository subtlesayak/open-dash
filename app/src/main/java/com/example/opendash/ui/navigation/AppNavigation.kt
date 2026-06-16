package com.example.opendash.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.screens.*
import com.example.opendash.ui.theme.*
import com.example.opendash.viewmodel.AppViewModel
import com.example.opendash.viewmodel.AuthViewModel
import com.example.opendash.viewmodel.ConnStage
import com.example.opendash.viewmodel.ConnectionState
import com.example.opendash.viewmodel.DashViewModel
import com.example.opendash.viewmodel.RouteViewModel

sealed class Screen(val route: String) {
    object Login    : Screen("login")
    object Home     : Screen("home")
    object Vehicles : Screen("vehicles")
    object Expenses : Screen("expenses")
    object Route    : Screen("route")
    object Dash     : Screen("dash")
    object Garage   : Screen("garage")
    object Rides    : Screen("rides")
    object Settings : Screen("settings")
}

private data class NavTab(val screen: Screen, val icon: ImageVector, val label: String)

private val bottomTabs = listOf(
    NavTab(Screen.Home,   OpenDashIcons.Home,    "Home"),
    NavTab(Screen.Vehicles, OpenDashIcons.Motor, "Vehicles"),
    NavTab(Screen.Expenses, OpenDashIcons.Chart, "Expenses"),
    NavTab(Screen.Garage, OpenDashIcons.Wrench,  "Garage"),
    NavTab(Screen.Settings, OpenDashIcons.Gear, "More"),
)

private val bottomRoutes = bottomTabs.map { it.screen.route }
private val homeChildRoutes = listOf(Screen.Route.route, Screen.Dash.route, Screen.Rides.route)
private val shellRoutes = bottomRoutes + homeChildRoutes

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

    val showBottomNav = currentRoute in shellRoutes
    val canSwipeBottomTabs = currentRoute in bottomRoutes

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

    fun navigateHome() {
        navController.navigate(Screen.Home.route) {
            popUpTo(Screen.Home.route) {
                inclusive = false
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun navigateTopLevel(screen: Screen) {
        if (screen.route == Screen.Home.route) {
            navigateHome()
            return
        }
        navController.navigate(screen.route) {
            popUpTo(Screen.Home.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    BackHandler(enabled = showBottomNav && currentRoute != Screen.Home.route) {
        navigateHome()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(
            Modifier
                .weight(1f)
                .pointerInput(canSwipeBottomTabs, currentRoute) {
                    if (!canSwipeBottomTabs) return@pointerInput
                    var dragX = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragX = 0f },
                        onHorizontalDrag = { _, amount -> dragX += amount },
                        onDragEnd = {
                            val currentIndex = bottomRoutes.indexOf(currentRoute)
                            if (currentIndex == -1 || kotlin.math.abs(dragX) < 80f) return@detectHorizontalDragGestures
                            val nextIndex = if (dragX < 0) currentIndex + 1 else currentIndex - 1
                            val next = bottomTabs.getOrNull(nextIndex)?.screen ?: return@detectHorizontalDragGestures
                            navigateTopLevel(next)
                        },
                    )
                }
        ) {
            NavHost(
                navController = navController,
                startDestination = Screen.Login.route,
                enterTransition = {
                    val direction = transitionDirection(initialState.destination.route, targetState.destination.route)
                    slideInHorizontally(tween(260)) { width -> width * direction }
                },
                exitTransition = {
                    val direction = transitionDirection(initialState.destination.route, targetState.destination.route)
                    slideOutHorizontally(tween(260)) { width -> -width * direction }
                },
                popEnterTransition = {
                    val direction = transitionDirection(targetState.destination.route, initialState.destination.route)
                    slideInHorizontally(tween(260)) { width -> -width * direction }
                },
                popExitTransition = {
                    val direction = transitionDirection(targetState.destination.route, initialState.destination.route)
                    slideOutHorizontally(tween(260)) { width -> width * direction }
                },
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
                                "rides" -> navController.navigate(Screen.Rides.route)
                                "garage" -> navController.navigate(Screen.Garage.route)
                                "settings" -> navController.navigate(Screen.Settings.route)
                            }
                        },
                        routeViewModel = routeViewModel,
                    )
                }

                composable(Screen.Vehicles.route) {
                    VehiclesScreen()
                }

                composable(Screen.Expenses.route) {
                    ExpensesScreen()
                }

                composable(Screen.Route.route) {
                    RouteScreen(
                        routeViewModel = routeViewModel,
                        onBack = { navigateHome() },
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
                        onBack = { navController.navigate(Screen.Home.route) { launchSingleTop = true } },
                    )
                }
            }
        }

        if (showBottomNav) {
            OpenDashBottomNav(
                currentRoute = activeBottomRoute(currentRoute),
                onNavSelect = { screen -> navigateTopLevel(screen) },
            )
        }
    }
}

private fun activeBottomRoute(route: String?): String? =
    if (route in homeChildRoutes) Screen.Home.route else route

private fun transitionDirection(fromRoute: String?, toRoute: String?): Int =
    if (navOrderIndex(toRoute) >= navOrderIndex(fromRoute)) 1 else -1

private fun navOrderIndex(route: String?): Int = when (route) {
    Screen.Home.route -> 0
    Screen.Route.route -> 1
    Screen.Dash.route -> 2
    Screen.Rides.route -> 3
    Screen.Vehicles.route -> 10
    Screen.Expenses.route -> 20
    Screen.Garage.route -> 30
    Screen.Settings.route -> 40
    else -> 0
}

@Composable
private fun OpenDashBottomNav(
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
