package xyz.vmflow.vending.ui.devices

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.take
import xyz.vmflow.vending.bluetooth.BleDevice
import xyz.vmflow.vending.bluetooth.BleManager

/**
 * Dialog for discovering and provisioning unconfigured ESP32 devices.
 *
 * Scans for BLE devices named "0.panamavendingmachines.com" (factory-default) and allows
 * the user to select one for registration. Once selected, the device's
 * MAC address is sent to the backend for registration.
 *
 * ## Flow
 * 1. Dialog opens and starts scanning for unconfigured devices
 * 2. Found devices are displayed in a list
 * 3. User taps a device to register it
 * 4. Backend creates the device record with subdomain and passkey
 * 5. The provisioning commands are sent via BLE (subdomain + passkey)
 *
 * @param isLoading Whether a provisioning operation is in progress.
 * @param onDismiss Callback when the dialog is dismissed.
 * @param onProvision Callback with the selected BLE device for provisioning.
 */
@Composable
fun ProvisionDialog(
    isLoading: Boolean,
    error: String? = null,
    onDismiss: () -> Unit,
    onProvision: (device: BleDevice) -> Unit
) {
    val unconfiguredDevices = remember { mutableStateListOf<BleDevice>() }
    var isScanning by remember { mutableStateOf(true) }

    // Scan for unconfigured devices when the dialog opens
    LaunchedEffect(Unit) {
        try {
            val tempManager = BleManager(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main))
            tempManager.scanForUnconfiguredDevices()
                .take(10) // Limit scan results
                .catch { e ->
                    Log.e("ProvisionDialog", "Scan error: ${e.message}")
                    isScanning = false
                }
                .collect { device ->
                    if (unconfiguredDevices.none { it.address == device.address }) {
                        unconfiguredDevices.add(device)
                    }
                }
        } catch (e: Exception) {
            Log.e("ProvisionDialog", "Scan failed: ${e.message}")
        }
        isScanning = false
    }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Provision Device") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isLoading) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Registering device...")
                } else if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap a device to retry:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn {
                        items(unconfiguredDevices) { device ->
                            Card(
                                onClick = { onProvision(device) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Text(
                                        text = device.name,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = device.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                } else if (isScanning && unconfiguredDevices.isEmpty()) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Scanning for unconfigured devices (0.panamavendingmachines.com)...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else if (unconfiguredDevices.isEmpty()) {
                    Text(
                        text = "No unconfigured devices found. Make sure the device is powered on and in range.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Tap a device to register it:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn {
                        items(unconfiguredDevices) { device ->
                            Card(
                                onClick = { onProvision(device) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Text(
                                        text = device.name,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = device.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text("Cancel")
            }
        }
    )
}
