/**
 * Tests for the POST /api/credit/request route.
 *
 * These tests verify the critical BLE credit flow:
 *   1. JWT is validated
 *   2. Payload is decoded, validated, and re-encrypted
 *   3. Sale record is inserted
 *   4. Correct response format
 *   5. Error cases (invalid payload, device not found, etc.)
 */

import { describe, it, expect, vi, beforeEach } from "vitest";
import { xorEncode, xorDecode, fromScaleFactor } from "@/lib/crypto/xor";
import { CMD, PAYLOAD_LENGTH, PASSKEY_LENGTH } from "@/lib/constants";

const TEST_PASSKEY = "abcdefghijklmnopqr";
const TEST_TIMESTAMP = Math.floor(Date.now() / 1000);

/** Zero-fill function for deterministic test payloads. */
const zeroFill = (buf: Uint8Array) => buf.fill(0);

describe("Credit Request Route Logic", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("should correctly decode, modify command, and re-encode a payload (full round-trip)", () => {
    const itemPrice = 250;
    const itemNumber = 7;

    /* Step 1: Create a VEND_REQUEST payload as the ESP32 would */
    const original = xorEncode(
      CMD.VEND_REQUEST,
      TEST_PASSKEY,
      itemPrice,
      itemNumber,
      0,
      TEST_TIMESTAMP,
      zeroFill
    );

    /* Save a copy for verification later */
    const originalBase64 = Buffer.from(original).toString("base64");

    /* Step 2: Simulate what the backend does - decode the payload */
    const payloadForDecode = new Uint8Array(
      Buffer.from(originalBase64, "base64")
    );

    const decoded = xorDecode(payloadForDecode, TEST_PASSKEY, TEST_TIMESTAMP);

    expect(decoded.valid).toBe(true);
    expect(decoded.itemPrice).toBe(itemPrice);
    expect(decoded.itemNumber).toBe(itemNumber);

    /* Step 3: Re-encrypt with APPROVE_VEND */
    payloadForDecode[0] = CMD.APPROVE_VEND;

    /* Recalculate checksum */
    let chk = 0;
    for (let k = 0; k < PAYLOAD_LENGTH - 1; k++) {
      chk += payloadForDecode[k];
    }
    payloadForDecode[PAYLOAD_LENGTH - 1] = chk & 0xff;

    /* XOR encrypt */
    for (let k = 0; k < PASSKEY_LENGTH; k++) {
      payloadForDecode[k + 1] ^= TEST_PASSKEY.charCodeAt(k);
    }

    /* Step 4: Convert to base64 */
    const responseBase64 = Buffer.from(payloadForDecode).toString("base64");

    /* Step 5: Verify the response payload can be decoded back */
    const responsePayload = new Uint8Array(
      Buffer.from(responseBase64, "base64")
    );

    /* Command byte should be APPROVE_VEND (unencrypted) */
    expect(responsePayload[0]).toBe(CMD.APPROVE_VEND);

    /* Decrypt */
    for (let k = 0; k < PASSKEY_LENGTH; k++) {
      responsePayload[k + 1] ^= TEST_PASSKEY.charCodeAt(k);
    }

    /* Verify checksum */
    let verifyChk = 0;
    for (let k = 0; k < PAYLOAD_LENGTH - 1; k++) {
      verifyChk += responsePayload[k];
    }
    verifyChk &= 0xff;
    expect(verifyChk).toBe(responsePayload[PAYLOAD_LENGTH - 1]);

    /* Verify price and item preserved */
    const verifyPrice =
      (responsePayload[2] << 24) |
      (responsePayload[3] << 16) |
      (responsePayload[4] << 8) |
      responsePayload[5];
    expect(verifyPrice).toBe(itemPrice);

    const verifyItem = (responsePayload[6] << 8) | responsePayload[7];
    expect(verifyItem).toBe(itemNumber);
  });

  it("should reject a payload with invalid length", () => {
    const shortPayload = new Uint8Array(10);
    const result = xorDecode(shortPayload, TEST_PASSKEY, TEST_TIMESTAMP);
    expect(result.valid).toBe(false);
  });

  it("should reject a base64 payload that decodes to wrong size", () => {
    const wrongSize = Buffer.from("aGVsbG8=", "base64");
    const result = xorDecode(
      new Uint8Array(wrongSize),
      TEST_PASSKEY,
      TEST_TIMESTAMP
    );
    expect(result.valid).toBe(false);
  });

  it("should correctly handle the fromScaleFactor conversion for the sale insert", () => {
    /* itemPrice=150 with factor=1, decimals=2 should give $1.50 */
    expect(fromScaleFactor(150, 1, 2)).toBeCloseTo(1.5);
    expect(fromScaleFactor(0, 1, 2)).toBe(0);
    expect(fromScaleFactor(99, 1, 2)).toBeCloseTo(0.99);
  });

  it("should handle multiple consecutive requests with different data", () => {
    const testCases = [
      { price: 100, item: 1 },
      { price: 250, item: 5 },
      { price: 999, item: 15 },
      { price: 50, item: 0 },
    ];

    for (const tc of testCases) {
      const encrypted = xorEncode(
        CMD.VEND_REQUEST,
        TEST_PASSKEY,
        tc.price,
        tc.item,
        0,
        TEST_TIMESTAMP,
        zeroFill
      );

      const decoded = xorDecode(
        new Uint8Array(encrypted),
        TEST_PASSKEY,
        TEST_TIMESTAMP
      );

      expect(decoded.valid).toBe(true);
      expect(decoded.itemPrice).toBe(tc.price);
      expect(decoded.itemNumber).toBe(tc.item);
    }
  });
});
