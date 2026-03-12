package xyz.vmflow.setup.ui.setup

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import xyz.vmflow.setup.ble.BleDevice
import xyz.vmflow.setup.ble.BleManager
import xyz.vmflow.setup.data.Device

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    onStartSetup: (macAddress: String) -> Unit,
    onLogout: () -> Unit,
    viewModel: SetupViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showScanDialog by remember { mutableStateOf(false) }
    var permissionsGranted by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (permissionsGranted) showScanDialog = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Devices") },
                actions = {
                    IconButton(onClick = { viewModel.loadDevices() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Default.Logout, "Logout")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    val perms = mutableListOf<String>()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        perms.add(Manifest.permission.BLUETOOTH_SCAN)
                        perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                    perms.add(Manifest.permission.ACCESS_FINE_LOCATION)

                    if (perms.isEmpty()) {
                        showScanDialog = true
                    } else {
                        permissionLauncher.launch(perms.toTypedArray())
                    }
                }
            ) {
                Icon(Icons.Default.Add, "Scan for devices")
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                state.devices.isEmpty() -> {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No devices yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Tap + to scan for ESP32 devices", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.devices) { device ->
                            DeviceCard(device)
                        }
                    }
                }
            }

            state.error?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                )
            }
        }
    }

    if (showScanDialog) {
        ScanDialog(
            onDeviceSelected = { bleDevice ->
                showScanDialog = false
                onStartSetup(bleDevice.address)
            },
            onDismiss = { showScanDialog = false }
        )
    }
}

@Composable
fun DeviceCard(device: Device) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .then(
                        if (device.isOnline)
                            Modifier.clip(CircleShape)
                        else Modifier
                    )
            ) {
                // Status dot
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    drawCircle(if (device.isOnline) Color(0xFF10B981) else Color(0xFF9CA3AF))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("ID: ${device.subdomain}", style = MaterialTheme.typography.titleSmall)
                if (device.macAddress.isNotBlank()) {
                    Text(device.macAddress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(
                if (device.isOnline) Icons.Default.Wifi else Icons.Default.WifiOff,
                contentDescription = if (device.isOnline) "Online" else "Offline",
                tint = if (device.isOnline) Color(0xFF10B981) else Color(0xFF9CA3AF)
            )
        }
    }
}

@Composable
fun ScanDialog(
    onDeviceSelected: (BleDevice) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val foundDevices = remember { mutableStateListOf<BleDevice>() }
    var scanning by remember { mutableStateOf(true) }
    var scanJob by remember { mutableStateOf<Job?>(null) }

    // Auto-start scanning
    if (scanning && scanJob == null) {
        scanJob = scope.launch {
            val bleManager = BleManager(this)
            bleManager.scanForUnconfiguredDevices().collect { device ->
                if (foundDevices.none { it.address == device.address }) {
                    foundDevices.add(device)
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            scanJob?.cancel()
            onDismiss()
        },
        title = { Text("Scanning for ESP32 devices...") },
        text = {
            Column {
                if (foundDevices.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Looking for unconfigured devices...")
                    }
                } else {
                    Text("Found ${foundDevices.size} device(s):", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    foundDevices.forEach { device ->
                        TextButton(onClick = {
                            scanJob?.cancel()
                            onDeviceSelected(device)
                        }) {
                            Text("${device.address}")
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = {
                scanJob?.cancel()
                onDismiss()
            }) {
                Text("Cancel")
            }
        }
    )
}
