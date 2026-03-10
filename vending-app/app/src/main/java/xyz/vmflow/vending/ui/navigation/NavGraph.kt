package xyz.vmflow.vending.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import xyz.vmflow.vending.ui.auth.LoginScreen
import xyz.vmflow.vending.ui.auth.RegisterScreen
import xyz.vmflow.vending.ui.devices.DevicesScreen
import xyz.vmflow.vending.ui.sales.SalesScreen
import xyz.vmflow.vending.ui.vending.VendingScreen

/**
 * Navigation route constants for the app.
 *
 * Defines all unique route strings used by the Compose navigation system.
 * Routes are organized by feature area: auth, vending, devices, sales.
 */
object Routes {
    /** Login screen route */
    const val LOGIN = "login"

    /** Registration screen route */
    const val REGISTER = "register"

    /** Main vending flow screen route */
    const val VENDING = "vending"

    /** Device management screen route */
    const val DEVICES = "devices"

    /** Sales history screen route */
    const val SALES = "sales"
}

/**
 * Bottom navigation bar item definition.
 *
 * @property route The navigation route for this destination.
 * @property label The display label shown under the icon.
 * @property icon The Material icon for this tab.
 */
data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

/**
 * The list of bottom navigation bar items.
 *
 * Defines the three main tabs: Vending, Devices, and Sales.
 */
val bottomNavItems = listOf(
    BottomNavItem(
        route = Routes.VENDING,
        label = "Vending",
        icon = Icons.Default.PointOfSale
    ),
    BottomNavItem(
        route = Routes.DEVICES,
        label = "Devices",
        icon = Icons.Default.Devices
    ),
    BottomNavItem(
        route = Routes.SALES,
        label = "Sales",
        icon = Icons.Default.Receipt
    )
)

/**
 * Root navigation graph for the VMflow Vending App.
 *
 * Handles two navigation flows:
 * - **Auth flow**: Login and Register screens (no bottom bar)
 * - **Main flow**: Vending, Devices, and Sales screens (with bottom bar)
 *
 * The start destination is determined by the [isLoggedIn] parameter.
 * After login/registration, the user is navigated to the main flow
 * with the auth screens removed from the back stack.
 *
 * @param isLoggedIn Whether the user is currently authenticated.
 *                   Determines the initial screen (Login vs Vending).
 * @param onLogout Callback invoked when the user requests to log out.
 *                 Should clear auth state and restart the nav graph.
 */
@Composable
fun AppNavGraph(
    isLoggedIn: Boolean,
    onLogout: () -> Unit
) {
    val navController = rememberNavController()
    val startDestination = if (isLoggedIn) Routes.VENDING else Routes.LOGIN

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // ── Auth Flow (no bottom bar) ──────────────────────────────────────────

        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.VENDING) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onNavigateToRegister = {
                    navController.navigate(Routes.REGISTER)
                }
            )
        }

        composable(Routes.REGISTER) {
            RegisterScreen(
                onRegisterSuccess = {
                    navController.navigate(Routes.VENDING) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onNavigateToLogin = {
                    navController.popBackStack()
                }
            )
        }

        // ── Main Flow (with bottom bar) ────────────────────────────────────────

        composable(Routes.VENDING) {
            MainScaffold(navController = navController, currentRoute = Routes.VENDING) {
                VendingScreen()
            }
        }

        composable(Routes.DEVICES) {
            MainScaffold(navController = navController, currentRoute = Routes.DEVICES) {
                DevicesScreen()
            }
        }

        composable(Routes.SALES) {
            MainScaffold(navController = navController, currentRoute = Routes.SALES) {
                SalesScreen(onLogout = {
                    onLogout()
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                })
            }
        }
    }
}

/**
 * Main scaffold with bottom navigation bar.
 *
 * Wraps the main content screens with a [Scaffold] that includes
 * the bottom navigation bar for switching between tabs.
 *
 * @param navController The navigation controller for handling tab switches.
 * @param currentRoute The currently active route (for highlighting the active tab).
 * @param content The screen content to display above the bottom bar.
 */
@Composable
private fun MainScaffold(
    navController: NavHostController,
    currentRoute: String,
    content: @Composable () -> Unit
) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val activeRoute = navBackStackEntry?.destination?.route

                bottomNavItems.forEach { item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        selected = activeRoute == item.route,
                        onClick = {
                            if (activeRoute != item.route) {
                                navController.navigate(item.route) {
                                    // Avoid building up a large back stack
                                    popUpTo(Routes.VENDING) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.padding(innerPadding)
        ) {
            content()
        }
    }
}
