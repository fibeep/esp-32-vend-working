package xyz.vmflow.setup.ui.setup

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.vmflow.setup.ble.BleDevice
import xyz.vmflow.setup.ble.BleManager
import xyz.vmflow.setup.data.Device
import xyz.vmflow.setup.data.DeviceRepository
import xyz.vmflow.setup.data.Machine

enum class SetupStep {
    WIFI_CONFIG,
    CREATE_MACHINE,
    PROVISIONING,
    STATUS
}

data class SetupUiState(
    val step: SetupStep = SetupStep.WIFI_CONFIG,
    val isLoading: Boolean = false,
    val error: String? = null,
    // WiFi
    val wifiSsid: String = "",
    val wifiPassword: String = "",
    // Machine
    val machineName: String = "",
    val machineLocation: String = "",
    // Provisioning progress
    val provisioningStatus: String = "",
    // Status checks
    val deviceRegistered: Boolean = false,
    val machineCreated: Boolean = false,
    val machineLinked: Boolean = false,
    val bleConfigSent: Boolean = false,
    val deviceOnline: Boolean = false,
    // Data
    val device: Device? = null,
    val machine: Machine? = null,
    // Devices list
    val devices: List<Device> = emptyList()
)

class SetupViewModel(
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    companion object {
        private const val TAG = "SetupViewModel"
    }

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    fun updateWifiSsid(ssid: String) {
        _uiState.value = _uiState.value.copy(wifiSsid = ssid, error = null)
    }

    fun updateWifiPassword(password: String) {
        _uiState.value = _uiState.value.copy(wifiPassword = password, error = null)
    }

    fun updateMachineName(name: String) {
        _uiState.value = _uiState.value.copy(machineName = name, error = null)
    }

    fun updateMachineLocation(location: String) {
        _uiState.value = _uiState.value.copy(machineLocation = location, error = null)
    }

    fun goToMachineStep() {
        val state = _uiState.value
        if (state.wifiSsid.isBlank()) {
            _uiState.value = state.copy(error = "WiFi SSID is required")
            return
        }
        if (state.wifiPassword.isBlank()) {
            _uiState.value = state.copy(error = "WiFi password is required")
            return
        }
        _uiState.value = state.copy(step = SetupStep.CREATE_MACHINE, error = null)
    }

    fun startProvisioning(macAddress: String, bleDevice: BleDevice?, scope: kotlinx.coroutines.CoroutineScope) {
        val state = _uiState.value
        if (state.machineName.isBlank()) {
            _uiState.value = state.copy(error = "Machine name is required")
            return
        }

        _uiState.value = state.copy(
            step = SetupStep.PROVISIONING,
            isLoading = true,
            error = null,
            provisioningStatus = "Registering device..."
        )

        viewModelScope.launch {
            try {
                // Step 1: Register device in backend
                _uiState.value = _uiState.value.copy(provisioningStatus = "Registering device...")
                val deviceResult = deviceRepository.registerDevice(macAddress)
                val device = deviceResult.getOrThrow()
                _uiState.value = _uiState.value.copy(
                    deviceRegistered = true,
                    device = device,
                    provisioningStatus = "Creating machine..."
                )
                Log.d(TAG, "Device registered: subdomain=${device.subdomain}, passkey=${device.passkey}")

                // Step 2: Create machine
                val machineResult = deviceRepository.createMachine(
                    _uiState.value.machineName,
                    _uiState.value.machineLocation.ifBlank { null }
                )
                val machine = machineResult.getOrThrow()
                _uiState.value = _uiState.value.copy(
                    machineCreated = true,
                    machine = machine,
                    provisioningStatus = "Linking device to machine..."
                )
                Log.d(TAG, "Machine created: id=${machine.id}")

                // Step 3: Link device to machine
                deviceRepository.linkDeviceToMachine(device.id, machine.id).getOrThrow()
                _uiState.value = _uiState.value.copy(
                    machineLinked = true,
                    provisioningStatus = "Sending BLE configuration..."
                )
                Log.d(TAG, "Device linked to machine")

                // Step 4: BLE write configuration
                if (bleDevice != null) {
                    val bleManager = BleManager(scope)
                    try {
                        bleManager.connect(bleDevice)
                        delay(500) // Brief delay for connection stability

                        bleManager.setSubdomain(device.subdomain)
                        delay(200)
                        bleManager.setPasskey(device.passkey)
                        delay(200)
                        bleManager.setWifiSsid(_uiState.value.wifiSsid)
                        delay(200)
                        bleManager.setWifiPassword(_uiState.value.wifiPassword)
                        delay(200)

                        bleManager.disconnect()
                    } catch (e: Exception) {
                        Log.e(TAG, "BLE write error: ${e.message}")
                        bleManager.disconnect()
                        throw Exception("Failed to send BLE config: ${e.message}")
                    }
                }

                _uiState.value = _uiState.value.copy(
                    bleConfigSent = true,
                    provisioningStatus = "Waiting for device to come online...",
                    step = SetupStep.STATUS
                )

                // Step 5: Poll for device online status
                startPollingDeviceStatus(device.id)

            } catch (e: Exception) {
                Log.e(TAG, "Provisioning failed: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Provisioning failed",
                    step = SetupStep.STATUS
                )
            }
        }
    }

    private fun startPollingDeviceStatus(deviceId: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var attempts = 0
            val maxAttempts = 60 // Poll for up to 2 minutes

            while (attempts < maxAttempts) {
                delay(2000)
                attempts++

                try {
                    val result = deviceRepository.getDevice(deviceId)
                    result.onSuccess { device ->
                        _uiState.value = _uiState.value.copy(
                            device = device,
                            deviceOnline = device.isOnline,
                            provisioningStatus = if (device.isOnline) "Device is online!" else "Waiting for device... ($attempts/${maxAttempts})"
                        )
                        if (device.isOnline) {
                            _uiState.value = _uiState.value.copy(isLoading = false)
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Poll error: ${e.message}")
                }
            }

            // Timed out
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                provisioningStatus = "Device hasn't come online yet. It may take a few more minutes."
            )
        }
    }

    fun loadDevices() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            deviceRepository.getDevices().fold(
                onSuccess = { _uiState.value = _uiState.value.copy(devices = it, isLoading = false) },
                onFailure = { _uiState.value = _uiState.value.copy(error = it.message, isLoading = false) }
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }
}
