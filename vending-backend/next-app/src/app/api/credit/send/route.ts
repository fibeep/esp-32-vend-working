/**
 * POST /api/credit/send
 *
 * Pushes credit to a vending machine remotely via MQTT.
 *
 * Ported from `docker/volumes/functions/send-credit/index.ts`.
 *
 * Flow:
 *   1. Verify JWT from Authorization header
 *   2. Parse body: { amount: number, subdomain: string }
 *   3. Lookup device passkey and status from Supabase `embedded` table
 *   4. Build an XOR-encrypted payload with cmd 0x20 (CREDIT_PUSH)
 *   5. Publish the encrypted payload to MQTT topic `{subdomain}.panamavendingmachines.com/credit`
 *   6. If the device is online, insert a sale record with channel "mqtt"
 *   7. Return { status, sales_id }
 *
 * The ESP32 firmware subscribes to `{subdomain}.panamavendingmachines.com/#` and matches
 * incoming messages on the `/credit` suffix. When it receives this payload,
 * it decrypts and initiates a vending session with the VMC.
 *
 * Request body:
 *   { amount: number, subdomain: string }
 *
 * Success response (200):
 *   { status: "online"|"offline", sales_id: string|null }
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
import { xorEncode, toScaleFactor, fromScaleFactor } from "@/lib/crypto/xor";
import { CMD, SCALE_FACTOR } from "@/lib/constants";
import { publishToMqtt } from "@/lib/mqtt/publisher";
import type { CreditSendBody } from "@/types/database";

export async function POST(request: NextRequest) {
  try {
    /* Step 1: Verify JWT */
    const auth = await verifyAuth(request);
    if (!auth.ok) return auth.data.error;

    /* Step 2: Parse request body */
    const body: CreditSendBody = await request.json();

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

    /* Step 3: Lookup device passkey and status */
    const supabase = createAuthClient(auth.data.authHeader);

    const { data: embeddedData, error: embeddedError } = await supabase
      .from("embedded")
      .select("passkey, subdomain, status, id")
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

    /* Step 6: Record sale only if the device is online */
    let salesId: string | null = null;

    if (device.status === "online") {
      const { data: saleData, error: saleError } = await supabase
        .from("sales")
        .insert([
          {
            embedded_id: device.id,
            item_price: fromScaleFactor(rawPrice, SCALE_FACTOR.factor, SCALE_FACTOR.decimals),
            channel: "mqtt",
          },
        ])
        .select("id")
        .single();

      if (!saleError && saleData) {
        salesId = saleData.id;
      }
    }

    /* Step 7: Return response */
    return NextResponse.json({
      status: device.status,
      sales_id: salesId,
    });
  } catch (err) {
    return NextResponse.json(
      { error: err instanceof Error ? err.message : "Internal server error" },
      { status: 500 }
    );
  }
}
