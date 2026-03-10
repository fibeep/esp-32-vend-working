/*
 * VMflow.xyz - Cashless Vending Machine Firmware
 *
 * xor_crypto.c - XOR-based payload encryption / decryption
 *
 * Implements the 19-byte XOR cipher used for all BLE notifications and
 * MQTT messages that carry structured vending data (sale reports, vend
 * requests, PAX counter, credits).
 *
 * The cipher is intentionally lightweight: the security model relies on
 * per-device passkeys that are provisioned at installation time and
 * stored only in NVS on the ESP32 and in the backend database.
 *
 * See xor_crypto.h for the full payload layout and protocol description.
 */

#include <esp_random.h>
#include <esp_log.h>
#include <string.h>
#include <stdlib.h>
#include <time.h>

#include "config.h"
#include "xor_crypto.h"

/* ======================================================================
 * xorDecodeWithPasskey
 * ======================================================================
 * Decrypt a 19-byte payload received from BLE (cmd 0x03 approve) or
 * MQTT (cmd 0x20 credit).
 *
 * Steps:
 *   1. XOR bytes 1..18 with my_passkey[0..17]  (undo encryption)
 *   2. Compute checksum over bytes 0..17
 *   3. Compare with byte 18; reject if mismatch
 *   4. Extract 32-bit timestamp from bytes 8..11
 *   5. Reject if |now - timestamp| > 8 seconds  (replay protection)
 *   6. Extract itemPrice from bytes 2..5  (uint32 big-endian)
 *   7. Extract itemNumber from bytes 6..7 (uint16 big-endian)
 *
 * The price is converted from the sender's scale factor (1, dec 2) to
 * the local MDB scale factor configured at build time.
 */
uint8_t xorDecodeWithPasskey(uint16_t *itemPrice, uint16_t *itemNumber,
                             uint8_t *payload)
{
    /* --- Step 1: XOR decrypt bytes 1..18 --- */
    for (int x = 0; x < sizeof(my_passkey); x++) {
        payload[x + 1] ^= my_passkey[x];
    }

    int p_len = sizeof(my_passkey) + 1;  /* 19 */

    /* --- Step 2-3: Checksum validation --- */
    uint8_t chk = 0x00;
    for (int x = 0; x < p_len - 1; x++) {
        chk += payload[x];
    }

    if (chk != payload[p_len - 1]) {
        /* Checksum mismatch - payload corrupted or wrong passkey */
        return 0;
    }

    /* --- Step 4-5: Timestamp validation (replay protection) --- */
    int32_t timestamp = ((uint32_t)payload[8]  << 24) |
                        ((uint32_t)payload[9]  << 16) |
                        ((uint32_t)payload[10] << 8)  |
                        ((uint32_t)payload[11] << 0);

    time_t now = time(NULL);

    if (abs((int32_t)now - timestamp) > 8 /* seconds */) {
        /* Timestamp too far from current time - possible replay attack */
        return 0;
    }

    /* --- Step 6: Extract item price --- */
    int32_t itemPrice32 = ((uint32_t)payload[2] << 24) |
                          ((uint32_t)payload[3] << 16) |
                          ((uint32_t)payload[4] << 8)  |
                          ((uint32_t)payload[5] << 0);

    if (itemPrice) {
        /*
         * Convert from sender's scale (scale=1, dec=2) to our local
         * MDB scale factor.  This double-conversion preserves the raw
         * integer value when both sides use the same scale.
         */
        *itemPrice = TO_SCALE_FACTOR(
            FROM_SCALE_FACTOR(itemPrice32, 1, 2),
            CONFIG_MDB_SCALE_FACTOR, CONFIG_MDB_DECIMAL_PLACES);
    }

    /* --- Step 7: Extract item number --- */
    if (itemNumber) {
        *itemNumber = ((uint16_t)payload[6] << 8) |
                      ((uint16_t)payload[7] << 0);
    }

    return 1;  /* Success */
}

/* ======================================================================
 * xorEncodeWithPasskey
 * ======================================================================
 * Build and encrypt a 19-byte payload for BLE notification or MQTT
 * publish.
 *
 * Steps:
 *   1. Fill bytes 1..18 with cryptographically random data
 *   2. Set payload[0] = cmd (NOT encrypted)
 *   3. Set payload[1] = 0x01 (protocol version)
 *   4. Set bytes 2..5 = itemPrice (uint32 big-endian)
 *   5. Set bytes 6..7 = itemNumber (uint16 big-endian)
 *   6. Set bytes 8..11 = current Unix timestamp (int32 big-endian)
 *   7. Set bytes 12..13 = paxCounter (uint16 big-endian)
 *   8. Compute checksum = sum(bytes 0..17) & 0xFF -> byte 18
 *   9. XOR bytes 1..18 with my_passkey[0..17]
 *
 * The random fill in step 1 ensures that bytes 14..17 (unused fields)
 * contain unpredictable data, making it harder to recover the passkey
 * from captured payloads.
 */
void xorEncodeWithPasskey(uint8_t cmd, uint16_t itemPrice,
                          uint16_t itemNumber, uint16_t paxCounter,
                          uint8_t *payload)
{
    /*
     * Convert the item price from local MDB scale to the normalised
     * wire format (scale=1, dec=2) used in the payload.
     */
    uint32_t itemPrice32 = TO_SCALE_FACTOR(
        FROM_SCALE_FACTOR(itemPrice, CONFIG_MDB_SCALE_FACTOR,
                          CONFIG_MDB_DECIMAL_PLACES),
        1, 2);

    /* Step 1: Random fill for bytes 1..18 */
    esp_fill_random(payload + 1, sizeof(my_passkey));

    time_t now = time(NULL);

    /* Step 2: Command byte (unencrypted) */
    payload[0] = cmd;

    /* Step 3: Protocol version */
    payload[1] = 0x01;

    /* Step 4: Item price (uint32 big-endian) */
    payload[2] = itemPrice32 >> 24;
    payload[3] = itemPrice32 >> 16;
    payload[4] = itemPrice32 >> 8;
    payload[5] = itemPrice32;

    /* Step 5: Item number (uint16 big-endian) */
    payload[6] = itemNumber >> 8;
    payload[7] = itemNumber;

    /* Step 6: Unix timestamp (int32 big-endian) */
    payload[8]  = now >> 24;
    payload[9]  = now >> 16;
    payload[10] = now >> 8;
    payload[11] = now;

    /* Step 7: PAX counter (uint16 big-endian) */
    payload[12] = paxCounter >> 8;
    payload[13] = paxCounter;

    /* Bytes 14..17 retain random data from step 1 */

    /* Step 8: Checksum over bytes 0..17 */
    int p_len = sizeof(my_passkey) + 1;  /* 19 */
    uint8_t chk = 0x00;
    for (int x = 0; x < p_len - 1; x++) {
        chk += payload[x];
    }
    payload[p_len - 1] = chk;

    /* Step 9: XOR encrypt bytes 1..18 */
    for (int x = 0; x < sizeof(my_passkey); x++) {
        payload[x + 1] ^= my_passkey[x];
    }
}
