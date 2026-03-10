package xyz.vmflow.vending.domain.model

/**
 * Decoded vend request data extracted from a BLE notification payload.
 *
 * When the user selects a product on the vending machine, the ESP32 sends
 * a 19-byte XOR-encrypted notification (event 0x0A) containing the price
 * and slot number. After decryption via [xyz.vmflow.vending.bluetooth.XorCrypto],
 * the data is represented by this class.
 *
 * ## Price Handling
 * The raw [itemPrice] is in scale-factor units (default: cents).
 * The [displayPrice] is the human-readable price: `itemPrice / 100.0`.
 * Example: `itemPrice = 150` -> `displayPrice = 1.50` ($1.50)
 *
 * @property command The command byte from the payload (0x0A for vend request).
 * @property itemPrice The raw price in scale-factor units (e.g., 150 = $1.50).
 * @property itemNumber The vending machine slot/item number.
 * @property timestamp The Unix timestamp (seconds) embedded in the payload.
 * @property paxCount The foot traffic counter from the ESP32's BLE scan.
 * @property displayPrice The human-readable price computed from itemPrice.
 */
data class VendRequest(
    val command: Int,
    val itemPrice: Int,
    val itemNumber: Int,
    val timestamp: Long = 0L,
    val paxCount: Int = 0,
    val displayPrice: Double
)
