import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

// ── Constants ────────────────────────────────────────────────
const PAYLOAD_LENGTH = 19;
const PASSKEY_LENGTH = 18;
const TIMESTAMP_WINDOW_SECONDS = 8;
const CMD_APPROVE_VEND = 0x03;
const SCALE_FACTOR = 1;
const SCALE_DECIMALS = 2;

// ── XOR helpers (byte-identical to ESP32 firmware) ───────────────────

interface XorDecodeResult {
  itemPrice: number;
  itemNumber: number;
  paxCount: number;
  valid: boolean;
}

function xorDecode(
  payload: Uint8Array,
  passkey: string,
  nowSeconds?: number
): XorDecodeResult {
  const invalid: XorDecodeResult = { itemPrice: 0, itemNumber: 0, paxCount: 0, valid: false };

  if (payload.length !== PAYLOAD_LENGTH) return invalid;
  if (passkey.length !== PASSKEY_LENGTH) return invalid;

  // XOR decrypt bytes 1-18
  for (let k = 0; k < PASSKEY_LENGTH; k++) {
    payload[k + 1] ^= passkey.charCodeAt(k);
  }

  // Validate checksum
  let chk = 0;
  for (let k = 0; k < PAYLOAD_LENGTH - 1; k++) {
    chk += payload[k];
  }
  chk &= 0xff;
  if (chk !== payload[PAYLOAD_LENGTH - 1]) return invalid;

  // Validate timestamp
  const timestamp =
    (payload[8] << 24) | (payload[9] << 16) | (payload[10] << 8) | payload[11];
  const now = nowSeconds ?? Math.floor(Date.now() / 1000);
  if (Math.abs(now - timestamp) > TIMESTAMP_WINDOW_SECONDS) return invalid;

  // Extract fields
  const itemPrice =
    (payload[2] << 24) | (payload[3] << 16) | (payload[4] << 8) | payload[5];
  const itemNumber = (payload[6] << 8) | payload[7];
  const paxCount = (payload[12] << 8) | payload[13];

  return { itemPrice, itemNumber, paxCount, valid: true };
}

function xorReEncrypt(
  payload: Uint8Array,
  passkey: string,
  newCmd: number
): void {
  payload[0] = newCmd;

  let chk = 0;
  for (let k = 0; k < PAYLOAD_LENGTH - 1; k++) {
    chk += payload[k];
  }
  payload[PAYLOAD_LENGTH - 1] = chk & 0xff;

  for (let k = 0; k < PASSKEY_LENGTH; k++) {
    payload[k + 1] ^= passkey.charCodeAt(k);
  }
}

function fromScaleFactor(raw: number, factor: number, decimals: number): number {
  return raw * factor * Math.pow(10, -decimals);
}

// ── Base64 helpers (Deno-compatible) ────────────────────────────────

function base64Decode(b64: string): Uint8Array {
  const binary = atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

function base64Encode(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}

// ── CORS headers ────────────────────────────────────────────────
const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

// ── Main handler ────────────────────────────────────────────────

Deno.serve(async (req: Request) => {
  // Handle CORS preflight
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    // Create Supabase client with user's auth context (enforces RLS)
    const supabase = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_ANON_KEY") ?? "",
      {
        global: {
          headers: { Authorization: req.headers.get("Authorization")! },
        },
      }
    );

    // Parse request body
    const body = await req.json();
    const { payload: payloadB64, subdomain, lat, lng } = body;

    if (!payloadB64 || !subdomain) {
      return new Response(
        JSON.stringify({ error: "payload and subdomain are required" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Base64 decode the payload
    const payloadBytes = base64Decode(payloadB64);

    if (payloadBytes.length !== PAYLOAD_LENGTH) {
      return new Response(
        JSON.stringify({ error: `Payload must be exactly ${PAYLOAD_LENGTH} bytes` }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Lookup device passkey
    const { data: embeddedData, error: embeddedError } = await supabase
      .from("embedded")
      .select("passkey, subdomain, id")
      .eq("subdomain", subdomain);

    if (embeddedError || !embeddedData || embeddedData.length === 0) {
      return new Response(
        JSON.stringify({ error: "Device not found" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const device = embeddedData[0];
    const passkey: string = device.passkey;

    // XOR decrypt and validate
    const decoded = xorDecode(payloadBytes, passkey);

    if (!decoded.valid) {
      return new Response(
        JSON.stringify({ error: "Invalid payload: checksum or timestamp validation failed" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const { itemPrice, itemNumber } = decoded;

    // Insert sale record
    const { data: saleData, error: saleError } = await supabase
      .from("sales")
      .insert([
        {
          embedded_id: device.id,
          item_number: itemNumber,
          item_price: fromScaleFactor(itemPrice, SCALE_FACTOR, SCALE_DECIMALS),
          channel: "ble",
          lat: lat !== undefined ? lat : null,
          lng: lng !== undefined ? lng : null,
        },
      ])
      .select("id")
      .single();

    if (saleError || !saleData) {
      return new Response(
        JSON.stringify({ error: saleError?.message ?? "Failed to record sale" }),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Re-encrypt with APPROVE_VEND command
    xorReEncrypt(payloadBytes, passkey, CMD_APPROVE_VEND);

    // Return the approval payload
    const responsePayload = base64Encode(payloadBytes);

    return new Response(
      JSON.stringify({ payload: responsePayload, sales_id: saleData.id }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (err) {
    return new Response(
      JSON.stringify({ error: err instanceof Error ? err.message : "Internal server error" }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});
