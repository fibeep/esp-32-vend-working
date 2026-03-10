/**
 * Comprehensive tests for the XOR encryption/decryption module.
 *
 * These tests verify that:
 *   1. xorEncode produces correctly structured payloads
 *   2. xorDecode correctly reverses the encryption
 *   3. Encode then decode is an identity operation (round-trip)
 *   4. Checksum validation catches corrupted payloads
 *   5. Timestamp validation rejects stale payloads
 *   6. Edge cases (zero price, max values, wrong passkey) are handled
 *   7. Scale factor conversions are correct
 */

import { describe, it, expect } from "vitest";
import {
  xorEncode,
  xorDecode,
  xorReEncrypt,
  toScaleFactor,
  fromScaleFactor,
} from "@/lib/crypto/xor";
import { PAYLOAD_LENGTH, PASSKEY_LENGTH, CMD } from "@/lib/constants";

/** A fixed 18-character passkey for deterministic testing. */
const TEST_PASSKEY = "abcdefghijklmnopqr";

/** A fixed timestamp for deterministic testing (Unix seconds). */
const TEST_TIMESTAMP = 1710000000;

/** A no-op random fill function for deterministic tests. */
const zeroFill = (buf: Uint8Array) => buf.fill(0);

describe("XOR Crypto Module", () => {
  describe("xorEncode", () => {
    it("produces a 19-byte payload", () => {
      const payload = xorEncode(
        CMD.CREDIT_PUSH,
        TEST_PASSKEY,
        150,
        1,
        0,
        TEST_TIMESTAMP,
        zeroFill
      );
      expect(payload.length).toBe(PAYLOAD_LENGTH);
    });

    it("sets the command byte at position 0 (unencrypted)", () => {
      const payload = xorEncode(
        CMD.CREDIT_PUSH,
        TEST_PASSKEY,
        150,
        1,
        0,
        TEST_TIMESTAMP,
        zeroFill
      );
      /* Command byte is NOT encrypted, so it should be the raw value */
      expect(payload[0]).toBe(CMD.CREDIT_PUSH);
    });

    it("encrypts bytes 1-18 with the passkey", () => {
      const payload = xorEncode(
        CMD.APPROVE_VEND,
        TEST_PASSKEY,
        150,
        5,
        0,
        TEST_TIMESTAMP,
        zeroFill
      );

      /* After encryption, decrypting should recover the original data */
      const decrypted = new Uint8Array(payload);
      for (let k = 0; k < PASSKEY_LENGTH; k++) {
        decrypted[k + 1] ^= TEST_PASSKEY.charCodeAt(k);
      }

      /* Byte 1 should be protocol version 0x01 */
      expect(decrypted[1]).toBe(0x01);
    });
  });

  describe("xorDecode", () => {
    it("correctly decodes a valid payload (round-trip)", () => {
      const itemPrice = 250; // $2.50 in scale-factor units
      const itemNumber = 7;
      const paxCount = 42;

      /* Encode with known parameters */
      const encrypted = xorEncode(
        CMD.VEND_REQUEST,
        TEST_PASSKEY,
        itemPrice,
        itemNumber,
        paxCount,
        TEST_TIMESTAMP,
        zeroFill
      );

      /* Decode with the same passkey and timestamp */
      const result = xorDecode(
        new Uint8Array(encrypted),
        TEST_PASSKEY,
        TEST_TIMESTAMP
      );

      expect(result.valid).toBe(true);
      expect(result.itemPrice).toBe(itemPrice);
      expect(result.itemNumber).toBe(itemNumber);
      expect(result.paxCount).toBe(paxCount);
    });

    it("works with random fill (non-deterministic bytes 14-17)", () => {
      const itemPrice = 100;
      const itemNumber = 3;

      const encrypted = xorEncode(
        CMD.VEND_REQUEST,
        TEST_PASSKEY,
        itemPrice,
        itemNumber,
        0,
        TEST_TIMESTAMP
        /* No zeroFill -- uses crypto.getRandomValues */
      );

      const result = xorDecode(
        new Uint8Array(encrypted),
        TEST_PASSKEY,
        TEST_TIMESTAMP
      );

      expect(result.valid).toBe(true);
      expect(result.itemPrice).toBe(itemPrice);
      expect(result.itemNumber).toBe(itemNumber);
    });

    it("rejects payloads with wrong passkey", () => {
      const encrypted = xorEncode(
        CMD.VEND_REQUEST,
        TEST_PASSKEY,
        150,
        1,
        0,
        TEST_TIMESTAMP,
        zeroFill
      );

      const wrongPasskey = "ABCDEFGHIJKLMNOPQR";
      const result = xorDecode(
        new Uint8Array(encrypted),
        wrongPasskey,
        TEST_TIMESTAMP
      );

      expect(result.valid).toBe(false);
    });

    it("rejects payloads with corrupted checksum", () => {
      const encrypted = xorEncode(
        CMD.VEND_REQUEST,
        TEST_PASSKEY,
        150,
        1,
        0,
        TEST_TIMESTAMP,
        zeroFill
      );

      /* Corrupt a byte in the encrypted payload */
      encrypted[5] ^= 0xff;

      const result = xorDecode(
        new Uint8Array(encrypted),
        TEST_PASSKEY,
        TEST_TIMESTAMP
      );

      expect(result.valid).toBe(false);
    });

    it("rejects payloads with expired timestamp", () => {
      const encrypted = xorEncode(
        CMD.VEND_REQUEST,
        TEST_PASSKEY,
        150,
        1,
        0,
        TEST_TIMESTAMP,
        zeroFill
      );

      /* Decode with a "current time" that's 10 seconds after the payload timestamp */
      const result = xorDecode(
        new Uint8Array(encrypted),
        TEST_PASSKEY,
        TEST_TIMESTAMP + 10 // 10 seconds after, exceeds 8-second window
      );

      expect(result.valid).toBe(false);
    });

    it("accepts payloads within the 8-second timestamp window", () => {
      const encrypted = xorEncode(
        CMD.VEND_REQUEST,
        TEST_PASSKEY,
        150,
        1,
        0,
        TEST_TIMESTAMP,
        zeroFill
      );

      /* Decode with a "current time" that's exactly 8 seconds after */
      const result = xorDecode(
        new Uint8Array(encrypted),
        TEST_PASSKEY,
        TEST_TIMESTAMP + 8
      );

      expect(result.valid).toBe(true);
    });

    it("rejects payloads with wrong length", () => {
      const shortPayload = new Uint8Array(10);
      const result = xorDecode(shortPayload, TEST_PASSKEY, TEST_TIMESTAMP);
      expect(result.valid).toBe(false);
    });

    it("rejects payloads with wrong passkey length", () => {
      const encrypted = xorEncode(
        CMD.VEND_REQUEST,
        TEST_PASSKEY,
        150,
        1,
        0,
        TEST_TIMESTAMP,
        zeroFill
      );

      const shortPasskey = "abc";
      const result = xorDecode(
        new Uint8Array(encrypted),
        shortPasskey,
        TEST_TIMESTAMP
      );

      expect(result.valid).toBe(false);
    });
  });

  describe("xorReEncrypt", () => {
    it("correctly re-encrypts with a new command byte (approve flow)", () => {
      const itemPrice = 350;
      const itemNumber = 12;

      /* Simulate the full credit/request flow:
         1. ESP32 encodes with VEND_REQUEST
         2. Backend decodes
         3. Backend re-encrypts with APPROVE_VEND
         4. Android sends back to ESP32
         5. ESP32 decodes and sees APPROVE_VEND */

      /* Step 1: Encode as if from ESP32 */
      const encrypted = xorEncode(
        CMD.VEND_REQUEST,
        TEST_PASSKEY,
        itemPrice,
        itemNumber,
        0,
        TEST_TIMESTAMP,
        zeroFill
      );

      /* Step 2: Backend decodes */
      const payload = new Uint8Array(encrypted);
      const decoded = xorDecode(payload, TEST_PASSKEY, TEST_TIMESTAMP);
      expect(decoded.valid).toBe(true);
      expect(decoded.itemPrice).toBe(itemPrice);

      /* Step 3: Backend re-encrypts with APPROVE_VEND */
      xorReEncrypt(payload, TEST_PASSKEY, CMD.APPROVE_VEND);

      /* Step 4-5: "ESP32" decodes the re-encrypted payload */
      const finalPayload = new Uint8Array(payload);
      /* Command byte should be APPROVE_VEND (not encrypted) */
      expect(finalPayload[0]).toBe(CMD.APPROVE_VEND);

      /* Decrypt bytes 1-18 */
      for (let k = 0; k < PASSKEY_LENGTH; k++) {
        finalPayload[k + 1] ^= TEST_PASSKEY.charCodeAt(k);
      }

      /* Verify checksum */
      let chk = 0;
      for (let k = 0; k < PAYLOAD_LENGTH - 1; k++) {
        chk += finalPayload[k];
      }
      chk &= 0xff;
      expect(chk).toBe(finalPayload[PAYLOAD_LENGTH - 1]);

      /* Verify the price and item number are preserved */
      const price =
        (finalPayload[2] << 24) |
        (finalPayload[3] << 16) |
        (finalPayload[4] << 8) |
        finalPayload[5];
      expect(price).toBe(itemPrice);

      const item = (finalPayload[6] << 8) | finalPayload[7];
      expect(item).toBe(itemNumber);
    });
  });

  describe("Scale factor conversions", () => {
    it("toScaleFactor converts dollars to raw units", () => {
      expect(toScaleFactor(1.5, 1, 2)).toBe(150);
      expect(toScaleFactor(0.25, 1, 2)).toBe(25);
      expect(toScaleFactor(10.0, 1, 2)).toBe(1000);
      expect(toScaleFactor(0, 1, 2)).toBe(0);
    });

    it("fromScaleFactor converts raw units to dollars", () => {
      expect(fromScaleFactor(150, 1, 2)).toBe(1.5);
      expect(fromScaleFactor(25, 1, 2)).toBe(0.25);
      expect(fromScaleFactor(1000, 1, 2)).toBe(10.0);
      expect(fromScaleFactor(0, 1, 2)).toBe(0);
    });

    it("round-trip conversion is identity", () => {
      const original = 3.75;
      const raw = toScaleFactor(original, 1, 2);
      const recovered = fromScaleFactor(raw, 1, 2);
      expect(recovered).toBe(original);
    });
  });

  describe("Edge cases", () => {
    it("handles zero price correctly", () => {
      const encrypted = xorEncode(
        CMD.VEND_REQUEST,
        TEST_PASSKEY,
        0,
        0,
        0,
        TEST_TIMESTAMP,
        zeroFill
      );
      const result = xorDecode(
        new Uint8Array(encrypted),
        TEST_PASSKEY,
        TEST_TIMESTAMP
      );
      expect(result.valid).toBe(true);
      expect(result.itemPrice).toBe(0);
      expect(result.itemNumber).toBe(0);
    });

    it("handles maximum uint16 item number (65535)", () => {
      const encrypted = xorEncode(
        CMD.VEND_REQUEST,
        TEST_PASSKEY,
        100,
        65535,
        0,
        TEST_TIMESTAMP,
        zeroFill
      );
      const result = xorDecode(
        new Uint8Array(encrypted),
        TEST_PASSKEY,
        TEST_TIMESTAMP
      );
      expect(result.valid).toBe(true);
      expect(result.itemNumber).toBe(65535);
    });

    it("handles maximum paxcounter value (65535)", () => {
      const encrypted = xorEncode(
        CMD.VEND_REQUEST,
        TEST_PASSKEY,
        100,
        1,
        65535,
        TEST_TIMESTAMP,
        zeroFill
      );
      const result = xorDecode(
        new Uint8Array(encrypted),
        TEST_PASSKEY,
        TEST_TIMESTAMP
      );
      expect(result.valid).toBe(true);
      expect(result.paxCount).toBe(65535);
    });

    it("handles large prices (uint32 range)", () => {
      const largePrice = 999999; // $9,999.99
      const encrypted = xorEncode(
        CMD.VEND_REQUEST,
        TEST_PASSKEY,
        largePrice,
        1,
        0,
        TEST_TIMESTAMP,
        zeroFill
      );
      const result = xorDecode(
        new Uint8Array(encrypted),
        TEST_PASSKEY,
        TEST_TIMESTAMP
      );
      expect(result.valid).toBe(true);
      expect(result.itemPrice).toBe(largePrice);
    });

    it("CREDIT_PUSH command (0x20) encodes correctly", () => {
      const encrypted = xorEncode(
        CMD.CREDIT_PUSH,
        TEST_PASSKEY,
        500,
        0,
        0,
        TEST_TIMESTAMP,
        zeroFill
      );

      expect(encrypted[0]).toBe(CMD.CREDIT_PUSH);

      const result = xorDecode(
        new Uint8Array(encrypted),
        TEST_PASSKEY,
        TEST_TIMESTAMP
      );
      expect(result.valid).toBe(true);
      expect(result.itemPrice).toBe(500);
    });

    it("PAX_COUNTER command (0x22) encodes correctly", () => {
      const encrypted = xorEncode(
        CMD.PAX_COUNTER,
        TEST_PASSKEY,
        0,
        0,
        127,
        TEST_TIMESTAMP,
        zeroFill
      );

      expect(encrypted[0]).toBe(CMD.PAX_COUNTER);

      const result = xorDecode(
        new Uint8Array(encrypted),
        TEST_PASSKEY,
        TEST_TIMESTAMP
      );
      expect(result.valid).toBe(true);
      expect(result.paxCount).toBe(127);
    });
  });
});
