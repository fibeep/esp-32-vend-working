/**
 * POST /api/credit/mqtt-push
 *
 * Pushes credit to a vending machine via MQTT **without** recording a sale.
 *
 * This endpoint is used by the Yappy payment Edge Function when a Yappy
 * payment is confirmed (PAGADO). The sale is already recorded by the Edge
 * Function with channel "yappy", so this endpoint only handles the MQTT
 * credit push to the ESP32.
 *
 * Flow:
 *   1. Verify JWT from Authorization header
 *   2. Parse body: { amount: number, subdomain: string }
 *   3. Lookup device passkey from Supabase `embedded` table
 *   4. Build an XOR-encrypted payload with cmd 0x20 (CREDIT_PUSH)
 *   5. Publish the encrypted payload to MQTT topic `{subdomain}.panamavendingmachines.com/credit`
 *   6. Return { status: "pushed" }
 *
 * The ESP32 receives this credit push, starts a new MDB cashless session,
 * and auto-approves the vend when the customer re-selects their product.
 *
 * Request body:
 *   { amount: number, subdomain: string }
 *
 * Success response (200):
 *   { status: "pushed" }
 *
 * Error responses:
 *   400 - Missing required fields
 *   401 - Missing/invalid JWT
 *   404 - Device not found
 *   500 - Server/MQTT error
 */

import { NextRequest, NextResponse } from "next/server";
import { verifyAuth } from "@/lib/supabase/middleware";
import { createAuthClient } from "@/lib/supabase/client";
import { xorEncode, toScaleFactor } from "@/lib/crypto/xor";
import { CMD, SCALE_FACTOR } from "@/lib/constants";
import { publishToMqtt } from "@/lib/mqtt/publisher";

interface MqttPushBody {
  amount: number;
  subdomain: string;
}

export async function POST(request: NextRequest) {
  try {
    /* Step 1: Verify JWT */
    const auth = await verifyAuth(request);
    if (!auth.ok) return auth.data.error;

    /* Step 2: Parse request body */
    const body: MqttPushBody = await request.json();

    if (!body.amount || !body.subdomain) {
      return NextResponse.json(
        { error: "amount and subdomain are required" },
        { status: 400 }
      );
    }

    if (body.amount <= 0) {
      return NextResponse.json(
        { error: "amount must be a positive number" },
        { status: 400 }
      );
    }

    /* Step 3: Lookup device passkey */
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

    /* Step 4: Build XOR-encrypted payload */
    const rawPrice = toScaleFactor(
      body.amount,
      SCALE_FACTOR.factor,
      SCALE_FACTOR.decimals
    );

    const encryptedPayload = xorEncode(
      CMD.CREDIT_PUSH,   // 0x20
      device.passkey,
      rawPrice,
      0                  // itemNumber = 0 for credit push
    );

    /* Step 5: Publish to MQTT */
    const topic = `${device.subdomain}.panamavendingmachines.com/credit`;
    await publishToMqtt(topic, encryptedPayload);

    console.log(`[mqtt-push] Credit pushed to ${device.subdomain}: $${body.amount}`);

    /* Step 6: Return success (no sale recording — caller handles that) */
    return NextResponse.json({ status: "pushed" });
  } catch (err) {
    console.error(`[mqtt-push] Error: ${err instanceof Error ? err.message : String(err)}`);
    return NextResponse.json(
      { error: err instanceof Error ? err.message : "Internal server error" },
      { status: 500 }
    );
  }
}
