package xyz.vmflow.vending.ui.vending

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import xyz.vmflow.vending.bluetooth.BleDevice
import xyz.vmflow.vending.ui.theme.StatusConnecting
import xyz.vmflow.vending.ui.theme.StatusOffline
import xyz.vmflow.vending.ui.theme.StatusOnline

/**
 * Main vending screen composable.
 *
 * Renders different UI layouts based on the current [VendingState]:
 * - Idle: "Scan for Machines" button
 * - Scanning: Progress indicator with scanning animation
 * - DevicesFound: List of discovered BLE devices to select
 * - Connecting: Connection progress indicator
 * - WaitingForSelection: Prompt to select a product on the machine
 * - ProcessingPayment: Payment progress with item details
 * - Dispensing: Dispensing progress animation
 * - Success: Checkmark with success message
 * - Failure: Error icon with failure reason
 * - SessionComplete: Session summary
 * - Error: Error details with retry option
 *
 * @param viewModel The [VendingViewModel] managing the vending state machine.
 */
@Composable
fun VendingScreen(
    viewModel: VendingViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        when (val currentState = state) {
            is VendingState.Idle -> IdleContent(onScan = viewModel::startScan)

            is VendingState.Scanning -> ScanningContent(onStop = viewModel::stopScan)

            is VendingState.DevicesFound -> DevicesFoundContent(
                devices = currentState.devices,
                onDeviceSelected = viewModel::connectToDevice,
                onStopScan = viewModel::stopScan
            )

            is VendingState.Connecting -> ConnectingContent(
                deviceName = currentState.device.name
            )

            is VendingState.WaitingForSelection -> WaitingContent()

            is VendingState.VendRequestReceived -> VendRequestReceivedContent(
                price = currentState.price,
                itemNumber = currentState.itemNumber,
                onSend = viewModel::approveVend,
                onCancel = viewModel::cancelVend
            )

            is VendingState.ProcessingPayment -> ProcessingPaymentContent(
                price = currentState.price,
                itemNumber = currentState.itemNumber
            )

            is VendingState.Dispensing -> DispensingContent()

            is VendingState.Success -> SuccessContent(
                itemNumber = currentState.itemNumber,
                onDone = viewModel::resetToIdle
            )

            is VendingState.Failure -> FailureContent(
                reason = currentState.reason,
                onRetry = viewModel::resetToIdle
            )

            is VendingState.SessionComplete -> SessionCompleteContent(
                onDone = viewModel::resetToIdle
            )

            is VendingState.Error -> ErrorContent(
                message = currentState.message,
                onRetry = viewModel::resetToIdle
            )
        }
    }
}

// ── State-specific composables ─────────────────────────────────────────────────

/** Idle state: Shows a prominent scan button */
@Composable
private fun IdleContent(onScan: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.PointOfSale,
            contentDescription = "Vending",
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Ready to Vend",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Scan for nearby vending machines to start a purchase",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onScan,
            modifier = Modifier.fillMaxWidth(0.7f)
        ) {
            Icon(Icons.Default.BluetoothSearching, contentDescription = null)
            Spacer(modifier = Modifier.size(8.dp))
            Text("Scan for Machines")
        }
    }
}

/** Scanning state: Progress indicator with cancel option */
@Composable
private fun ScanningContent(onStop: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(64.dp),
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Scanning for machines...",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Looking for nearby VMflow devices",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedButton(onClick = onStop) {
            Text("Stop Scanning")
        }
    }
}

/** Devices found: List of selectable BLE devices */
@Composable
private fun DevicesFoundContent(
    devices: List<BleDevice>,
    onDeviceSelected: (BleDevice) -> Unit,
    onStopScan: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Found ${devices.size} Machine${if (devices.size != 1) "s" else ""}",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Tap a machine to start a vending session",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(devices) { device ->
                DeviceCard(device = device, onClick = { onDeviceSelected(device) })
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(onClick = onStopScan) {
            Text("Cancel")
        }
    }
}

/** Card for a single discovered BLE device */
@Composable
private fun DeviceCard(device: BleDevice, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Machine: ${device.subdomain}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Connecting state: Progress indicator with device name */
@Composable
private fun ConnectingContent(deviceName: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(64.dp),
            color = StatusConnecting
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Connecting...",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = deviceName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Waiting for selection: Prompt to use the machine */
@Composable
private fun WaitingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.ShoppingCart,
            contentDescription = "Select product",
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Select a Product",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Choose a product on the vending machine to begin your purchase",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Vend request received: Shows product info and Send/Cancel buttons */
@Composable
private fun VendRequestReceivedContent(
    price: Double,
    itemNumber: Int,
    onSend: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.ShoppingCart,
            contentDescription = "Product selected",
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Product Selected",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Item #$itemNumber",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "$${String.format("%.2f", price)}",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onSend,
            modifier = Modifier.fillMaxWidth(0.7f)
        ) {
            Icon(Icons.Default.Payment, contentDescription = null)
            Spacer(modifier = Modifier.size(8.dp))
            Text("Send Payment")
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(0.7f)
        ) {
            Text("Cancel")
        }
    }
}

/** Processing payment: Shows item details and payment progress */
@Composable
private fun ProcessingPaymentContent(price: Double, itemNumber: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Payment,
            contentDescription = "Processing payment",
            modifier = Modifier.size(64.dp),
            tint = StatusConnecting
        )

        Spacer(modifier = Modifier.height(16.dp))

        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Processing Payment",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Item #${String.format("%03X", itemNumber)}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Dispensing: Animation while machine dispenses */
@Composable
private fun DispensingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.LocalShipping,
            contentDescription = "Dispensing",
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = StatusOnline
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Dispensing Product...",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Please wait while your product is being dispensed",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Success: Checkmark with completion message */
@Composable
private fun SuccessContent(itemNumber: Int, onDone: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "Success",
            modifier = Modifier.size(96.dp),
            tint = StatusOnline
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Product Dispensed!",
            style = MaterialTheme.typography.headlineMedium,
            color = StatusOnline
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Item #${String.format("%03X", itemNumber)} has been dispensed successfully",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onDone) {
            Text("Done")
        }
    }
}

/** Failure: Error icon with reason and retry option */
@Composable
private fun FailureContent(reason: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = "Failure",
            modifier = Modifier.size(96.dp),
            tint = StatusOffline
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Vend Failed",
            style = MaterialTheme.typography.headlineMedium,
            color = StatusOffline
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = reason,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onRetry) {
            Text("Try Again")
        }
    }
}

/** Session complete: Summary with done button */
@Composable
private fun SessionCompleteContent(onDone: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "Complete",
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Session Complete",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "The vending session has ended",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onDone) {
            Text("Start New Session")
        }
    }
}

/** Error: Error details with retry option */
@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = "Error",
            modifier = Modifier.size(96.dp),
            tint = StatusOffline
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Something Went Wrong",
            style = MaterialTheme.typography.headlineMedium,
            color = StatusOffline
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}
