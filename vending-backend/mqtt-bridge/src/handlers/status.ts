/**
 * MQTT status message handler.
 *
 * Processes messages on the `domain.panamavendingmachines.com/{subdomain}/status` topic.
 * The ESP32 firmware publishes "online" when it connects to the MQTT broker
 * and configures "offline" as the LWT (Last Will and Testament) message,
 * which the broker publishes automatically when the device disconnects.
 *
 * Ported from the Python `on_message` handler in `docker/mqtt/domain/mqtt_domain.py`.
 */

import { SupabaseClient } from "@supabase/supabase-js";

/**
 * Updates the device's online/offline status in the database.
 *
 * @param supabase  - Service-role Supabase client (bypasses RLS)
 * @param subdomain - The device subdomain extracted from the MQTT topic
 * @param rawPayload - The raw message payload (UTF-8 string: "online" or "offline")
 */
export async function handleStatus(
  supabase: SupabaseClient,
  subdomain: string,
  rawPayload: Buffer
): Promise<void> {
  const status = rawPayload.toString("utf-8").trim();

  /* Only accept valid status values */
  if (status !== "online" && status !== "offline") {
    console.error(`[status] Invalid status value for subdomain ${subdomain}: "${status}"`);
    return;
  }

  const { error } = await supabase
    .from("embedded")
    .update({
      status,
      status_at: new Date().toISOString(),
    })
    .eq("subdomain", subdomain);

  if (error) {
    console.error(`[status] Failed to update status for subdomain ${subdomain}:`, error.message);
    return;
  }

  console.log(`[status] Device ${subdomain} is now ${status}`);
}
