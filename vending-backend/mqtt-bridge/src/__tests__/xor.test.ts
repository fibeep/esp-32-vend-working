/**
 * Tests for the MQTT bridge's XOR decryption module.
 *
 * Verifies that the bridge's standalone XOR implementation produces
 * identical results to the Next.js app's version and the original
 * Python/ESP32 implementations.
 */

import { describe, it, expect } from "vitest";
import { xorDecode, fromScaleFactor } from "../crypto/xor";

const TEST_PASSKEY = "abcdefghijklmnopqr";
const TEST_TIMESTAMP = 1710000000;
const PAYLOAD_LENGTH = 19;
const PASSKEY_LENGTH = 18;

/**
 * Helper to create a valid encrypted test payload.
 * Mirrors the xorEncode function from the Next.js app but implemented
 * independently to verify cross-module compatibility.
 */
function createTestPayload(
  cmd: number,
  passkey: string,
  itemPrice: number,
  itemNumber: number,
  paxCount: number,
  timestamp: number
): Buffer {
  const payload = Buffer.alloc(PAYLOAD_LENGTH, 0);

  payload[0] = cmd;
  payload[1] = 0x01; // version
  payload[2] = (itemPrice >> 24) & 0xff;
  payload[3] = (itemPrice >> 16) & 0xff;
  payload[4] = (itemPrice >> 8) & 0xff;
  payload[5] = itemPrice & 0xff;
  payload[6] = (itemNumber >> 8) & 0xff;
  payload[7] = itemNumber & 0xff;
  payload[8] = (timestamp >> 24) & 0xff;
  payload[9] = (timestamp >> 16) & 0xff;
  payload[10] = (timestamp >> 8) & 0xff;
  payload[11] = timestamp & 0xff;
  payload[12] = (paxCount >> 8) & 0xff;
  payload[13] = paxCount & 0xff;
  /* bytes 14-17 stay as zero */

  /* Compute checksum */
  let chk = 0;
  for (let k = 0; k < PAYLOAD_LENGTH - 1; k++) {
    chk += payload[k];
  }
  payload[PAYLOAD_LENGTH - 1] = chk & 0xff;

  /* XOR encrypt bytes 1-18 */
  for (let k = 0; k < PASSKEY_LENGTH; k++) {
    payload[k + 1] ^= passkey.charCodeAt(k);
  }

  return payload;
}

describe("MQTT Bridge XOR Decode", () => {
  it("correctly decodes a cash sale payload", () => {
    const payload = createTestPayload(0x05, TEST_PASSKEY, 150, 3, 0, TEST_TIMESTAMP);

    const result = xorDecode(payload, TEST_PASSKEY, TEST_TIMESTAMP);

    expect(result.valid).toBe(true);
    expect(result.itemPrice).toBe(150);
    expect(result.itemNumber).toBe(3);
  });

  it("correctly decodes a paxcounter payload", () => {
    const payload = createTestPayload(0x22, TEST_PASSKEY, 0, 0, 42, TEST_TIMESTAMP);

    const result = xorDecode(payload, TEST_PASSKEY, TEST_TIMESTAMP);

    expect(result.valid).toBe(true);
    expect(result.paxCount).toBe(42);
  });

  it("rejects payloads with wrong passkey", () => {
    const payload = createTestPayload(0x05, TEST_PASSKEY, 150, 1, 0, TEST_TIMESTAMP);

    const result = xorDecode(payload, "ABCDEFGHIJKLMNOPQR", TEST_TIMESTAMP);

    expect(result.valid).toBe(false);
  });

  it("rejects payloads with expired timestamp", () => {
    const payload = createTestPayload(0x05, TEST_PASSKEY, 150, 1, 0, TEST_TIMESTAMP);

    const result = xorDecode(payload, TEST_PASSKEY, TEST_TIMESTAMP + 20);

    expect(result.valid).toBe(false);
  });

  it("accepts payloads within the 8-second window", () => {
    const payload = createTestPayload(0x05, TEST_PASSKEY, 150, 1, 0, TEST_TIMESTAMP);

    const result = xorDecode(payload, TEST_PASSKEY, TEST_TIMESTAMP + 7);

    expect(result.valid).toBe(true);
  });

  it("rejects payloads with wrong length", () => {
    const shortPayload = Buffer.alloc(10);
    const result = xorDecode(shortPayload, TEST_PASSKEY, TEST_TIMESTAMP);
    expect(result.valid).toBe(false);
  });

  it("rejects payloads with corrupted data", () => {
    const payload = createTestPayload(0x05, TEST_PASSKEY, 150, 1, 0, TEST_TIMESTAMP);
    payload[3] ^= 0xff; // corrupt a byte
    const result = xorDecode(payload, TEST_PASSKEY, TEST_TIMESTAMP);
    expect(result.valid).toBe(false);
  });
});

describe("fromScaleFactor", () => {
  it("converts raw price to display price", () => {
    expect(fromScaleFactor(150, 1, 2)).toBe(1.5);
    expect(fromScaleFactor(100, 1, 2)).toBe(1.0);
    expect(fromScaleFactor(0, 1, 2)).toBe(0);
  });
});
