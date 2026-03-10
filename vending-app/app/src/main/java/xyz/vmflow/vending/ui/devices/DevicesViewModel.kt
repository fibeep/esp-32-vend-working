package xyz.vmflow.vending.ui.devices

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.vmflow.vending.bluetooth.BleDevice
import xyz.vmflow.vending.bluetooth.BleManager
import xyz.vmflow.vending.data.repository.DeviceRepository
import xyz.vmflow.vending.domain.model.Device

/**
 * UI state for the devices management screen.
 *
 * @property devices The list of registered devices.
 * @property isLoading Whether devices are being fetched.
 * @property isRefreshing Whether a pull-to-refresh is in progress.
 * @property error An error message, or null if none.
 * @property showProvisionDialog Whether the device provisioning dialog is visible.
 * @property provisioningInProgress Whether a device provisioning operation is active.
 */
data class DevicesUiState(
    val devices: List<Device> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val showProvisionDialog: Boolean = false,
    val provisioningInProgress: Boolean = false
)

/**
 * ViewModel for the devices management screen.
 *
 * Manages the list of registered ESP32 devices, handles device registration
 * (provisioning), and provides refresh capabilities.
 *
 * @property deviceRepository Repository for device CRUD operations.
 */
class DevicesViewModel(
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    companion object {
        private const val TAG = "DevicesViewModel"
    }

    private val _uiState = MutableStateFlow(DevicesUiState())

    /** Observable UI state for the devices screen. */
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    init {
        loadDevices()
    }

    /**
     * Loads the list of devices owned by the current user.
     *
     * Called on initialization and on manual refresh.
     */
    fun loadDevices() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val result = deviceRepository.getDevices()

            result.fold(
                onSuccess = { devices ->
                    _uiState.value = _uiState.value.copy(
                        devices = devices,
                        isLoading = false,
                        isRefreshing = false
                    )
                },
                onFailure = { exception ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = exception.message ?: "Failed to load devices"
                    )
                }
            )
        }
    }

    /**
     * Refreshes the device list (pull-to-refresh).
     */
    fun refresh() {
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        loadDevices()
    }

    /**
     * Shows the device provisioning dialog.
     */
    fun showProvisionDialog() {
        _uiState.value = _uiState.value.copy(showProvisionDialog = true)
    }

    /**
     * Hides the device provisioning dialog.
     */
    fun hideProvisionDialog() {
        _uiState.value = _uiState.value.copy(showProvisionDialog = false)
    }

    /**
     * Full provisioning flow: register in backend + write config to ESP32 via BLE.
     *
     * 1. POST the MAC address to Supabase to create the device record
     * 2. Backend generates subdomain and passkey
     * 3. Connect to the ESP32 via BLE
     * 4. Write subdomain (cmd 0x00) and passkey (cmd 0x01) to the device
     * 5. Disconnect and refresh the device list
     *
     * @param bleDevice The unconfigured BLE device selected by the user.
     */
    fun provisionDevice(bleDevice: BleDevice) {
        viewModelScope.launch {
            Log.d(TAG, "provisionDevice called for: ${bleDevice.name} (${bleDevice.address})")
            _uiState.value = _uiState.value.copy(provisioningInProgress = true, error = null)

            // Step 1: Register device in backend
            Log.d(TAG, "Registering device with backend...")
            val result = deviceRepository.registerDevice(bleDevice.address)
            Log.d(TAG, "registerDevice result: success=${result.isSuccess}, failure=${result.isFailure}")

            result.fold(
                onSuccess = { device ->
                    // Step 2: Write subdomain and passkey to ESP32 via BLE
                    try {
                        val bleManager = BleManager(viewModelScope)

                        Log.d(TAG, "Connecting to device for provisioning: ${bleDevice.address}")
                        bleManager.connect(bleDevice)

                        // Small delay to ensure connection is stable
                        delay(500)

                        Log.d(TAG, "Writing subdomain: ${device.subdomain}")
                        bleManager.setSubdomain(device.subdomain)
                        delay(300)

                        Log.d(TAG, "Writing passkey (length=${device.passkey.length})")
                        bleManager.setPasskey(device.passkey)
                        delay(300)

                        Log.d(TAG, "Provisioning complete, disconnecting")
                        bleManager.disconnect()

                        _uiState.value = _uiState.value.copy(
                            provisioningInProgress = false,
                            showProvisionDialog = false
                        )
                        loadDevices()

                    } catch (e: Exception) {
                        Log.e(TAG, "BLE provisioning failed: ${e.message}")
                        _uiState.value = _uiState.value.copy(
                            provisioningInProgress = false,
                            error = "Device registered but BLE provisioning failed: ${e.message}. " +
                                    "The device may need to be re-provisioned."
                        )
                        loadDevices()
                    }
                },
                onFailure = { exception ->
                    Log.e(TAG, "Device registration failed: ${exception.message}", exception)
                    _uiState.value = _uiState.value.copy(
                        provisioningInProgress = false,
                        error = exception.message ?: "Failed to register device"
                    )
                }
            )
        }
    }
}
