package xyz.vmflow.setup.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.koin.android.ext.android.inject
import xyz.vmflow.setup.data.AuthRepository
import xyz.vmflow.setup.ui.login.LoginScreen
import xyz.vmflow.setup.ui.setup.DeviceListScreen
import xyz.vmflow.setup.ui.setup.SetupWizardScreen
import xyz.vmflow.setup.ui.theme.SetupTheme

class MainActivity : ComponentActivity() {
    private val authRepository: AuthRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val startDest = if (authRepository.isLoggedIn()) "devices" else "login"

        setContent {
            SetupTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    NavHost(navController = navController, startDestination = startDest) {
                        composable("login") {
                            LoginScreen(
                                onLoginSuccess = {
                                    navController.navigate("devices") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable("devices") {
                            DeviceListScreen(
                                onStartSetup = { macAddress ->
                                    navController.navigate("setup/$macAddress")
                                },
                                onLogout = {
                                    authRepository.logout()
                                    navController.navigate("login") {
                                        popUpTo("devices") { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable("setup/{macAddress}") { backStackEntry ->
                            val macAddress = backStackEntry.arguments?.getString("macAddress") ?: ""
                            SetupWizardScreen(
                                macAddress = macAddress,
                                onDone = {
                                    navController.navigate("devices") {
                                        popUpTo("devices") { inclusive = true }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
