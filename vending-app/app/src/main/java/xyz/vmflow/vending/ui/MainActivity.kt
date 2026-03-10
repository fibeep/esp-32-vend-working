package xyz.vmflow.vending.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import org.koin.android.ext.android.inject
import xyz.vmflow.vending.data.repository.AuthRepository
import xyz.vmflow.vending.ui.navigation.AppNavGraph
import xyz.vmflow.vending.ui.theme.VendingAppTheme

/**
 * The single activity for the VMflow Vending App.
 *
 * Uses the single-activity architecture pattern with Jetpack Compose navigation.
 * All screens are rendered as Composable functions within the navigation graph.
 *
 * ## Responsibilities
 * - Request BLE and location permissions at startup
 * - Determine initial auth state (logged in vs. not)
 * - Set up the Compose content with theming and navigation
 *
 * The activity uses edge-to-edge display for a modern, immersive UI.
 */
class MainActivity : ComponentActivity() {

    /** Auth repository injected via Koin for checking login state */
    private val authRepository: AuthRepository by inject()

    /**
     * Permission request launcher for BLE and location permissions.
     *
     * Handles the result of the runtime permission request dialog.
     * The app can still function without permissions, but BLE scanning
     * will not work until permissions are granted.
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Permissions are handled - BLE operations will check permissions before scanning
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            // User denied some permissions; BLE features will show appropriate error messages
            android.util.Log.w("MainActivity", "Some BLE permissions were denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request BLE permissions at startup
        requestBlePermissions()

        setContent {
            VendingAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavGraph(
                        isLoggedIn = authRepository.isLoggedIn(),
                        onLogout = {
                            authRepository.logout()
                        }
                    )
                }
            }
        }
    }

    /**
     * Requests the necessary BLE and location permissions.
     *
     * On Android 12+ (API 31+), requests BLUETOOTH_SCAN and BLUETOOTH_CONNECT.
     * On older versions, requests BLUETOOTH, BLUETOOTH_ADMIN, and ACCESS_FINE_LOCATION.
     * Also requests ACCESS_FINE_LOCATION for GPS coordinates in vending transactions.
     */
    private fun requestBlePermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ BLE permissions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        // Location permission (needed for BLE on older devices + GPS for sales)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}
