package xyz.vmflow.setup.ble

import android.util.Log
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
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

data class BleDevice(
    val name: String,
    val address: String,
    val advertisement: com.juul.kable.Advertisement
) {
    val subdomain: String
        get() = name.split(".").firstOrNull() ?: ""
}

class BleManager(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "BleManager"
    }

    private val characteristic = characteristicOf(
        service = BleConstants.SERVICE_UUID.toString(),
        characteristic = BleConstants.CHARACTERISTIC_UUID.toString()
    )

    private var peripheral: Peripheral? = null

    fun scanForUnconfiguredDevices(): Flow<BleDevice> {
        val scanner = Scanner {
            logging {
                engine = SystemLogEngine
                level = Logging.Level.Warnings
            }
        }
        return scanner.advertisements
            .filter { it.name == BleConstants.UNCONFIGURED_NAME }
            .map { BleDevice(it.name ?: "", it.identifier.toString(), it) }
    }

    fun scanForDeviceByName(bleName: String): Flow<BleDevice> {
        val scanner = Scanner {
            logging {
                engine = SystemLogEngine
                level = Logging.Level.Warnings
            }
        }
        return scanner.advertisements
            .filter { it.name == bleName }
            .map { BleDevice(it.name ?: "", it.identifier.toString(), it) }
    }

    suspend fun connect(device: BleDevice) {
        Log.d(TAG, "Connecting to: ${device.name} (${device.address})")
        val p = scope.peripheral(device.advertisement) {
            logging {
                engine = SystemLogEngine
                level = Logging.Level.Warnings
            }
        }
        peripheral = p
        p.connect()
        Log.d(TAG, "Connected to: ${device.name}")
    }

    suspend fun disconnect() {
        try {
            peripheral?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Error during disconnect: ${e.message}")
        } finally {
            peripheral = null
        }
    }

    suspend fun setSubdomain(subdomain: String) {
        val bytes = subdomain.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(BleConstants.PROVISION_PAYLOAD_SIZE)
        payload[0] = BleConstants.CMD_SET_SUBDOMAIN
        System.arraycopy(bytes, 0, payload, 1, bytes.size)
        writeBytes(payload)
    }

    suspend fun setPasskey(passkey: String) {
        val bytes = passkey.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(BleConstants.PROVISION_PAYLOAD_SIZE)
        payload[0] = BleConstants.CMD_SET_PASSKEY
        System.arraycopy(bytes, 0, payload, 1, bytes.size)
        writeBytes(payload)
    }

    suspend fun setWifiSsid(ssid: String) {
        val bytes = ssid.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(BleConstants.PROVISION_PAYLOAD_SIZE)
        payload[0] = BleConstants.CMD_SET_WIFI_SSID
        System.arraycopy(bytes, 0, payload, 1, bytes.size)
        writeBytes(payload)
    }

    suspend fun setWifiPassword(password: String) {
        val bytes = password.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(BleConstants.WIFI_PASS_PAYLOAD_SIZE)
        payload[0] = BleConstants.CMD_SET_WIFI_PASS
        System.arraycopy(bytes, 0, payload, 1, bytes.size)
        writeBytes(payload)
    }

    private suspend fun writeBytes(data: ByteArray) {
        val p = peripheral ?: throw IllegalStateException("Not connected")
        p.write(characteristic, data, WriteType.WithResponse)
    }
}
