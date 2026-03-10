package xyz.vmflow.vending.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
     * Registers a new device by MAC address and provisions it via BLE.
     *
     * The flow:
     * 1. POST the MAC address to the backend to create the device record
     * 2. Backend generates subdomain and passkey
     * 3. Write subdomain and passkey to the device via BLE
     * 4. Refresh the device list
     *
     * @param macAddress The BLE MAC address of the unconfigured device.
     */
    fun registerDevice(macAddress: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(provisioningInProgress = true)

            val result = deviceRepository.registerDevice(macAddress)

            result.fold(
                onSuccess = { device ->
                    _uiState.value = _uiState.value.copy(
                        provisioningInProgress = false,
                        showProvisionDialog = false
                    )
                    // Refresh the device list to show the new device
                    loadDevices()
                },
                onFailure = { exception ->
                    _uiState.value = _uiState.value.copy(
                        provisioningInProgress = false,
                        error = exception.message ?: "Failed to register device"
                    )
                }
            )
        }
    }
}
