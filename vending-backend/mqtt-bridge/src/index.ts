/**
 * MQTT Bridge Service -- Main Entry Point
 *
 * This long-running Node.js process connects to the local Mosquitto broker
 * and subscribes to all device topics. When messages arrive, it delegates
 * to the appropriate handler based on the topic suffix.
 *
 * Ported from the Python implementation in `docker/mqtt/domain/mqtt_domain.py`.
 *
 * Subscribed topic pattern: `domain.panamavendingmachines.com/+/#`
 *
 * This matches:
 *   - domain.panamavendingmachines.com/{subdomain}/sale
 *   - domain.panamavendingmachines.com/{subdomain}/status
 *   - domain.panamavendingmachines.com/{subdomain}/paxcounter
 *   - domain.panamavendingmachines.com/{subdomain}/dex
 *
 * The bridge uses the **service-role key** to bypass Supabase RLS because
 * it needs to insert records on behalf of any device owner.
 */

import mqtt from "mqtt";
import { createClient } from "@supabase/supabase-js";
import { handleSale } from "./handlers/sale";
import { handleStatus } from "./handlers/status";
import { handlePaxcounter } from "./handlers/paxcounter";

/** MQTT broker URL -- points to the sibling Mosquitto container */
const MQTT_HOST = process.env.MQTT_HOST || "mosquitto";
const MQTT_PORT = parseInt(process.env.MQTT_PORT || "1883", 10);
const MQTT_URL = `mqtt://${MQTT_HOST}:${MQTT_PORT}`;

/** Supabase configuration -- requires service-role key for RLS bypass */
const SUPABASE_URL = process.env.SUPABASE_URL;
const SUPABASE_SERVICE_ROLE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY;

/** Wildcard subscription that captures all device messages */
const SUBSCRIBE_TOPIC = "domain.panamavendingmachines.com/+/#";

/**
 * Regular expression to parse device MQTT topics.
 *
 * Captures:
 *   group 1: subdomain (numeric string, e.g. "123456")
 *   group 2: event type ("sale", "status", "paxcounter", "dex")
 */
const TOPIC_REGEX = /^domain\.panamavendingmachines\.com\/(\d+)\/(sale|status|paxcounter|dex)$/;

/**
 * Initializes and starts the MQTT bridge.
 *
 * - Connects to Mosquitto
 * - Subscribes to the wildcard topic
 * - Routes incoming messages to handlers
 */
function start(): void {
  if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
    console.error("Missing SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY environment variables");
    process.exit(1);
  }

  console.log(`[bridge] Supabase URL: ${SUPABASE_URL}`);
  console.log(`[bridge] Connecting to MQTT broker at ${MQTT_URL}...`);

  /** Create a single Supabase client for the lifetime of the bridge process */
  const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);

  /** Connect to the MQTT broker */
  const client = mqtt.connect(MQTT_URL, {
    reconnectPeriod: 5000,
    connectTimeout: 10000,
  });

  /** Handle successful connection */
  client.on("connect", () => {
    console.log("[bridge] Connected to MQTT broker");
    client.subscribe(SUBSCRIBE_TOPIC, (err) => {
      if (err) {
        console.error("[bridge] Subscription failed:", err.message);
      } else {
        console.log(`[bridge] Subscribed to: ${SUBSCRIBE_TOPIC}`);
      }
    });
  });

  /** Handle incoming messages */
  client.on("message", (topic: string, payload: Buffer) => {
    try {
      const match = topic.match(TOPIC_REGEX);
      if (!match) {
        /* Topic doesn't match our expected pattern -- ignore silently */
        return;
      }

      const subdomain = match[1];
      const eventType = match[2];

      switch (eventType) {
        case "sale":
          handleSale(supabase, subdomain, payload);
          break;

        case "status":
          handleStatus(supabase, subdomain, payload);
          break;

        case "paxcounter":
          handlePaxcounter(supabase, subdomain, payload);
          break;

        case "dex":
          /* DEX/DDCMP telemetry -- not yet implemented */
          console.log(`[bridge] DEX data received from subdomain ${subdomain} (${payload.length} bytes)`);
          break;

        default:
          console.log(`[bridge] Unknown event type: ${eventType}`);
      }
    } catch (err) {
      console.error("[bridge] Error processing message:", err);
    }
  });

  /** Handle connection errors */
  client.on("error", (err) => {
    console.error("[bridge] MQTT error:", err.message);
  });

  /** Handle disconnections */
  client.on("close", () => {
    console.log("[bridge] Disconnected from MQTT broker, will attempt to reconnect...");
  });

  /** Handle reconnection */
  client.on("reconnect", () => {
    console.log("[bridge] Attempting to reconnect to MQTT broker...");
  });
}

/* Start the bridge */
start();
