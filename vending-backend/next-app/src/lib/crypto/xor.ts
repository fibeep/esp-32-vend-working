/**
 * XOR encryption/decryption module for the vending machine protocol.
 *
 * This is a **byte-identical** port of the Deno edge function logic
 * (`docker/volumes/functions/request-credit/index.ts` and
 *  `docker/volumes/functions/send-credit/index.ts`) as well as the
 * ESP32-S3 C firmware implementation.
 *
 * Payload format (19 bytes total):
 *   Byte  0     : CMD   - command type, NOT encrypted
 *   Byte  1     : VER   - protocol version (0x01)
 *   Bytes 2-5   : ITEM_PRICE  - big-endian uint32
 *   Bytes 6-7   : ITEM_NUM    - big-endian uint16
 *   Bytes 8-11  : TIMESTAMP   - big-endian int32 (Unix seconds)
 *   Bytes 12-13 : PAX_COUNT   - big-endian uint16
 *   Bytes 14-17 : RANDOM      - random padding
 *   Byte  18    : CHK   - checksum
 *
 * Encryption steps:
 *   1. Fill bytes 1-17 with payload data (version, price, item, timestamp, pax, random)
 *   2. Compute checksum = sum(bytes[0..17]) & 0xFF and store in byte 18
 *   3. XOR bytes 1-18 with the 18-byte passkey
 *
 * Decryption steps:
 *   1. XOR bytes 1-18 with the 18-byte passkey (reverses encryption)
 *   2. Verify checksum: sum(bytes[0..17]) & 0xFF === byte 18
 *   3. Verify timestamp is within the allowed window
 *   4. Extract fields from the decrypted payload
 */

import {
  PAYLOAD_LENGTH,
  PASSKEY_LENGTH,
  TIMESTAMP_WINDOW_SECONDS,
  PROTOCOL_VERSION,
} from "../constants";

/** Result of decoding a XOR-encrypted payload. */
export interface XorDecodeResult {
  /** Price in raw scale-factor units (divide by 100 for display dollars). */
  itemPrice: number;
  /** Vend slot / item number on the vending machine. */
  itemNumber: number;
  /** Paxcounter value (foot traffic count). */
  paxCount: number;
  /** Whether the payload passed checksum and timestamp validation. */
  valid: boolean;
}

/**
 * Converts a display-currency amount to raw scale-factor units.
 * Example: toScaleFactor(1.50, 1, 2) => 150
 *
 * @param price     - The display price (e.g. 1.50 for $1.50)
 * @param factor    - Scale factor multiplier (default 1)
 * @param decimals  - Number of decimal places (default 2)
 * @returns The raw integer price in MDB units
 */
export function toScaleFactor(price: number, factor: number, decimals: number): number {
  return price / factor / Math.pow(10, -decimals);
}

/**
 * Converts a raw scale-factor value back to display currency.
 * Example: fromScaleFactor(150, 1, 2) => 1.50
 *
 * @param raw       - The raw price from the payload
 * @param factor    - Scale factor multiplier (default 1)
 * @param decimals  - Number of decimal places (default 2)
 * @returns The display price in dollars
 */
export function fromScaleFactor(raw: number, factor: number, decimals: number): number {
  return raw * factor * Math.pow(10, -decimals);
}

/**
 * Decrypts and validates a 19-byte XOR-encrypted payload.
 *
 * This function **mutates** the input `payload` array in place (XOR is its own
 * inverse, so decryption is done by XOR-ing again). The caller should make a
 * copy first if the original encrypted bytes are still needed.
 *
 * Ported byte-for-byte from the Deno edge function `request-credit/index.ts`.
 *
 * @param payload   - Mutable 19-byte Uint8Array (will be decrypted in place)
 * @param passkey   - 18-character ASCII passkey string
 * @param nowSeconds - Optional override for "current time" (Unix seconds), used in tests
 * @returns Decoded fields and a `valid` flag
 */
export function xorDecode(
  payload: Uint8Array,
  passkey: string,
  nowSeconds?: number
): XorDecodeResult {
  const invalidResult: XorDecodeResult = {
    itemPrice: 0,
    itemNumber: 0,
    paxCount: 0,
    valid: false,
  };

  /* Sanity checks */
  if (payload.length !== PAYLOAD_LENGTH) return invalidResult;
  if (passkey.length !== PASSKEY_LENGTH) return invalidResult;

  /* Step 1: XOR decrypt bytes 1-18 with the passkey */
  for (let k = 0; k < PASSKEY_LENGTH; k++) {
    payload[k + 1] ^= passkey.charCodeAt(k);
  }

  /* Step 2: Validate checksum -- sum of bytes 0..17, masked to 8 bits */
  let chk = 0;
  for (let k = 0; k < PAYLOAD_LENGTH - 1; k++) {
    chk += payload[k];
  }
  chk &= 0xff;

  if (chk !== payload[PAYLOAD_LENGTH - 1]) {
    return invalidResult;
  }

  /* Step 3: Validate timestamp (must be within TIMESTAMP_WINDOW_SECONDS of now) */
  const timestamp =
    (payload[8] << 24) |
    (payload[9] << 16) |
    (payload[10] << 8) |
    payload[11];

  const now = nowSeconds ?? Math.floor(Date.now() / 1000);
  if (Math.abs(now - timestamp) > TIMESTAMP_WINDOW_SECONDS) {
    return invalidResult;
  }

  /* Step 4: Extract fields */
  const itemPrice =
    (payload[2] << 24) |
    (payload[3] << 16) |
    (payload[4] << 8) |
    payload[5];

  const itemNumber = (payload[6] << 8) | payload[7];

  const paxCount = (payload[12] << 8) | payload[13];

  return { itemPrice, itemNumber, paxCount, valid: true };
}

/**
 * Builds and encrypts a 19-byte XOR payload.
 *
 * Fills the payload buffer with the provided fields, computes the checksum,
 * then XOR-encrypts bytes 1-18 with the passkey.
 *
 * Ported from the Deno edge function `send-credit/index.ts`.
 *
 * @param cmd       - Command byte (placed at payload[0], NOT encrypted)
 * @param passkey   - 18-character ASCII passkey string
 * @param itemPrice - Raw price in scale-factor units
 * @param itemNumber - Item/slot number
 * @param paxCount  - Paxcounter value (default 0)
 * @param timestampOverride - Optional Unix seconds override (for deterministic tests)
 * @param randomFill - Optional function to fill random bytes (for deterministic tests)
 * @returns The fully encrypted 19-byte Uint8Array
 */
export function xorEncode(
  cmd: number,
  passkey: string,
  itemPrice: number,
  itemNumber: number,
  paxCount: number = 0,
  timestampOverride?: number,
  randomFill?: (buf: Uint8Array) => void
): Uint8Array {
  const payload = new Uint8Array(PAYLOAD_LENGTH);

  /* Fill with random data first (matches ESP32's esp_fill_random) */
  if (randomFill) {
    randomFill(payload);
  } else {
    crypto.getRandomValues(payload);
  }

  const timestampSec = timestampOverride ?? Math.floor(Date.now() / 1000);

  /* Set structured fields */
  payload[0] = cmd;
  payload[1] = PROTOCOL_VERSION;                    // version v1
  payload[2] = (itemPrice >> 24) & 0xff;             // itemPrice big-endian u32
  payload[3] = (itemPrice >> 16) & 0xff;
  payload[4] = (itemPrice >> 8) & 0xff;
  payload[5] = itemPrice & 0xff;
  payload[6] = (itemNumber >> 8) & 0xff;             // itemNumber big-endian u16
  payload[7] = itemNumber & 0xff;
  payload[8] = (timestampSec >> 24) & 0xff;          // timestamp big-endian i32
  payload[9] = (timestampSec >> 16) & 0xff;
  payload[10] = (timestampSec >> 8) & 0xff;
  payload[11] = timestampSec & 0xff;
  payload[12] = (paxCount >> 8) & 0xff;              // paxCount big-endian u16
  payload[13] = paxCount & 0xff;
  /* bytes 14-17 stay as random padding */

  /* Compute checksum: sum(bytes[0..17]) & 0xFF */
  let chk = 0;
  for (let k = 0; k < PAYLOAD_LENGTH - 1; k++) {
    chk += payload[k];
  }
  payload[PAYLOAD_LENGTH - 1] = chk & 0xff;

  /* XOR encrypt bytes 1-18 with the passkey */
  for (let k = 0; k < PASSKEY_LENGTH; k++) {
    payload[k + 1] ^= passkey.charCodeAt(k);
  }

  return payload;
}

/**
 * Re-encrypts a **previously decrypted** payload after modifying it.
 *
 * Used in the credit/request flow: after decryption and validation,
 * the command byte is changed to APPROVE_VEND (0x03), the checksum is
 * recalculated, and the payload is re-encrypted for the Android app
 * to write back to the ESP32 over BLE.
 *
 * This matches the exact logic in the original Deno edge function.
 *
 * @param payload - Mutable 19-byte Uint8Array (already decrypted in place)
 * @param passkey - 18-character ASCII passkey string
 * @param newCmd  - New command byte to set at payload[0]
 */
export function xorReEncrypt(
  payload: Uint8Array,
  passkey: string,
  newCmd: number
): void {
  /* Update the command byte */
  payload[0] = newCmd;

  /* Recalculate checksum over bytes 0..17 */
  let chk = 0;
  for (let k = 0; k < PAYLOAD_LENGTH - 1; k++) {
    chk += payload[k];
  }
  payload[PAYLOAD_LENGTH - 1] = chk & 0xff;

  /* XOR encrypt bytes 1-18 with passkey */
  for (let k = 0; k < PASSKEY_LENGTH; k++) {
    payload[k + 1] ^= passkey.charCodeAt(k);
  }
}
