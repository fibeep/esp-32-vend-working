package xyz.vmflow.vending.bluetooth

import android.util.Log
import com.juul.kable.Filter
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import com.juul.kable.logs.Logging
import com.juul.kable.logs.SystemLogEngine
import com.juul.kable.peripheral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.nio.charset.StandardCharsets

/**
 * Discovered BLE device representation.
 *
 * Wraps the raw Kable advertisement data into a domain-friendly format
 * used by the UI layer for displaying available vending machines.
 *
 * @property name The BLE advertised name (e.g., "123456.panamavendingmachines.com").
 * @property address The MAC address of the device.
 * @property advertisement The raw Kable advertisement for creating a peripheral.
 */
data class BleDevice(
    val name: String,
    val address: String,
    val advertisement: com.juul.kable.Advertisement
) {
    /**
     * Extracts the subdomain portion from the device name.
     * For "123456.panamavendingmachines.com", this returns "123456".
     */
    val subdomain: String
        get() = name.split(".").firstOrNull() ?: ""
}

/**
 * BLE communication manager built on top of JuulLabs Kable library.
 *
 * This class encapsulates all BLE operations needed for the vending flow:
 * - Scanning for nearby VMflow devices
 * - Connecting to a specific device
 * - Writing commands (start session, approve vend, set config)
 * - Observing notifications (vend request, success, failure, session complete)
 * - Disconnecting cleanly
 *
 * It uses Kotlin coroutines natively through Kable, avoiding the callback-heavy
 * patterns of the Android BLE API or RxBle.
 *
 * ## Usage
 * ```kotlin
 * val bleManager = BleManager(scope)
 * bleManager.scanForDevices().collect { device -> ... }
 * bleManager.connect(device)
 * bleManager.startSession()
 * bleManager.observeNotifications().collect { payload -> ... }
 * bleManager.writePayload(approvalBytes)
 * bleManager.disconnect()
 * ```
 *
 * @param scope The [CoroutineScope] for managing BLE operations.
 *              Typically bound to a ViewModel's lifecycle.
 */
class BleManager(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "BleManager"
    }

    /**
     * The Kable characteristic reference used for all reads, writes, and notifications.
     * Configured with the VMflow GATT service and characteristic UUIDs.
     */
    private val characteristic = characteristicOf(
        service = BleConstants.SERVICE_UUID.toString(),
        characteristic = BleConstants.CHARACTERISTIC_UUID.toString()
    )

    /** The currently connected peripheral, or null if disconnected. */
    private var peripheral: Peripheral? = null

    /**
     * Scans for nearby BLE devices that match the VMflow naming pattern.
     *
     * Filters results to only include devices whose name ends with
     * [BleConstants.DEVICE_SUFFIX] (".panamavendingmachines.com") and excludes unconfigured
     * devices named [BleConstants.UNCONFIGURED_NAME] ("0.panamavendingmachines.com").
     *
     * The scan uses low-latency mode for fast discovery and runs until the
     * collecting coroutine is cancelled.
     *
     * @return A [Flow] of [BleDevice] instances as they are discovered.
     *         Duplicate devices (same MAC) may appear; the caller should deduplicate.
     */
    fun scanForDevices(): Flow<BleDevice> {
        val scanner = Scanner {
            logging {
                engine = SystemLogEngine
                level = Logging.Level.Warnings
            }
        }

        return scanner.advertisements
            .filter { advertisement ->
                val name = advertisement.name
                name != null &&
                        name.endsWith(BleConstants.DEVICE_SUFFIX) &&
                        name != BleConstants.UNCONFIGURED_NAME
            }
            .map { advertisement ->
                BleDevice(
                    name = advertisement.name ?: "",
                    address = advertisement.identifier.toString(),
                    advertisement = advertisement
                )
            }
    }

    /**
     * Scans specifically for unconfigured (factory-new) devices.
     *
     * These devices advertise as "0.panamavendingmachines.com" and need to be provisioned
     * with a subdomain and passkey before they can be used for vending.
     *
     * @return A [Flow] of [BleDevice] instances for unconfigured devices.
     */
    fun scanForUnconfiguredDevices(): Flow<BleDevice> {
        val scanner = Scanner {
            logging {
                engine = SystemLogEngine
                level = Logging.Level.Warnings
            }
        }

        return scanner.advertisements
            .filter { advertisement ->
                advertisement.name == BleConstants.UNCONFIGURED_NAME
            }
            .map { advertisement ->
                BleDevice(
                    name = advertisement.name ?: "",
                    address = advertisement.identifier.toString(),
                    advertisement = advertisement
                )
            }
    }

    /**
     * Connects to a specific BLE device.
     *
     * Creates a Kable [Peripheral] from the device's advertisement and establishes
     * a GATT connection. The connection remains active until [disconnect] is called
     * or the device goes out of range.
     *
     * @param device The [BleDevice] to connect to (obtained from scanning).
     * @throws Exception if the connection fails (e.g., device out of range, BLE disabled).
     */
    suspend fun connect(device: BleDevice) {
        Log.d(TAG, "Connecting to device: ${device.name} (${device.address})")

        val p = scope.peripheral(device.advertisement) {
            logging {
                engine = SystemLogEngine
                level = Logging.Level.Warnings
            }
        }
        peripheral = p
        p.connect()

        Log.d(TAG, "Connected to device: ${device.name}")
    }

    /**
     * Disconnects from the currently connected device.
     *
     * Gracefully terminates the GATT connection and clears the peripheral reference.
     * Safe to call even if not connected (no-op in that case).
     */
    suspend fun disconnect() {
        try {
            peripheral?.disconnect()
            Log.d(TAG, "Disconnected from device")
        } catch (e: Exception) {
            Log.w(TAG, "Error during disconnect: ${e.message}")
        } finally {
            peripheral = null
        }
    }

    /**
     * Observes the connection state of the current peripheral.
     *
     * @return A [Flow] of Kable [State] values representing the connection state,
     *         or null if no peripheral is connected.
     */
    fun observeConnectionState(): Flow<State>? {
        return peripheral?.state
    }

    /**
     * Sends the BEGIN_SESSION command to start a vending session.
     *
     * Writes `[0x02]` to the characteristic, which tells the ESP32 to signal
     * the vending machine that a cashless session is ready with unlimited funds
     * (0xFFFF). The machine will then allow the user to select a product.
     *
     * @throws Exception if not connected or the write fails.
     */
    suspend fun startSession() {
        Log.d(TAG, "Starting vending session")
        writeBytes(byteArrayOf(BleConstants.CMD_START_SESSION))
    }

    /**
     * Sends the CLOSE_SESSION command to cancel/end the current session.
     *
     * Writes `[0x04]` to the characteristic, telling the ESP32 to cancel
     * any pending vend and end the session with the vending machine.
     *
     * @throws Exception if not connected or the write fails.
     */
    suspend fun closeSession() {
        Log.d(TAG, "Closing vending session")
        writeBytes(byteArrayOf(BleConstants.CMD_CLOSE_SESSION))
    }

    /**
     * Writes an arbitrary payload to the BLE characteristic.
     *
     * Used for sending approval payloads (0x03) from the backend,
     * as well as provisioning commands (subdomain, passkey, WiFi).
     *
     * @param data The byte array to write. Size varies by command type.
     * @throws Exception if not connected or the write fails.
     */
    suspend fun writePayload(data: ByteArray) {
        Log.d(TAG, "Writing payload: ${data.size} bytes, cmd=0x${String.format("%02X", data[0])}")
        writeBytes(data)
    }

    /**
     * Sets the device subdomain during provisioning.
     *
     * Builds a 22-byte payload: `[0x00, subdomain_bytes..., 0x00]`
     * and writes it to the characteristic.
     *
     * @param subdomain The subdomain string to set (e.g., "123456").
     * @throws Exception if not connected or the write fails.
     */
    suspend fun setSubdomain(subdomain: String) {
        val subdomainBytes = subdomain.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(BleConstants.PROVISION_PAYLOAD_SIZE)
        payload[0] = BleConstants.CMD_SET_SUBDOMAIN
        System.arraycopy(subdomainBytes, 0, payload, 1, subdomainBytes.size)
        payload[1 + subdomainBytes.size] = 0x00

        Log.d(TAG, "Setting subdomain: $subdomain")
        writeBytes(payload)
    }

    /**
     * Sets the device passkey during provisioning.
     *
     * Builds a 22-byte payload: `[0x01, passkey_bytes..., 0x00]`
     * and writes it to the characteristic.
     *
     * @param passkey The 18-character passkey string.
     * @throws Exception if not connected or the write fails.
     */
    suspend fun setPasskey(passkey: String) {
        val passkeyBytes = passkey.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(BleConstants.PROVISION_PAYLOAD_SIZE)
        payload[0] = BleConstants.CMD_SET_PASSKEY
        System.arraycopy(passkeyBytes, 0, payload, 1, passkeyBytes.size)
        payload[1 + passkeyBytes.size] = 0x00

        Log.d(TAG, "Setting passkey (length=${passkey.length})")
        writeBytes(payload)
    }

    /**
     * Sets the WiFi SSID on the device during provisioning.
     *
     * @param ssid The WiFi network name.
     * @throws Exception if not connected or the write fails.
     */
    suspend fun setWifiSsid(ssid: String) {
        val ssidBytes = ssid.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(BleConstants.PROVISION_PAYLOAD_SIZE)
        payload[0] = BleConstants.CMD_SET_WIFI_SSID
        System.arraycopy(ssidBytes, 0, payload, 1, ssidBytes.size)
        payload[1 + ssidBytes.size] = 0x00

        Log.d(TAG, "Setting WiFi SSID: $ssid")
        writeBytes(payload)
    }

    /**
     * Sets the WiFi password on the device during provisioning.
     *
     * @param password The WiFi password.
     * @throws Exception if not connected or the write fails.
     */
    suspend fun setWifiPassword(password: String) {
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(BleConstants.WIFI_PASS_PAYLOAD_SIZE)
        payload[0] = BleConstants.CMD_SET_WIFI_PASS
        System.arraycopy(passwordBytes, 0, payload, 1, passwordBytes.size)
        payload[1 + passwordBytes.size] = 0x00

        Log.d(TAG, "Setting WiFi password (length=${password.length})")
        writeBytes(payload)
    }

    /**
     * Observes BLE notifications from the connected device.
     *
     * Returns a flow of raw byte arrays received as GATT notifications on the
     * VMflow characteristic. Each notification is a 19-byte XOR-encrypted payload
     * that should be decoded using [XorCrypto.decode].
     *
     * The first byte of each payload identifies the event type:
     * - 0x0A: Vend request (price + item number)
     * - 0x0B: Vend success
     * - 0x0C: Vend failure
     * - 0x0D: Session complete
     *
     * @return A [Flow] of byte arrays, or null if no peripheral is connected.
     */
    fun observeNotifications(): Flow<ByteArray>? {
        return peripheral?.observe(characteristic)
    }

    /**
     * Returns whether a peripheral is currently connected.
     */
    val isConnected: Boolean
        get() = peripheral != null

    /**
     * Internal helper to write bytes to the characteristic with WRITE_WITH_RESPONSE.
     */
    private suspend fun writeBytes(data: ByteArray) {
        val p = peripheral ?: throw IllegalStateException("Not connected to any device")
        p.write(characteristic, data, WriteType.WithResponse)
    }
}
