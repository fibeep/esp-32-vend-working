/**
 * POST /api/credit/request
 *
 * **CRITICAL PATH** -- This is the core BLE credit flow endpoint.
 *
 * Called by the Android app after the ESP32 sends a VEND_REQUEST BLE
 * notification (cmd 0x0A). The app forwards the XOR-encrypted payload
 * to this endpoint for validation and sale recording.
 *
 * Flow (ported byte-for-byte from `docker/volumes/functions/request-credit/index.ts`):
 *   1. Verify JWT from Authorization header
 *   2. Parse body: { payload: base64, subdomain, lat?, lng? }
 *   3. Lookup device passkey from Supabase `embedded` table
 *   4. XOR decrypt the 19-byte payload with the passkey
 *   5. Validate checksum (sum of bytes 0..17 mod 256 === byte 18)
 *   6. Extract itemPrice (bytes 2-5, big-endian u32) and itemNumber (bytes 6-7, big-endian u16)
 *   7. Insert a sale record with channel "ble"
 *   8. Set payload[0] = 0x03 (APPROVE_VEND), recalculate checksum, re-encrypt
 *   9. Return { payload: base64, sales_id }
 *
 * The returned payload is written back to the ESP32 BLE characteristic
 * by the Android app to approve the pending vend.
 *
 * Request body:
 *   { payload: string (base64), subdomain: string, lat?: number, lng?: number }
 *
 * Success response (200):
 *   { payload: string (base64), sales_id: string (UUID) }
 *
 * Error responses:
 *   400 - Invalid payload or checksum failure
 *   401 - Missing/invalid JWT
 *   404 - Device not found
 *   500 - Server error
 */

import { NextRequest, NextResponse } from "next/server";
import { verifyAuth } from "@/lib/supabase/middleware";
import { createAuthClient } from "@/lib/supabase/client";
import { xorDecode, xorReEncrypt, fromScaleFactor } from "@/lib/crypto/xor";
import { CMD, SCALE_FACTOR, PAYLOAD_LENGTH } from "@/lib/constants";
import type { CreditRequestBody } from "@/types/database";

export async function POST(request: NextRequest) {
  try {
    /* Step 1: Verify JWT */
    const auth = await verifyAuth(request);
    if (!auth.ok) return auth.data.error;

    /* Step 2: Parse request body */
    const body: CreditRequestBody = await request.json();

    if (!body.payload || !body.subdomain) {
      return NextResponse.json(
        { error: "payload and subdomain are required" },
        { status: 400 }
      );
    }

    /* Base64 decode the payload into a mutable Uint8Array */
    const payloadBytes = new Uint8Array(
      Buffer.from(body.payload, "base64")
    );

    if (payloadBytes.length !== PAYLOAD_LENGTH) {
      return NextResponse.json(
        { error: `Payload must be exactly ${PAYLOAD_LENGTH} bytes` },
        { status: 400 }
      );
    }

    /* Step 3: Lookup device passkey from the embedded table */
    const supabase = createAuthClient(auth.data.authHeader);

    const { data: embeddedData, error: embeddedError } = await supabase
      .from("embedded")
      .select("passkey, subdomain, id")
      .eq("subdomain", body.subdomain);

    if (embeddedError || !embeddedData || embeddedData.length === 0) {
      return NextResponse.json(
        { error: "Device not found" },
        { status: 404 }
      );
    }

    const device = embeddedData[0];
    const passkey: string = device.passkey;

    /* Step 4: XOR decrypt the payload (mutates payloadBytes in place) */
    /* Step 5: Validate checksum */
    const decoded = xorDecode(payloadBytes, passkey);

    if (!decoded.valid) {
      return NextResponse.json(
        { error: "Invalid payload: checksum or timestamp validation failed" },
        { status: 400 }
      );
    }

    /* Step 6: Extract itemPrice and itemNumber (already done by xorDecode) */
    const { itemPrice, itemNumber } = decoded;

    /* Step 7: Insert sale record */
    const { data: saleData, error: saleError } = await supabase
      .from("sales")
      .insert([
        {
          embedded_id: device.id,
          item_number: itemNumber,
          item_price: fromScaleFactor(itemPrice, SCALE_FACTOR.factor, SCALE_FACTOR.decimals),
          channel: "ble",
          lat: body.lat !== undefined ? body.lat : null,
          lng: body.lng !== undefined ? body.lng : null,
        },
      ])
      .select("id")
      .single();

    if (saleError || !saleData) {
      return NextResponse.json(
        { error: saleError?.message ?? "Failed to record sale" },
        { status: 500 }
      );
    }

    /* Step 8: Re-encrypt the payload with APPROVE_VEND command (0x03) */
    xorReEncrypt(payloadBytes, passkey, CMD.APPROVE_VEND);

    /* Step 9: Return the re-encrypted payload as base64 */
    const responsePayload = Buffer.from(payloadBytes).toString("base64");

    return NextResponse.json({
      payload: responsePayload,
      sales_id: saleData.id,
    });
  } catch (err) {
    return NextResponse.json(
      { error: err instanceof Error ? err.message : "Internal server error" },
      { status: 500 }
    );
  }
}
