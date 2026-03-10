package xyz.vmflow.vending.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents an ESP32 embedded device registered in the backend database.
 *
 * Each device corresponds to a physical ESP32-S3 board installed inside a vending
 * machine. The device is identified by a unique subdomain and uses a passkey for
 * XOR-encrypted BLE communication.
 *
 * ## Database Table: `embedded`
 * This data class maps directly to the Supabase `embedded` table schema.
 *
 * @property id Unique identifier (UUID) assigned by the database.
 * @property subdomain The device's subdomain identifier (e.g., "123456").
 *                     Used as the BLE name prefix: "{subdomain}.panamavendingmachines.com".
 * @property passkey The 18-character XOR encryption key for BLE payload security.
 * @property macAddress The BLE MAC address of the ESP32 hardware.
 * @property status The MQTT connection status: "online" or "offline".
 * @property ownerId The UUID of the user who owns this device.
 * @property createdAt ISO 8601 timestamp of when the device was registered.
 */
@Serializable
data class Device(
    val id: String = "",

    val subdomain: String = "",

    val passkey: String = "",

    @SerialName("mac_address")
    val macAddress: String = "",

    val status: String = "offline",

    @SerialName("owner_id")
    val ownerId: String = "",

    @SerialName("created_at")
    val createdAt: String = ""
) {
    /**
     * Whether the device is currently reporting as online via MQTT.
     */
    val isOnline: Boolean
        get() = status == "online"

    /**
     * The full BLE device name as it appears during scanning.
     */
    val bleName: String
        get() = "$subdomain.panamavendingmachines.com"
}
