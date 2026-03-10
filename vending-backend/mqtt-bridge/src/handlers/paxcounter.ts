/**
 * MQTT paxcounter message handler.
 *
 * Processes messages on the `domain.panamavendingmachines.com/{subdomain}/paxcounter` topic.
 * The ESP32 firmware periodically scans for nearby BLE devices (phones,
 * wearables, etc.) and publishes the count as a foot traffic metric.
 *
 * Ported from the Python `on_message` handler in `docker/mqtt/domain/mqtt_domain.py`.
 *
 * Flow:
 *   1. Lookup the device by subdomain to get its passkey and machine_id
 *   2. XOR decrypt the 19-byte payload
 *   3. Validate checksum and timestamp
 *   4. Extract the paxcounter value from bytes 12-13
 *   5. Insert a metric record with name "paxcounter"
 */

import { SupabaseClient } from "@supabase/supabase-js";
import { xorDecode } from "../crypto/xor";

/**
 * Handles a paxcounter metric message from an ESP32 device.
 *
 * @param supabase   - Service-role Supabase client (bypasses RLS)
 * @param subdomain  - The device subdomain extracted from the MQTT topic
 * @param rawPayload - The raw 19-byte MQTT message payload
 */
export async function handlePaxcounter(
  supabase: SupabaseClient,
  subdomain: string,
  rawPayload: Buffer
): Promise<void> {
  /* Step 1: Lookup device */
  const { data: embeddedData, error: lookupError } = await supabase
    .from("embedded")
    .select("passkey, subdomain, id, machine_id")
    .eq("subdomain", subdomain);

  if (lookupError || !embeddedData || embeddedData.length === 0) {
    console.error(`[paxcounter] Device not found for subdomain ${subdomain}:`, lookupError?.message);
    return;
  }

  const device = embeddedData[0];

  /* Step 2-3: Decrypt and validate */
  const payload = Buffer.from(rawPayload);
  const decoded = xorDecode(payload, device.passkey);

  if (!decoded.valid) {
    console.error(`[paxcounter] Invalid payload for subdomain ${subdomain}: checksum or timestamp failed`);
    return;
  }

  /* Step 4-5: Extract paxcounter and insert metric */
  const { paxCount } = decoded;

  const { error: insertError } = await supabase.from("metrics").insert([
    {
      embedded_id: device.id,
      machine_id: device.machine_id,
      name: "paxcounter",
      value: paxCount,
    },
  ]);

  if (insertError) {
    console.error(`[paxcounter] Failed to insert metric for subdomain ${subdomain}:`, insertError.message);
    return;
  }

  console.log(`[paxcounter] Recorded: subdomain=${subdomain} count=${paxCount}`);
}
