/*
 * VMflow.xyz - Cashless Vending Machine Firmware
 *
 * xor_crypto.h - XOR-based payload encryption / decryption
 *
 * All BLE notifications and MQTT messages carrying sale data, vend
 * requests, or PAX counter values use a fixed 19-byte payload that is
 * XOR-obfuscated with the device's 18-byte passkey.
 *
 * Payload layout (19 bytes):
 *  Byte  0      : CMD   (command opcode, NOT encrypted)
 *  Byte  1      : VER   (protocol version, always 0x01)
 *  Bytes 2-5    : ITEM_PRICE  (uint32 big-endian)
 *  Bytes 6-7    : ITEM_NUMBER (uint16 big-endian)
 *  Bytes 8-11   : TIMESTAMP   (int32  big-endian, Unix seconds)
 *  Bytes 12-13  : PAX_COUNT   (uint16 big-endian)
 *  Bytes 14-17  : RANDOM      (random padding from esp_fill_random)
 *  Byte  18     : CHK   (checksum = sum of bytes 0..17, lower 8 bits)
 *
 * Encryption:
 *   1. Fill bytes 1-17 with random data.
 *   2. Overwrite structured fields (VER, PRICE, ITEM, TIME, PAX).
 *   3. Compute checksum over bytes 0..17 -> byte 18.
 *   4. XOR bytes 1..18 with passkey[0..17].
 *
 * Decryption:
 *   1. XOR bytes 1..18 with passkey[0..17].
 *   2. Verify checksum.
 *   3. Verify timestamp is within +/- 8 seconds of current time.
 *   4. Extract price and item number.
 */

#ifndef XOR_CRYPTO_H
#define XOR_CRYPTO_H

#include <stdint.h>

/**
 * @brief Encrypt a 19-byte payload with the device passkey.
 *
 * Fills the payload buffer with random data, sets the structured fields,
 * computes the checksum, and XOR-encrypts bytes 1..18 using my_passkey.
 *
 * @param cmd         Command opcode (placed in payload[0], NOT encrypted).
 *                    Common values:
 *                      0x0A = VEND_REQUEST (BLE notification)
 *                      0x0B = VEND_SUCCESS
 *                      0x0C = VEND_FAILURE
 *                      0x0D = SESSION_COMPLETE
 *                      0x21 = CASH_SALE (MQTT publish)
 *                      0x22 = PAX_COUNTER (MQTT publish)
 * @param itemPrice   Item price in MDB scale-factor units.
 * @param itemNumber  Item / slot number.
 * @param paxCounter  PAX counter value (phone count from BLE scan).
 * @param payload     Output buffer, must be at least 19 bytes.
 */
void xorEncodeWithPasskey(uint8_t cmd, uint16_t itemPrice,
                          uint16_t itemNumber, uint16_t paxCounter,
                          uint8_t *payload);

/**
 * @brief Decrypt and validate a 19-byte XOR-encrypted payload.
 *
 * XOR-decrypts bytes 1..18 using my_passkey, verifies the checksum,
 * validates the timestamp (must be within +/- 8 seconds of now), and
 * extracts the item price and item number.
 *
 * @param itemPrice   [out] Extracted item price in local MDB scale-factor
 *                    units.  May be NULL if not needed.
 * @param itemNumber  [out] Extracted item number.  May be NULL if not needed.
 * @param payload     [in/out] 19-byte buffer.  Modified in-place (decrypted).
 *
 * @return 1 on success (valid checksum and timestamp), 0 on failure.
 */
uint8_t xorDecodeWithPasskey(uint16_t *itemPrice, uint16_t *itemNumber,
                             uint8_t *payload);

#endif /* XOR_CRYPTO_H */
