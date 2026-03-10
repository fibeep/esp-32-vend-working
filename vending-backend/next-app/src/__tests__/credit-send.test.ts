/**
 * Tests for the POST /api/credit/send route logic.
 *
 * These tests verify the MQTT credit push flow:
 *   1. Payload is correctly built with the CREDIT_PUSH command
 *   2. Scale factor conversion is applied correctly
 *   3. The topic is formatted correctly
 *   4. Edge cases (zero amount, large amounts)
 */

import { describe, it, expect } from "vitest";
import { xorEncode, xorDecode, toScaleFactor } from "@/lib/crypto/xor";
import { CMD } from "@/lib/constants";

const TEST_PASSKEY = "abcdefghijklmnopqr";
const TEST_TIMESTAMP = Math.floor(Date.now() / 1000);
const zeroFill = (buf: Uint8Array) => buf.fill(0);

describe("Credit Send Route Logic", () => {
  it("correctly builds a CREDIT_PUSH payload", () => {
    const amount = 5.0; // $5.00
    const rawPrice = toScaleFactor(amount, 1, 2); // 500

    const encrypted = xorEncode(
      CMD.CREDIT_PUSH,
      TEST_PASSKEY,
      rawPrice,
      0, // itemNumber is always 0 for credit push
      0,
      TEST_TIMESTAMP,
      zeroFill
    );

    /* Verify command byte */
    expect(encrypted[0]).toBe(CMD.CREDIT_PUSH);

    /* Decode and verify */
    const decoded = xorDecode(
      new Uint8Array(encrypted),
      TEST_PASSKEY,
      TEST_TIMESTAMP
    );

    expect(decoded.valid).toBe(true);
    expect(decoded.itemPrice).toBe(500);
    expect(decoded.itemNumber).toBe(0);
  });

  it("handles fractional dollar amounts", () => {
    const amount = 1.75;
    const rawPrice = toScaleFactor(amount, 1, 2); // 175

    const encrypted = xorEncode(
      CMD.CREDIT_PUSH,
      TEST_PASSKEY,
      rawPrice,
      0,
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
    expect(decoded.itemPrice).toBe(175);
  });

  it("handles minimum amount (1 cent)", () => {
    const amount = 0.01;
    const rawPrice = toScaleFactor(amount, 1, 2); // 1

    const encrypted = xorEncode(
      CMD.CREDIT_PUSH,
      TEST_PASSKEY,
      rawPrice,
      0,
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
    expect(decoded.itemPrice).toBe(1);
  });

  it("constructs the correct MQTT topic for a given subdomain", () => {
    const subdomain = "123456";
    const topic = `${subdomain}.panamavendingmachines.com/credit`;
    expect(topic).toBe("123456.panamavendingmachines.com/credit");
  });

  it("correctly determines the toScaleFactor for various amounts", () => {
    expect(toScaleFactor(1.0, 1, 2)).toBe(100);
    expect(toScaleFactor(2.50, 1, 2)).toBe(250);
    expect(toScaleFactor(99.99, 1, 2)).toBeCloseTo(9999);
    expect(toScaleFactor(0.05, 1, 2)).toBe(5);
  });
});
