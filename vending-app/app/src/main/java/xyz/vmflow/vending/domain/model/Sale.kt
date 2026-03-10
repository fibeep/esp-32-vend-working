package xyz.vmflow.vending.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a single vending transaction (sale) recorded in the backend.
 *
 * Sales can originate from three channels:
 * - **"ble"**: Direct BLE payment via the Android app
 * - **"mqtt"**: Remote credit push via MQTT from the backend
 * - **"cash"**: Cash transaction reported by the vending machine
 *
 * ## Database Table: `sales`
 * This data class maps to the Supabase `sales` table schema, including
 * a nested join on the `embedded` table to retrieve the device subdomain.
 *
 * @property id Unique identifier (UUID) for this sale record.
 * @property embeddedId The UUID of the device that processed this sale.
 * @property channel The transaction channel: "ble", "mqtt", or "cash".
 * @property itemNumber The vending slot number from which the product was dispensed.
 * @property itemPrice The display price of the item (after scale factor conversion).
 * @property lat GPS latitude at the time of a BLE transaction (null for MQTT/cash).
 * @property lng GPS longitude at the time of a BLE transaction (null for MQTT/cash).
 * @property ownerId The UUID of the device owner.
 * @property createdAt ISO 8601 timestamp of when the sale occurred.
 * @property embedded Nested device info from a Supabase join (contains subdomain).
 */
@Serializable
data class Sale(
    val id: String = "",

    @SerialName("embedded_id")
    val embeddedId: String = "",

    val channel: String = "",

    @SerialName("item_number")
    val itemNumber: Int = 0,

    @SerialName("item_price")
    val itemPrice: Double = 0.0,

    val lat: Double? = null,

    val lng: Double? = null,

    @SerialName("owner_id")
    val ownerId: String = "",

    @SerialName("created_at")
    val createdAt: String = "",

    val embedded: SaleEmbedded? = null
)

/**
 * Minimal device info nested within a [Sale] record from a Supabase join.
 *
 * When querying sales with `select=*,embedded(subdomain)`, Supabase returns
 * the embedded device's subdomain in this nested object.
 *
 * @property subdomain The device subdomain identifier (e.g., "123456").
 */
@Serializable
data class SaleEmbedded(
    val subdomain: String = ""
)
