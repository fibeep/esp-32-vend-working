/**
 * XOR decryption module for the MQTT bridge.
 *
 * This is a standalone copy of the decryption logic from the Next.js app's
 * crypto module. It is duplicated here rather than shared via a monorepo
 * workspace to keep the two services independently deployable.
 *
 * The logic is byte-identical to:
 *   - next-app/src/lib/crypto/xor.ts
 *   - The original Python implementation in docker/mqtt/domain/mqtt_domain.py
 *   - The ESP32-S3 firmware C implementation
 *
 * See PROTOCOLS.md for the full payload format specification.
 */

/** Total byte length of an XOR-encrypted payload. */
const PAYLOAD_LENGTH = 19;

/** Number of bytes in the XOR passkey. */
const PASSKEY_LENGTH = 18;

/** Maximum allowed timestamp drift in seconds. */
const TIMESTAMP_WINDOW_SECONDS = 8;

/** Result of decoding a XOR-encrypted payload. */
export interface XorDecodeResult {
  /** Price in raw scale-factor units. */
  itemPrice: number;
  /** Vend slot / item number. */
  itemNumber: number;
  /** Paxcounter value (foot traffic count). */
  paxCount: number;
  /** Whether the payload passed validation. */
  valid: boolean;
}

/**
 * Converts a raw scale-factor value back to display currency.
 * Example: fromScaleFactor(150, 1, 2) => 1.50
 */
export function fromScaleFactor(raw: number, factor: number, decimals: number): number {
  return raw * factor * Math.pow(10, -decimals);
}

/**
 * Decrypts and validates a 19-byte XOR-encrypted payload.
 *
 * Mutates the input buffer in place (XOR is its own inverse).
 * Ported from the Python MQTT domain handler.
 *
 * @param payload    - Mutable 19-byte buffer (will be decrypted in place)
 * @param passkey    - 18-character ASCII passkey string
 * @param nowSeconds - Optional "current time" override for testing
 * @returns Decoded fields and a `valid` flag
 */
export function xorDecode(
  payload: Buffer | Uint8Array,
  passkey: string,
  nowSeconds?: number
): XorDecodeResult {
  const invalidResult: XorDecodeResult = {
    itemPrice: 0,
    itemNumber: 0,
    paxCount: 0,
    valid: false,
  };

  if (payload.length !== PAYLOAD_LENGTH) return invalidResult;
  if (passkey.length !== PASSKEY_LENGTH) return invalidResult;

  /* Step 1: XOR decrypt bytes 1-18 with the passkey */
  for (let k = 0; k < PASSKEY_LENGTH; k++) {
    payload[k + 1] ^= passkey.charCodeAt(k);
  }

  /* Step 2: Validate checksum */
  let chk = 0;
  for (let k = 0; k < PAYLOAD_LENGTH - 1; k++) {
    chk += payload[k];
  }
  chk &= 0xff;

  if (chk !== payload[PAYLOAD_LENGTH - 1]) {
    return invalidResult;
  }

  /* Step 3: Validate timestamp */
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
