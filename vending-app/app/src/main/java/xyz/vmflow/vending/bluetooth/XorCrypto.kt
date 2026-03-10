package xyz.vmflow.vending.bluetooth

import xyz.vmflow.vending.domain.model.VendRequest

/**
 * XOR encryption/decryption utility that matches the ESP32 firmware implementation.
 *
 * The ESP32 uses a simple XOR cipher with an 18-byte passkey to encrypt all
 * BLE notification payloads. This ensures that only authorized apps (those that
 * know the device passkey) can decode vend requests and send valid approvals.
 *
 * ## Payload Format (19 bytes)
 * ```
 * Byte:  0     1     2-5          6-7         8-11        12-13       14-17     18
 * Field: CMD   VER   ITEM_PRICE   ITEM_NUM    TIMESTAMP   PAX_COUNT   RANDOM    CHK
 * Size:  1B    1B    4B (u32 BE)  2B (u16 BE) 4B (i32 BE) 2B (u16 BE) 4B        1B
 * ```
 *
 * ## Encryption Process
 * 1. Byte 0 (command) is NOT encrypted
 * 2. Bytes 1-18 are XOR'd with the 18-byte passkey
 * 3. Byte 18 is the checksum: `sum(bytes[0..17]) & 0xFF`
 *
 * @see BleConstants.PAYLOAD_SIZE
 * @see BleConstants.PASSKEY_LENGTH
 */
object XorCrypto {

    /**
     * Decrypts a 19-byte XOR-encrypted payload received from the ESP32.
     *
     * The decryption process:
     * 1. XOR-decrypt bytes 1-18 using the 18-byte passkey
     * 2. Validate the checksum at byte 18
     * 3. Extract item price (bytes 2-5, big-endian uint32) and item number (bytes 6-7, big-endian uint16)
     *
     * **Important**: This function modifies the input [payload] array in place.
     * If you need to preserve the original encrypted bytes, make a copy before calling.
     *
     * @param payload The 19-byte encrypted payload from a BLE notification.
     *                Byte 0 is the command (unencrypted), bytes 1-18 are XOR-encrypted.
     * @param passkey The 18-character ASCII passkey used for XOR decryption.
     *                Must exactly match the passkey stored on the ESP32 device.
     * @return A [VendRequest] with the decoded fields, or `null` if the checksum
     *         validation fails (indicating corrupted data or wrong passkey).
     * @throws IllegalArgumentException if [payload] is not 19 bytes or [passkey] is not 18 chars.
     */
    fun decode(payload: ByteArray, passkey: String): VendRequest? {
        require(payload.size == BleConstants.PAYLOAD_SIZE) {
            "Payload must be ${BleConstants.PAYLOAD_SIZE} bytes, got ${payload.size}"
        }
        require(passkey.length == BleConstants.PASSKEY_LENGTH) {
            "Passkey must be ${BleConstants.PASSKEY_LENGTH} chars, got ${passkey.length}"
        }

        // Step 1: XOR decrypt bytes 1-18 using the passkey
        for (k in 0 until BleConstants.PASSKEY_LENGTH) {
            payload[k + 1] = (payload[k + 1].toInt() xor passkey[k].code).toByte()
        }

        // Step 2: Validate checksum (sum of bytes 0-17, masked to 8 bits)
        var checksum = 0
        for (k in 0 until BleConstants.PASSKEY_LENGTH) {
            checksum += payload[k].toInt() and 0xFF
        }
        checksum = checksum and 0xFF

        val expectedChecksum = payload[BleConstants.PAYLOAD_SIZE - 1].toInt() and 0xFF
        if (checksum != expectedChecksum) {
            return null // Checksum mismatch: corrupted data or wrong passkey
        }

        // Step 3: Extract fields from decrypted payload (big-endian byte order)

        // Item price: bytes 2-5, unsigned 32-bit big-endian integer
        val itemPrice = ((payload[2].toInt() and 0xFF) shl 24) or
                ((payload[3].toInt() and 0xFF) shl 16) or
                ((payload[4].toInt() and 0xFF) shl 8) or
                (payload[5].toInt() and 0xFF)

        // Item number: bytes 6-7, unsigned 16-bit big-endian integer
        val itemNumber = ((payload[6].toInt() and 0xFF) shl 8) or
                (payload[7].toInt() and 0xFF)

        // Timestamp: bytes 8-11, signed 32-bit big-endian integer (Unix epoch seconds)
        val timestamp = ((payload[8].toInt() and 0xFF) shl 24) or
                ((payload[9].toInt() and 0xFF) shl 16) or
                ((payload[10].toInt() and 0xFF) shl 8) or
                (payload[11].toInt() and 0xFF)

        // PAX counter: bytes 12-13, unsigned 16-bit big-endian integer
        val paxCount = ((payload[12].toInt() and 0xFF) shl 8) or
                (payload[13].toInt() and 0xFF)

        return VendRequest(
            command = payload[0].toInt() and 0xFF,
            itemPrice = itemPrice,
            itemNumber = itemNumber,
            timestamp = timestamp.toLong(),
            paxCount = paxCount,
            displayPrice = itemPrice / 100.0
        )
    }

    /**
     * Encodes a 19-byte XOR-encrypted approval payload to send to the ESP32.
     *
     * This creates a valid approval response that the ESP32 will accept.
     * The payload format matches what the backend returns after validating a credit request.
     *
     * @param command The command byte (typically [BleConstants.CMD_APPROVE_VEND] = 0x03).
     * @param itemPrice The item price in scale-factor units (e.g., 150 = $1.50).
     * @param itemNumber The vending slot number.
     * @param passkey The 18-character ASCII passkey for XOR encryption.
     * @return A 19-byte encrypted payload ready to write to the BLE characteristic.
     * @throws IllegalArgumentException if [passkey] is not 18 chars.
     */
    fun encode(
        command: Int,
        itemPrice: Int,
        itemNumber: Int,
        passkey: String
    ): ByteArray {
        require(passkey.length == BleConstants.PASSKEY_LENGTH) {
            "Passkey must be ${BleConstants.PASSKEY_LENGTH} chars, got ${passkey.length}"
        }

        val payload = ByteArray(BleConstants.PAYLOAD_SIZE)

        // Byte 0: Command (NOT encrypted)
        payload[0] = command.toByte()

        // Byte 1: Protocol version
        payload[1] = 0x01

        // Bytes 2-5: Item price (big-endian uint32)
        payload[2] = ((itemPrice shr 24) and 0xFF).toByte()
        payload[3] = ((itemPrice shr 16) and 0xFF).toByte()
        payload[4] = ((itemPrice shr 8) and 0xFF).toByte()
        payload[5] = (itemPrice and 0xFF).toByte()

        // Bytes 6-7: Item number (big-endian uint16)
        payload[6] = ((itemNumber shr 8) and 0xFF).toByte()
        payload[7] = (itemNumber and 0xFF).toByte()

        // Bytes 8-11: Current timestamp (big-endian int32)
        val now = (System.currentTimeMillis() / 1000).toInt()
        payload[8] = ((now shr 24) and 0xFF).toByte()
        payload[9] = ((now shr 16) and 0xFF).toByte()
        payload[10] = ((now shr 8) and 0xFF).toByte()
        payload[11] = (now and 0xFF).toByte()

        // Bytes 12-17: Zero padding (PAX counter + random not needed for approval)

        // Byte 18: Checksum = sum(bytes[0..17]) & 0xFF
        var checksum = 0
        for (k in 0 until BleConstants.PASSKEY_LENGTH) {
            checksum += payload[k].toInt() and 0xFF
        }
        payload[BleConstants.PAYLOAD_SIZE - 1] = (checksum and 0xFF).toByte()

        // XOR encrypt bytes 1-18 using the passkey
        for (k in 0 until BleConstants.PASSKEY_LENGTH) {
            payload[k + 1] = (payload[k + 1].toInt() xor passkey[k].code).toByte()
        }

        return payload
    }
}
