package xyz.vmflow.vending.ui.vending

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import xyz.vmflow.vending.bluetooth.BleConstants
import xyz.vmflow.vending.bluetooth.BleDevice
import xyz.vmflow.vending.bluetooth.BleManager
import xyz.vmflow.vending.bluetooth.XorCrypto
import xyz.vmflow.vending.data.repository.DeviceRepository
import xyz.vmflow.vending.data.repository.VendingRepository
import xyz.vmflow.vending.data.repository.YappyRepository
import xyz.vmflow.vending.domain.model.VendRequest
import xyz.vmflow.vending.domain.payment.PaymentProvider
import xyz.vmflow.vending.domain.payment.PaymentResult

/**
 * Sealed class representing all possible states of the vending flow.
 *
 * The vending process follows a strict state machine that ensures
 * the user experience is predictable and error states are handled gracefully.
 *
 * ## State Flow
 * ```
 * Idle -> Scanning -> DevicesFound -> Connecting -> WaitingForSelection
 *   -> ProcessingPayment -> Dispensing -> Success/Failure -> SessionComplete
 * ```
 *
 * Any state can transition to [Error] on unexpected failures.
 */
sealed class VendingState {
    /** Initial state. No BLE activity. Ready to scan. */
    data object Idle : VendingState()

    /** Actively scanning for nearby VMflow BLE devices. */
    data object Scanning : VendingState()

    /** One or more devices were found during scanning. */
    data class DevicesFound(val devices: List<BleDevice>) : VendingState()

    /** Connecting to a selected BLE device. */
    data class Connecting(val device: BleDevice) : VendingState()

    /** Connected and session started. Waiting for user to select a product on the machine. */
    data object WaitingForSelection : VendingState()

    /** Vend request received. Showing product info, waiting for user to approve. */
    data class VendRequestReceived(val price: Double, val itemNumber: Int) : VendingState()

    /** User approved the vend. Processing payment for the selected item. */
    data class ProcessingPayment(val price: Double, val itemNumber: Int) : VendingState()

    /** Yappy QR code displayed. Waiting for customer to scan and pay. */
    data class YappyPayment(
        val price: Double,
        val itemNumber: Int,
        val qrHash: String,
        val transactionId: String,
        val statusMessage: String = "Waiting for payment...",
        val mdbSessionTimedOut: Boolean = false
    ) : VendingState()

    /** Payment approved. Waiting for the machine to dispense the product. */
    data object Dispensing : VendingState()

    /** Payment succeeded. Product was dispensed or credit was pushed to machine. */
    data class Success(
        val itemNumber: Int,
        val message: String = "Product Dispensed!",
        val subtitle: String = ""
    ) : VendingState()

    /** Dispensing failed. The product was not dispensed. */
    data class Failure(val reason: String) : VendingState()

    /** Session has completed. The machine ended the vending session. */
    data object SessionComplete : VendingState()

    /** An error occurred. Includes a descriptive message. */
    data class Error(val message: String) : VendingState()
}

/**
 * ViewModel for the core vending flow.
 *
 * Implements the full vending state machine:
 * 1. **Scan** for nearby VMflow BLE devices
 * 2. **Connect** to a selected device
 * 3. **Start session** via BLE write (0x02)
 * 4. **Wait** for the user to select a product on the machine
 * 5. **Receive** vend request notification (0x0A) with price and item number
 * 6. **Process payment** via the pluggable [PaymentProvider]
 * 7. **Request credit** from the backend API
 * 8. **Approve vend** by writing the backend's response to BLE
 * 9. **Monitor** for success (0x0B), failure (0x0C), or session complete (0x0D)
 * 10. **Disconnect** when the session ends
 *
 * @property vendingRepository Repository for backend credit request API calls.
 * @property paymentProvider Pluggable payment provider (mock for development).
 */
class VendingViewModel(
    private val vendingRepository: VendingRepository,
    private val deviceRepository: DeviceRepository,
    private val paymentProvider: PaymentProvider,
    private val yappyRepository: YappyRepository
) : ViewModel() {

    companion object {
        private const val TAG = "VendingViewModel"
    }

    private val _state = MutableStateFlow<VendingState>(VendingState.Idle)

    /** Observable vending state for the UI. */
    val state: StateFlow<VendingState> = _state.asStateFlow()

    /** BLE manager for device communication. Created per vending session. */
    private var bleManager: BleManager? = null

    /** Job reference for the BLE scan coroutine (for cancellation). */
    private var scanJob: Job? = null

    /** Job reference for the notification observer coroutine. */
    private var notificationJob: Job? = null

    /** List of discovered devices during scanning. */
    private val discoveredDevices = mutableListOf<BleDevice>()

    /** The currently connected device (for subdomain extraction). */
    private var connectedDevice: BleDevice? = null

    /** The raw payload from the last vend request notification (for forwarding to backend). */
    private var lastVendRequestPayload: ByteArray? = null

    /** The device passkey for local XOR decryption of vend requests. */
    private var devicePasskey: String? = null

    /** Job reference for the Yappy payment status polling coroutine. */
    private var yappyPollingJob: Job? = null

    /**
     * Starts scanning for nearby VMflow BLE devices.
     *
     * Transitions from [VendingState.Idle] to [VendingState.Scanning].
     * Discovered devices are deduplicated by MAC address and accumulated
     * in [VendingState.DevicesFound] as they are found.
     *
     * The scan runs until [stopScan] is called or the ViewModel is cleared.
     */
    fun startScan() {
        if (_state.value !is VendingState.Idle && _state.value !is VendingState.Error
            && _state.value !is VendingState.SessionComplete
        ) {
            return // Already in an active flow
        }

        _state.value = VendingState.Scanning
        discoveredDevices.clear()

        bleManager = BleManager(viewModelScope)

        scanJob = viewModelScope.launch {
            try {
                bleManager?.scanForDevices()
                    ?.catch { e ->
                        Log.e(TAG, "Scan error: ${e.message}")
                        _state.value = VendingState.Error("Scan failed: ${e.message}")
                    }
                    ?.collect { device ->
                        // Deduplicate by MAC address
                        if (discoveredDevices.none { it.address == device.address }) {
                            discoveredDevices.add(device)
                            _state.value = VendingState.DevicesFound(discoveredDevices.toList())
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Scan error: ${e.message}")
                _state.value = VendingState.Error("Scan failed: ${e.message}")
            }
        }
    }

    /**
     * Stops the active BLE scan.
     *
     * If devices were found, stays in [VendingState.DevicesFound].
     * If no devices were found, transitions back to [VendingState.Idle].
     */
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null

        if (discoveredDevices.isEmpty()) {
            _state.value = VendingState.Idle
        }
    }

    /**
     * Connects to a selected BLE device and starts a vending session.
     *
     * Transitions through [VendingState.Connecting] -> [VendingState.WaitingForSelection].
     * After connecting, sends the BEGIN_SESSION command (0x02) and starts
     * observing BLE notifications for vend events.
     *
     * @param device The [BleDevice] selected by the user from the scan results.
     */
    fun connectToDevice(device: BleDevice) {
        stopScan() // Stop scanning before connecting

        _state.value = VendingState.Connecting(device)
        connectedDevice = device

        viewModelScope.launch {
            try {
                // Fetch device passkey from backend for local decryption
                Log.d(TAG, "Fetching passkey for subdomain: ${device.subdomain}")
                val devicesResult = deviceRepository.getDevices()
                devicesResult.fold(
                    onSuccess = { devices ->
                        val backendDevice = devices.find { it.subdomain == device.subdomain }
                        if (backendDevice != null) {
                            devicePasskey = backendDevice.passkey
                            Log.d(TAG, "Passkey found for device ${device.subdomain}")
                        } else {
                            Log.w(TAG, "Device ${device.subdomain} not found in backend, will skip local decryption")
                        }
                    },
                    onFailure = { e ->
                        Log.w(TAG, "Failed to fetch passkey: ${e.message}, will skip local decryption")
                    }
                )

                bleManager?.connect(device)
                bleManager?.startSession()
                _state.value = VendingState.WaitingForSelection

                // Start observing notifications
                observeNotifications()
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}")
                _state.value = VendingState.Error("Connection failed: ${e.message}")
                connectedDevice = null
                devicePasskey = null
            }
        }
    }

    /**
     * Observes BLE notifications from the connected device.
     *
     * Listens for the four notification event types:
     * - **0x0A (VEND_REQUEST)**: Product selected, payment needed
     * - **0x0B (VEND_SUCCESS)**: Product dispensed successfully
     * - **0x0C (VEND_FAILURE)**: Dispensing failed
     * - **0x0D (SESSION_COMPLETE)**: Session ended by machine
     *
     * Each event triggers the appropriate state transition and action.
     */
    private fun observeNotifications() {
        notificationJob = viewModelScope.launch {
            try {
                bleManager?.observeNotifications()
                    ?.catch { e ->
                        Log.e(TAG, "Notification error: ${e.message}")
                        val currentState = _state.value
                        if (currentState is VendingState.YappyPayment) {
                            // BLE disconnected during Yappy payment — keep polling alive.
                            // The sale will be recorded by the Edge Function and credit
                            // pushed to ESP32 via MQTT regardless of BLE state.
                            Log.w(TAG, "BLE connection lost during Yappy payment — keeping Yappy poll alive")
                            _state.value = currentState.copy(
                                statusMessage = "BLE disconnected. Waiting for Yappy payment...",
                                mdbSessionTimedOut = true
                            )
                        } else {
                            _state.value = VendingState.Error("Connection lost: ${e.message}")
                        }
                    }
                    ?.collect { payload ->
                        handleNotification(payload)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Notification observer error: ${e.message}")
            }
        }
    }

    /**
     * Routes a BLE notification payload to the appropriate handler.
     *
     * @param payload The raw notification bytes (19-byte XOR-encrypted payload).
     */
    private suspend fun handleNotification(payload: ByteArray) {
        if (payload.isEmpty()) return

        val eventType = payload[0]
        Log.d(TAG, "Received notification: 0x${String.format("%02X", eventType)}")

        when (eventType) {
            BleConstants.EVT_VEND_REQUEST -> handleVendRequest(payload)
            BleConstants.EVT_VEND_SUCCESS -> handleVendSuccess(payload)
            BleConstants.EVT_VEND_FAILURE -> handleVendFailure()
            BleConstants.EVT_SESSION_COMPLETE -> handleSessionComplete()
        }
    }

    /**
     * Handles a vend request notification (0x0A).
     *
     * This is the critical path:
     * 1. Save the raw payload for forwarding to the backend
     * 2. Update state to ProcessingPayment (shows price to user)
     * 3. Process payment via the PaymentProvider
     * 4. On payment success, request credit from the backend
     * 5. Write the approval payload to BLE
     * 6. Transition to Dispensing state
     *
     * @param payload The raw 19-byte XOR-encrypted vend request payload.
     */
    private suspend fun handleVendRequest(payload: ByteArray) {
        // Store raw payload for forwarding to the backend
        lastVendRequestPayload = payload.copyOf()

        // Decrypt payload locally to show price and item number to the user
        val passkey = devicePasskey
        if (passkey != null && payload.size == BleConstants.PAYLOAD_SIZE) {
            val vendRequest = XorCrypto.decode(payload.copyOf(), passkey)
            if (vendRequest != null) {
                Log.d(TAG, "Vend request: item #${vendRequest.itemNumber}, price=$${vendRequest.displayPrice}")
                _state.value = VendingState.VendRequestReceived(
                    price = vendRequest.displayPrice,
                    itemNumber = vendRequest.itemNumber
                )
            } else {
                Log.e(TAG, "Failed to decrypt vend request (checksum mismatch)")
                _state.value = VendingState.Error("Failed to decrypt vend request. Wrong passkey?")
            }
        } else {
            Log.e(TAG, "No passkey available for decryption")
            _state.value = VendingState.Error("Device passkey not available. Re-register the device.")
        }
    }

    /**
     * Approves the pending vend request after the user taps "Send".
     *
     * Transitions to ProcessingPayment, sends the credit request to the backend,
     * and writes the approval payload to BLE.
     */
    fun approveVend() {
        val currentState = _state.value
        if (currentState !is VendingState.VendRequestReceived) return

        _state.value = VendingState.ProcessingPayment(
            price = currentState.price,
            itemNumber = currentState.itemNumber
        )

        viewModelScope.launch {
            requestCreditFromBackend()
        }
    }

    /**
     * Cancels the pending vend request when the user taps "Cancel".
     *
     * Closes the BLE session and disconnects from the device.
     */
    fun cancelVend() {
        viewModelScope.launch {
            try {
                bleManager?.closeSession()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to close session: ${e.message}")
            }
        }
        resetToIdle()
    }

    // ── Yappy Payment Flow ──────────────────────────────────────────────────

    /**
     * Initiates a Yappy payment for the pending vend request.
     *
     * 1. Transitions to ProcessingPayment while generating QR
     * 2. Calls Edge Function to generate Yappy QR code
     * 3. Transitions to YappyPayment state with QR hash
     * 4. Starts polling for payment confirmation
     */
    fun approveVendWithYappy() {
        val currentState = _state.value
        if (currentState !is VendingState.VendRequestReceived) return

        _state.value = VendingState.ProcessingPayment(
            price = currentState.price,
            itemNumber = currentState.itemNumber
        )

        viewModelScope.launch {
            val payload = lastVendRequestPayload ?: run {
                _state.value = VendingState.Error("No vend request payload available")
                return@launch
            }

            val subdomain = connectedDevice?.subdomain ?: run {
                _state.value = VendingState.Error("Device subdomain unknown")
                return@launch
            }

            val result = yappyRepository.generateQr(
                payload = payload,
                subdomain = subdomain,
                latitude = null,
                longitude = null
            )

            result.fold(
                onSuccess = { qrResponse ->
                    Log.d(TAG, "Yappy QR generated: txn=${qrResponse.transactionId}")
                    _state.value = VendingState.YappyPayment(
                        price = qrResponse.amount,
                        itemNumber = currentState.itemNumber,
                        qrHash = qrResponse.qrHash,
                        transactionId = qrResponse.transactionId
                    )
                    startYappyPolling(qrResponse.transactionId)
                },
                onFailure = { exception ->
                    Log.e(TAG, "Yappy QR generation failed: ${exception.message}")
                    _state.value = VendingState.Error("Yappy QR generation failed: ${exception.message}")
                }
            )
        }
    }

    /**
     * Polls the Yappy transaction status every 3 seconds.
     *
     * When the status becomes "PAGADO" (paid):
     * 1. The Edge Function records the sale and returns the approval payload
     * 2. The approval payload is written to BLE to approve the vend
     * 3. State transitions to Dispensing
     *
     * @param transactionId The Yappy transaction ID to poll.
     */
    private fun startYappyPolling(transactionId: String) {
        yappyPollingJob?.cancel()
        yappyPollingJob = viewModelScope.launch {
            val payload = lastVendRequestPayload ?: return@launch
            val subdomain = connectedDevice?.subdomain ?: return@launch
            val maxPollingMs = 5 * 60 * 1000L // 5 minute timeout
            val startTime = System.currentTimeMillis()

            while (true) {
                delay(3000) // Poll every 3 seconds

                val currentState = _state.value
                if (currentState !is VendingState.YappyPayment) break

                // Safety timeout: stop polling after 5 minutes
                if (System.currentTimeMillis() - startTime > maxPollingMs) {
                    Log.w(TAG, "Yappy polling timed out after 5 minutes")
                    _state.value = VendingState.Error("Payment timed out. If you already paid, the credit will be applied automatically.")
                    break
                }

                val result = yappyRepository.checkStatus(
                    transactionId = transactionId,
                    payload = payload,
                    subdomain = subdomain,
                    latitude = null,
                    longitude = null
                )

                result.fold(
                    onSuccess = { statusResponse ->
                        Log.d(TAG, "Yappy status response: status='${statusResponse.status}', rawStatus='${statusResponse.yappyRawStatus}', bodyKeys=${statusResponse.yappyBodyKeys}, hasPayload=${statusResponse.payload.isNotEmpty()}")
                        val normalizedStatus = statusResponse.status.uppercase().trim()
                        when (normalizedStatus) {
                            "PAGADO" -> {
                                Log.d(TAG, "Yappy payment confirmed! Sale recorded. Server pushed credit via MQTT.")
                                // The Edge Function has:
                                //   1. Recorded the sale with channel "yappy"
                                //   2. Pushed credit to ESP32 via MQTT (starts new MDB session)

                                if (currentState.mdbSessionTimedOut) {
                                    // MDB session already ended — BLE write would succeed at
                                    // transport level but ESP32 can't act on it. Go straight
                                    // to Success; the MQTT credit push handles dispensing.
                                    Log.d(TAG, "MDB session already timed out — skipping BLE write, MQTT handles dispensing")
                                    _state.value = VendingState.Success(
                                        itemNumber = currentState.itemNumber,
                                        message = "Payment Confirmed!",
                                        subtitle = "Credit sent to machine. Please select your product on the machine to dispense."
                                    )
                                } else {
                                    // MDB session still alive — write BLE approval directly
                                    try {
                                        val approvalPayload = yappyRepository.decodeApprovalPayload(statusResponse)
                                        bleManager?.writePayload(approvalPayload)
                                        Log.d(TAG, "BLE approval written successfully")
                                        _state.value = VendingState.Dispensing
                                    } catch (e: Exception) {
                                        Log.w(TAG, "BLE write failed: ${e.message}")
                                        _state.value = VendingState.Success(
                                            itemNumber = currentState.itemNumber,
                                            message = "Payment Confirmed!",
                                            subtitle = "Credit sent to machine. Please select your product on the machine to dispense."
                                        )
                                    }
                                }
                                return@launch
                            }
                            else -> {
                                // Update status message with actual Yappy status
                                val displayStatus = if (statusResponse.status.isNotEmpty() && statusResponse.status != "UNKNOWN") {
                                    "Yappy status: ${statusResponse.status}"
                                } else {
                                    "Waiting for payment..."
                                }
                                _state.value = currentState.copy(
                                    statusMessage = displayStatus
                                )
                            }
                        }
                    },
                    onFailure = { exception ->
                        Log.e(TAG, "Yappy status check failed: ${exception.message}")
                        // Don't break polling on transient errors, just log
                    }
                )
            }
        }
    }

    /**
     * Cancels the pending Yappy payment.
     *
     * Stops the polling loop, cancels the Yappy transaction,
     * closes the BLE session, and resets to idle.
     */
    fun cancelYappyPayment() {
        val currentState = _state.value
        if (currentState !is VendingState.YappyPayment) return

        yappyPollingJob?.cancel()
        yappyPollingJob = null

        viewModelScope.launch {
            try {
                yappyRepository.cancelTransaction(currentState.transactionId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel Yappy transaction: ${e.message}")
            }
            try {
                bleManager?.closeSession()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to close session: ${e.message}")
            }
        }
        resetToIdle()
    }

    /**
     * Sends the credit request to the backend and writes the approval to BLE.
     *
     * The backend:
     * 1. Decrypts the payload using the device's passkey
     * 2. Validates checksum and timestamp
     * 3. Records the sale in the database
     * 4. Re-encrypts with command 0x03 (approve)
     * 5. Returns the approval payload
     *
     * The approval payload is then written to the BLE characteristic,
     * which triggers the ESP32 to approve the vend with the machine.
     */
    private suspend fun requestCreditFromBackend() {
        val payload = lastVendRequestPayload ?: run {
            _state.value = VendingState.Error("No vend request payload available")
            return
        }

        val subdomain = connectedDevice?.subdomain ?: run {
            _state.value = VendingState.Error("Device subdomain unknown")
            return
        }

        val result = vendingRepository.requestCredit(
            payload = payload,
            subdomain = subdomain,
            latitude = null, // TODO: Get GPS coordinates
            longitude = null
        )

        result.fold(
            onSuccess = { creditResponse ->
                try {
                    // Decode the Base64 approval payload and write to BLE
                    val approvalPayload = vendingRepository.decodeApprovalPayload(creditResponse)
                    bleManager?.writePayload(approvalPayload)
                    _state.value = VendingState.Dispensing
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write approval: ${e.message}")
                    _state.value = VendingState.Error("Failed to approve vend: ${e.message}")
                }
            },
            onFailure = { exception ->
                Log.e(TAG, "Credit request failed: ${exception.message}")
                _state.value = VendingState.Failure("Payment processing failed: ${exception.message}")
                try {
                    bleManager?.closeSession()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to close session: ${e.message}")
                }
            }
        )
    }

    /**
     * Handles a vend success notification (0x0B).
     *
     * The product was dispensed successfully.
     */
    private fun handleVendSuccess(payload: ByteArray) {
        val itemNumber = if (payload.size >= 8) {
            ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
        } else {
            0
        }
        _state.value = VendingState.Success(itemNumber = itemNumber)
    }

    /**
     * Handles a vend failure notification (0x0C).
     *
     * The machine failed to dispense the product.
     */
    private fun handleVendFailure() {
        _state.value = VendingState.Failure("Product could not be dispensed. Please try again.")
    }

    /**
     * Handles a session complete notification (0x0D).
     *
     * The vending session has ended on the machine side.
     * If a Yappy payment is in progress, keep polling — the MDB session
     * timeout is shorter than the time it takes to complete a QR payment.
     * The sale will still be recorded and the machine can re-vend.
     */
    private fun handleSessionComplete() {
        val currentState = _state.value
        if (currentState is VendingState.YappyPayment) {
            Log.w(TAG, "MDB session timed out while waiting for Yappy payment — keeping Yappy poll alive")
            _state.value = currentState.copy(
                statusMessage = "Machine timed out. Waiting for Yappy payment...",
                mdbSessionTimedOut = true
            )
            // Do NOT call disconnect() — keep BLE alive and keep Yappy polling
            return
        }
        _state.value = VendingState.SessionComplete
        viewModelScope.launch {
            disconnect()
        }
    }

    /**
     * Manually disconnects from the current device and resets to idle.
     *
     * Can be called by the user to cancel an active session,
     * or automatically when the session completes.
     */
    fun disconnect() {
        notificationJob?.cancel()
        notificationJob = null
        yappyPollingJob?.cancel()
        yappyPollingJob = null

        viewModelScope.launch {
            try {
                bleManager?.closeSession()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing session: ${e.message}")
            }
            try {
                bleManager?.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting: ${e.message}")
            }
            connectedDevice = null
            lastVendRequestPayload = null
            devicePasskey = null
        }
    }

    /**
     * Resets the vending flow back to the idle state.
     *
     * Called after a completed session, error, or when the user wants to start over.
     */
    fun resetToIdle() {
        disconnect()
        _state.value = VendingState.Idle
    }

    /**
     * Cleanup on ViewModel destruction.
     *
     * Ensures BLE connections are properly closed when the screen
     * is removed from the navigation back stack.
     */
    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
        notificationJob?.cancel()
        yappyPollingJob?.cancel()
        // Disconnect is called in a blocking manner since the scope is being cancelled
    }
}
