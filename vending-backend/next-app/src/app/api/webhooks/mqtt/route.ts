/**
 * POST /api/webhooks/mqtt
 *
 * Internal webhook endpoint called by the MQTT bridge service when it
 * receives messages from the Mosquitto broker. This provides an HTTP
 * interface for the bridge to forward processed MQTT events to the
 * Next.js application.
 *
 * The bridge sends pre-processed data (already decrypted and validated)
 * so this endpoint simply inserts records into Supabase.
 *
 * Security: This endpoint is only accessible within the Docker network.
 * It validates a shared secret from the `MQTT_WEBHOOK_SECRET` environment
 * variable to prevent unauthorized access.
 *
 * Request body:
 *   {
 *     event: "sale" | "status" | "paxcounter",
 *     subdomain: string,
 *     data: {
 *       // For "sale": { item_price, item_number, owner_id }
 *       // For "status": { status: "online" | "offline" }
 *       // For "paxcounter": { value, embedded_id, machine_id? }
 *     }
 *   }
 *
 * Headers:
 *   X-Webhook-Secret: <MQTT_WEBHOOK_SECRET>
 *
 * Response (200): { success: true }
 * Response (401): { error: "Unauthorized" }
 * Response (400): { error: "..." }
 */

import { NextRequest, NextResponse } from "next/server";
import { createServiceClient } from "@/lib/supabase/client";

export async function POST(request: NextRequest) {
  try {
    /* Validate webhook secret */
    const secret = request.headers.get("X-Webhook-Secret");
    const expectedSecret = process.env.MQTT_WEBHOOK_SECRET;

    if (!expectedSecret || secret !== expectedSecret) {
      return NextResponse.json(
        { error: "Unauthorized" },
        { status: 401 }
      );
    }

    const body = await request.json();
    const { event, subdomain, data } = body;

    if (!event || !subdomain) {
      return NextResponse.json(
        { error: "event and subdomain are required" },
        { status: 400 }
      );
    }

    /* Use the service-role client to bypass RLS for backend-to-backend operations */
    const supabase = createServiceClient();

    switch (event) {
      case "sale": {
        /* Insert a cash sale record */
        const { error } = await supabase.from("sales").insert([
          {
            embedded_id: data.embedded_id,
            item_number: data.item_number,
            item_price: data.item_price,
            channel: "cash",
            owner_id: data.owner_id,
          },
        ]);
        if (error) {
          return NextResponse.json({ error: error.message }, { status: 500 });
        }
        break;
      }

      case "status": {
        /* Update device online/offline status */
        const { error } = await supabase
          .from("embedded")
          .update({
            status: data.status,
            status_at: new Date().toISOString(),
          })
          .eq("subdomain", subdomain);
        if (error) {
          return NextResponse.json({ error: error.message }, { status: 500 });
        }
        break;
      }

      case "paxcounter": {
        /* Insert paxcounter metric */
        const { error } = await supabase.from("metrics").insert([
          {
            embedded_id: data.embedded_id,
            machine_id: data.machine_id ?? null,
            name: "paxcounter",
            value: data.value,
          },
        ]);
        if (error) {
          return NextResponse.json({ error: error.message }, { status: 500 });
        }
        break;
      }

      default:
        return NextResponse.json(
          { error: `Unknown event type: ${event}` },
          { status: 400 }
        );
    }

    return NextResponse.json({ success: true });
  } catch (err) {
    return NextResponse.json(
      { error: err instanceof Error ? err.message : "Internal server error" },
      { status: 500 }
    );
  }
}
