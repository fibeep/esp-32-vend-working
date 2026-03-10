package xyz.vmflow.vending.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [XorCrypto] encoding and decoding.
 *
 * Verifies that the XOR encryption implementation exactly matches the ESP32
 * firmware behavior. These tests use known test vectors to ensure byte-level
 * compatibility between the Android app and the ESP32 device.
 *
 * ## Test Categories
 * 1. **Decode tests**: Verify decryption of encrypted payloads
 * 2. **Encode tests**: Verify encryption of approval payloads
 * 3. **Roundtrip tests**: Encode then decode, verify data integrity
 * 4. **Checksum validation tests**: Verify rejection of corrupt payloads
 * 5. **Edge case tests**: Boundary values, zero prices, max values
 */
class XorCryptoTest {

    companion object {
        /** Standard 18-character passkey for testing */
        private const val TEST_PASSKEY = "ABCDEFGHIJKLMNOPQR"

        /** Alternative passkey for negative testing */
        private const val WRONG_PASSKEY = "123456789012345678"
    }

    /**
     * Creates a valid XOR-encrypted 19-byte payload for testing.
     *
     * Builds a payload with the given fields, computes the checksum,
     * and XOR-encrypts bytes 1-18 with the passkey.
     */
    private fun createEncryptedPayload(
        command: Int,
        itemPrice: Int,
        itemNumber: Int,
        passkey: String = TEST_PASSKEY
    ): ByteArray {
        val payload = ByteArray(19)

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

        // Bytes 8-17: Zeros (timestamp, pax, random)

        // Byte 18: Checksum = sum(bytes[0..17]) & 0xFF
        var checksum = 0
        for (k in 0 until 18) {
            checksum += payload[k].toInt() and 0xFF
        }
        payload[18] = (checksum and 0xFF).toByte()

        // XOR encrypt bytes 1-18
        for (k in 0 until 18) {
            payload[k + 1] = (payload[k + 1].toInt() xor passkey[k].code).toByte()
        }

        return payload
    }

    // ── Decode Tests ───────────────────────────────────────────────────────────

    @Test
    fun `decode valid vend request payload`() {
        // Create a vend request with price=$1.50 (150 cents), item #5
        val payload = createEncryptedPayload(
            command = 0x0A,
            itemPrice = 150,
            itemNumber = 5
        )

        val result = XorCrypto.decode(payload, TEST_PASSKEY)

        assertNotNull("Decode should succeed for valid payload", result)
        assertEquals("Command should be 0x0A", 0x0A, result!!.command)
        assertEquals("Item price should be 150", 150, result.itemPrice)
        assertEquals("Item number should be 5", 5, result.itemNumber)
        assertEquals("Display price should be $1.50", 1.50, result.displayPrice, 0.001)
    }

    @Test
    fun `decode with wrong passkey returns null`() {
        val payload = createEncryptedPayload(
            command = 0x0A,
            itemPrice = 150,
            itemNumber = 5,
            passkey = TEST_PASSKEY
        )

        // Try to decode with a different passkey
        val result = XorCrypto.decode(payload, WRONG_PASSKEY)

        assertNull("Decode should return null with wrong passkey", result)
    }

    @Test
    fun `decode with corrupted checksum returns null`() {
        val payload = createEncryptedPayload(
            command = 0x0A,
            itemPrice = 150,
            itemNumber = 5
        )

        // Corrupt the checksum byte
        payload[18] = (payload[18].toInt() xor 0xFF).toByte()

        val result = XorCrypto.decode(payload, TEST_PASSKEY)

        assertNull("Decode should return null with corrupted checksum", result)
    }

    @Test
    fun `decode with corrupted data returns null`() {
        val payload = createEncryptedPayload(
            command = 0x0A,
            itemPrice = 150,
            itemNumber = 5
        )

        // Corrupt a data byte
        payload[5] = (payload[5].toInt() xor 0x42).toByte()

        val result = XorCrypto.decode(payload, TEST_PASSKEY)

        assertNull("Decode should return null with corrupted data", result)
    }

    @Test
    fun `decode zero price`() {
        val payload = createEncryptedPayload(
            command = 0x0A,
            itemPrice = 0,
            itemNumber = 1
        )

        val result = XorCrypto.decode(payload, TEST_PASSKEY)

        assertNotNull("Decode should succeed for zero price", result)
        assertEquals("Item price should be 0", 0, result!!.itemPrice)
        assertEquals("Display price should be 0.0", 0.0, result.displayPrice, 0.001)
    }

    @Test
    fun `decode max price value`() {
        // Max price that fits in a uint32 (but reasonable: $999,999.99 = 99999999 cents)
        val maxPrice = 99999999
        val payload = createEncryptedPayload(
            command = 0x0A,
            itemPrice = maxPrice,
            itemNumber = 0xFFFF
        )

        val result = XorCrypto.decode(payload, TEST_PASSKEY)

        assertNotNull("Decode should succeed for max price", result)
        assertEquals("Item price should be max", maxPrice, result!!.itemPrice)
        assertEquals("Item number should be max uint16", 0xFFFF, result.itemNumber)
    }

    @Test
    fun `decode vend success event`() {
        val payload = createEncryptedPayload(
            command = 0x0B,
            itemPrice = 250,
            itemNumber = 10
        )

        val result = XorCrypto.decode(payload, TEST_PASSKEY)

        assertNotNull("Decode should succeed for vend success", result)
        assertEquals("Command should be 0x0B", 0x0B, result!!.command)
    }

    @Test
    fun `decode vend failure event`() {
        val payload = createEncryptedPayload(
            command = 0x0C,
            itemPrice = 250,
            itemNumber = 10
        )

        val result = XorCrypto.decode(payload, TEST_PASSKEY)

        assertNotNull("Decode should succeed for vend failure", result)
        assertEquals("Command should be 0x0C", 0x0C, result!!.command)
    }

    @Test
    fun `decode session complete event`() {
        val payload = createEncryptedPayload(
            command = 0x0D,
            itemPrice = 0,
            itemNumber = 0
        )

        val result = XorCrypto.decode(payload, TEST_PASSKEY)

        assertNotNull("Decode should succeed for session complete", result)
        assertEquals("Command should be 0x0D", 0x0D, result!!.command)
    }

    // ── Encode Tests ───────────────────────────────────────────────────────────

    @Test
    fun `encode produces 19-byte payload`() {
        val payload = XorCrypto.encode(
            command = 0x03,
            itemPrice = 150,
            itemNumber = 5,
            passkey = TEST_PASSKEY
        )

        assertEquals("Encoded payload should be 19 bytes", 19, payload.size)
    }

    @Test
    fun `encode preserves command byte unencrypted`() {
        val payload = XorCrypto.encode(
            command = 0x03,
            itemPrice = 150,
            itemNumber = 5,
            passkey = TEST_PASSKEY
        )

        assertEquals("Command byte should be unencrypted", 0x03.toByte(), payload[0])
    }

    // ── Roundtrip Tests ────────────────────────────────────────────────────────

    @Test
    fun `encode then decode produces original values`() {
        val originalPrice = 350
        val originalItem = 42
        val command = 0x03

        val encoded = XorCrypto.encode(
            command = command,
            itemPrice = originalPrice,
            itemNumber = originalItem,
            passkey = TEST_PASSKEY
        )

        val decoded = XorCrypto.decode(encoded, TEST_PASSKEY)

        assertNotNull("Roundtrip decode should succeed", decoded)
        assertEquals("Command should survive roundtrip", command, decoded!!.command)
        assertEquals("Item price should survive roundtrip", originalPrice, decoded.itemPrice)
        assertEquals("Item number should survive roundtrip", originalItem, decoded.itemNumber)
    }

    @Test
    fun `roundtrip with various prices`() {
        val testPrices = listOf(0, 1, 50, 100, 150, 999, 5000, 10000, 65535)

        testPrices.forEach { price ->
            val encoded = XorCrypto.encode(
                command = 0x03,
                itemPrice = price,
                itemNumber = 1,
                passkey = TEST_PASSKEY
            )

            val decoded = XorCrypto.decode(encoded, TEST_PASSKEY)

            assertNotNull("Roundtrip should succeed for price=$price", decoded)
            assertEquals("Price $price should survive roundtrip", price, decoded!!.itemPrice)
        }
    }

    @Test
    fun `roundtrip with various item numbers`() {
        val testItems = listOf(0, 1, 10, 100, 255, 256, 1000, 65535)

        testItems.forEach { item ->
            val encoded = XorCrypto.encode(
                command = 0x03,
                itemPrice = 150,
                itemNumber = item,
                passkey = TEST_PASSKEY
            )

            val decoded = XorCrypto.decode(encoded, TEST_PASSKEY)

            assertNotNull("Roundtrip should succeed for item=$item", decoded)
            assertEquals("Item $item should survive roundtrip", item, decoded!!.itemNumber)
        }
    }

    // ── Validation Tests ───────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `decode rejects payload shorter than 19 bytes`() {
        XorCrypto.decode(ByteArray(18), TEST_PASSKEY)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode rejects payload longer than 19 bytes`() {
        XorCrypto.decode(ByteArray(20), TEST_PASSKEY)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode rejects passkey shorter than 18 chars`() {
        XorCrypto.decode(ByteArray(19), "SHORT")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode rejects passkey longer than 18 chars`() {
        XorCrypto.decode(ByteArray(19), "THIS_PASSKEY_IS_TOO_LONG")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encode rejects passkey shorter than 18 chars`() {
        XorCrypto.encode(0x03, 150, 5, "SHORT")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encode rejects passkey longer than 18 chars`() {
        XorCrypto.encode(0x03, 150, 5, "THIS_PASSKEY_IS_TOO_LONG")
    }

    // ── Display Price Tests ────────────────────────────────────────────────────

    @Test
    fun `display price converts cents to dollars correctly`() {
        val payload = createEncryptedPayload(
            command = 0x0A,
            itemPrice = 250,
            itemNumber = 1
        )

        val result = XorCrypto.decode(payload, TEST_PASSKEY)

        assertNotNull(result)
        assertEquals("250 cents should be $2.50", 2.50, result!!.displayPrice, 0.001)
    }

    @Test
    fun `display price handles single cent correctly`() {
        val payload = createEncryptedPayload(
            command = 0x0A,
            itemPrice = 1,
            itemNumber = 1
        )

        val result = XorCrypto.decode(payload, TEST_PASSKEY)

        assertNotNull(result)
        assertEquals("1 cent should be $0.01", 0.01, result!!.displayPrice, 0.001)
    }
}
