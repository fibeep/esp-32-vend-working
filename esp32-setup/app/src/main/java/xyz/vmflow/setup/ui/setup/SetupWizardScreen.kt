package xyz.vmflow.setup.ui.setup

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import xyz.vmflow.setup.ble.BleDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardScreen(
    macAddress: String,
    onDone: () -> Unit,
    viewModel: SetupViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    // Store the BLE device reference for provisioning
    val bleDevice = remember { mutableMapOf<String, BleDevice>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state.step) {
                            SetupStep.WIFI_CONFIG -> "WiFi Configuration"
                            SetupStep.CREATE_MACHINE -> "Create Machine"
                            SetupStep.PROVISIONING -> "Provisioning..."
                            SetupStep.STATUS -> "Device Status"
                        }
                    )
                },
                navigationIcon = {
                    if (state.step == SetupStep.WIFI_CONFIG) {
                        IconButton(onClick = onDone) {
                            Icon(Icons.Default.ArrowBack, "Back")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Step indicators
            StepIndicator(
                currentStep = state.step,
                steps = listOf(
                    SetupStep.WIFI_CONFIG to "WiFi",
                    SetupStep.CREATE_MACHINE to "Machine",
                    SetupStep.STATUS to "Status"
                )
            )

            Spacer(Modifier.height(8.dp))

            when (state.step) {
                SetupStep.WIFI_CONFIG -> WifiConfigStep(state, viewModel)
                SetupStep.CREATE_MACHINE -> MachineStep(state, viewModel, macAddress, bleDevice, scope)
                SetupStep.PROVISIONING -> ProvisioningStep(state)
                SetupStep.STATUS -> StatusStep(state, onDone)
            }

            state.error?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(
    currentStep: SetupStep,
    steps: List<Pair<SetupStep, String>>
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, (step, label) ->
            val isCurrent = currentStep == step ||
                (currentStep == SetupStep.PROVISIONING && step == SetupStep.CREATE_MACHINE)
            val isDone = steps.indexOfFirst { it.first == currentStep } >
                steps.indexOfFirst { it.first == step } ||
                (currentStep == SetupStep.STATUS && step != SetupStep.STATUS)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isDone) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(32.dp)
                        )
                    } else {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCurrent || isDone) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WifiConfigStep(state: SetupUiState, viewModel: SetupViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Enter your WiFi credentials",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            "The ESP32 will use these credentials to connect to the internet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = state.wifiSsid,
            onValueChange = { viewModel.updateWifiSsid(it) },
            label = { Text("WiFi SSID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.wifiPassword,
            onValueChange = { viewModel.updateWifiPassword(it) },
            label = { Text("WiFi Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { viewModel.goToMachineStep() },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.wifiSsid.isNotBlank() && state.wifiPassword.isNotBlank()
        ) {
            Text("Next")
        }
    }
}

@Composable
private fun MachineStep(
    state: SetupUiState,
    viewModel: SetupViewModel,
    macAddress: String,
    bleDeviceMap: MutableMap<String, BleDevice>,
    scope: kotlinx.coroutines.CoroutineScope
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Create a machine for this device",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            "MAC Address: $macAddress",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = state.machineName,
            onValueChange = { viewModel.updateMachineName(it) },
            label = { Text("Machine Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.machineLocation,
            onValueChange = { viewModel.updateMachineLocation(it) },
            label = { Text("Location (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                viewModel.startProvisioning(macAddress, bleDeviceMap[macAddress], scope)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.machineName.isNotBlank() && !state.isLoading
        ) {
            Text("Provision Device")
        }
    }
}

@Composable
private fun ProvisioningStep(state: SetupUiState) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(32.dp))
        CircularProgressIndicator()
        Text(
            state.provisioningStatus,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(16.dp))

        ProvisionCheckItem("Device registered", state.deviceRegistered)
        ProvisionCheckItem("Machine created", state.machineCreated)
        ProvisionCheckItem("Device linked to machine", state.machineLinked)
        ProvisionCheckItem("BLE config sent", state.bleConfigSent)
    }
}

@Composable
private fun ProvisionCheckItem(label: String, completed: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (completed) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF10B981),
                modifier = Modifier.size(20.dp)
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusStep(state: SetupUiState, onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Device Status",
            style = MaterialTheme.typography.titleMedium
        )

        state.device?.let { device ->
            Text(
                "Subdomain: ${device.subdomain}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        state.machine?.let { machine ->
            Text(
                "Machine: ${machine.name ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))

        StatusCard(
            icon = Icons.Default.CheckCircle,
            label = "Device Registered",
            status = state.deviceRegistered,
            statusText = if (state.deviceRegistered) "Registered" else "Pending"
        )

        StatusCard(
            icon = Icons.Default.Link,
            label = "Machine Linked",
            status = state.machineLinked,
            statusText = if (state.machineLinked) "Linked" else "Pending"
        )

        StatusCard(
            icon = Icons.Default.Wifi,
            label = "BLE Configuration",
            status = state.bleConfigSent,
            statusText = if (state.bleConfigSent) "Sent" else "Pending"
        )

        StatusCard(
            icon = Icons.Default.Cloud,
            label = "Device Online",
            status = state.deviceOnline,
            statusText = when {
                state.deviceOnline -> "Online"
                state.isLoading -> "Waiting..."
                else -> "Offline"
            },
            showProgress = state.isLoading && !state.deviceOnline
        )

        if (state.isLoading && !state.deviceOnline) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                state.provisioningStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.deviceOnline) "Done" else "Close")
        }
    }
}

@Composable
private fun StatusCard(
    icon: ImageVector,
    label: String,
    status: Boolean,
    statusText: String,
    showProgress: Boolean = false
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (status) Color(0xFF10B981) else Color(0xFF9CA3AF),
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            if (showProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (status) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
