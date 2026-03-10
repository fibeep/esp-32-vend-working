/**
 * MQTT sale message handler.
 *
 * Processes messages on the `domain.panamavendingmachines.com/{subdomain}/sale` topic.
 * These are cash/card sale events reported by the ESP32 firmware after
 * a VMC VEND_SUCCESS MDB command.
 *
 * Ported from the Python `on_message` handler in `docker/mqtt/domain/mqtt_domain.py`.
 *
 * Flow:
 *   1. Lookup the device by subdomain to get its passkey and owner_id
 *   2. XOR decrypt the 19-byte payload
 *   3. Validate checksum and timestamp
 *   4. Extract itemPrice and itemNumber
 *   5. Insert a sale record with channel "cash"
 */

import { SupabaseClient } from "@supabase/supabase-js";
import { xorDecode, fromScaleFactor } from "../crypto/xor";

/**
 * Handles a cash sale message from an ESP32 device.
 *
 * @param supabase  - Service-role Supabase client (bypasses RLS)
 * @param subdomain - The device subdomain extracted from the MQTT topic
 * @param rawPayload - The raw 19-byte MQTT message payload
 */
export async function handleSale(
  supabase: SupabaseClient,
  subdomain: string,
  rawPayload: Buffer
): Promise<void> {
  /* Step 1: Lookup device */
  const { data: embeddedData, error: lookupError } = await supabase
    .from("embedded")
    .select("passkey, subdomain, id, owner_id")
    .eq("subdomain", subdomain);

  if (lookupError || !embeddedData || embeddedData.length === 0) {
    console.error(`[sale] Device not found for subdomain ${subdomain}:`, lookupError?.message);
    return;
  }

  const device = embeddedData[0];

  /* Step 2-3: Decrypt and validate */
  const payload = Buffer.from(rawPayload);
  const decoded = xorDecode(payload, device.passkey);

  if (!decoded.valid) {
    console.error(`[sale] Invalid payload for subdomain ${subdomain}: checksum or timestamp failed`);
    return;
  }

  /* Step 4-5: Extract fields and insert sale */
  const { itemPrice, itemNumber } = decoded;

  const { error: insertError } = await supabase.from("sales").insert([
    {
      owner_id: device.owner_id,
      embedded_id: device.id,
      item_number: itemNumber,
      item_price: fromScaleFactor(itemPrice, 1, 2),
      channel: "cash",
    },
  ]);

  if (insertError) {
    console.error(`[sale] Failed to insert sale for subdomain ${subdomain}:`, insertError.message);
    return;
  }

  console.log(
    `[sale] Recorded cash sale: subdomain=${subdomain} item=${itemNumber} price=$${fromScaleFactor(itemPrice, 1, 2).toFixed(2)}`
  );
}
