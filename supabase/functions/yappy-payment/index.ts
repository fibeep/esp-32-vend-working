import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

// ── Constants (shared with request-credit) ──────────────────────────────
const PAYLOAD_LENGTH = 19;
const PASSKEY_LENGTH = 18;
const CMD_APPROVE_VEND = 0x03;
const SCALE_FACTOR = 1;
const SCALE_DECIMALS = 2;

// Yappy session token lifespan (6 hours)
const YAPPY_SESSION_TTL_MS = 6 * 60 * 60 * 1000;

// VPS backend URL for MQTT credit push
const VPS_BASE_URL = Deno.env.get("VPS_BASE_URL") ?? "https://api.panamavendingmachines.com";

// ── XOR helpers (byte-identical to ESP32 firmware & request-credit) ─────

function xorDecode(
  payload: Uint8Array,
  passkey: string
): { itemPrice: number; itemNumber: number; valid: boolean } {
  if (payload.length !== PAYLOAD_LENGTH || passkey.length !== PASSKEY_LENGTH) {
    return { itemPrice: 0, itemNumber: 0, valid: false };
  }

  // XOR decrypt bytes 1-18
  for (let k = 0; k < PASSKEY_LENGTH; k++) {
    payload[k + 1] ^= passkey.charCodeAt(k);
  }

  // Extract fields
  const itemPrice =
    ((payload[2] & 0xFF) << 24) | ((payload[3] & 0xFF) << 16) |
    ((payload[4] & 0xFF) << 8) | (payload[5] & 0xFF);
  const itemNumber = ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);

  return { itemPrice, itemNumber, valid: true };
}

function xorReEncrypt(payload: Uint8Array, passkey: string, newCmd: number): void {
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

// ── Base64 helpers ──────────────────────────────────────────────────────

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

// ── CORS headers ────────────────────────────────────────────────────────
const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function jsonResponse(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

// ── Yappy API helpers ───────────────────────────────────────────────────

async function getOrCreateYappyToken(supabaseAdmin: ReturnType<typeof createClient>): Promise<string> {
  // Check for a valid cached session
  const { data: sessions } = await supabaseAdmin
    .from("yappy_sessions")
    .select("token, expires_at")
    .gt("expires_at", new Date().toISOString())
    .order("created_at", { ascending: false })
    .limit(1);

  if (sessions && sessions.length > 0) {
    console.log("[yappy] Reusing cached session token");
    return sessions[0].token;
  }

  // Open a new device session with Yappy
  console.log("[yappy] Opening new Yappy device session...");
  const baseUrl = Deno.env.get("YAPPY_BASE_URL") ?? "";
  const apiKey = Deno.env.get("YAPPY_API_KEY") ?? "";
  const secretKey = Deno.env.get("YAPPY_SECRET_KEY") ?? "";

  const payload = {
    body: {
      device: {
        id: Deno.env.get("YAPPY_ID_DEVICE") ?? "",
        name: Deno.env.get("YAPPY_NAME_DEVICE") ?? "",
        user: Deno.env.get("YAPPY_USER_DEVICE") ?? "",
      },
      group_id: Deno.env.get("YAPPY_ID_GROUP") ?? "",
    },
  };

  const response = await fetch(`${baseUrl}/session/device`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "api-key": apiKey,
      "secret-key": secretKey,
    },
    body: JSON.stringify(payload),
  });

  const data: any = await response.json();

  if (!response.ok || data.status?.code !== "YP-0000") {
    throw new Error(`Yappy session open failed: ${JSON.stringify(data)}`);
  }

  const token = data.body?.token;
  if (!token) {
    throw new Error("Yappy API did not return a token");
  }

  // Cache the token
  const expiresAt = new Date(Date.now() + YAPPY_SESSION_TTL_MS).toISOString();
  await supabaseAdmin.from("yappy_sessions").insert([
    { token, expires_at: expiresAt },
  ]);

  console.log("[yappy] New session token cached, expires at", expiresAt);
  return token;
}

// ── Action handlers ─────────────────────────────────────────────────────

async function handleGenerateQr(
  body: any,
  supabaseAdmin: ReturnType<typeof createClient>,
  userId: string
): Promise<Response> {
  const { payload: payloadB64, subdomain } = body;

  if (!payloadB64 || !subdomain) {
    return jsonResponse({ error: "payload and subdomain are required" }, 400);
  }

  const payloadBytes = base64Decode(payloadB64);
  if (payloadBytes.length !== PAYLOAD_LENGTH) {
    return jsonResponse({ error: `Payload must be ${PAYLOAD_LENGTH} bytes` }, 400);
  }

  // Look up device passkey
  const { data: embeddedData, error: embeddedError } = await supabaseAdmin
    .from("embedded")
    .select("passkey, id")
    .eq("subdomain", subdomain);

  if (embeddedError || !embeddedData || embeddedData.length === 0) {
    return jsonResponse({ error: "Device not found" }, 404);
  }

  const device = embeddedData[0];
  const passkey: string = device.passkey;

  // XOR decode to extract price
  const decoded = xorDecode(payloadBytes, passkey);
  if (!decoded.valid) {
    return jsonResponse({ error: "Failed to decode payload" }, 400);
  }

  const displayPrice = fromScaleFactor(decoded.itemPrice, SCALE_FACTOR, SCALE_DECIMALS);
  console.log(`[yappy] Generate QR: item #${decoded.itemNumber}, price=$${displayPrice}`);

  // Get or create Yappy session token
  const yappyToken = await getOrCreateYappyToken(supabaseAdmin);

  // Call Yappy API to generate dynamic QR
  const baseUrl = Deno.env.get("YAPPY_BASE_URL") ?? "";
  const apiKey = Deno.env.get("YAPPY_API_KEY") ?? "";
  const secretKey = Deno.env.get("YAPPY_SECRET_KEY") ?? "";

  const qrPayload = {
    body: {
      charge_amount: {
        sub_total: displayPrice,
        tax: 0,
        tip: 0,
        discount: 0,
        total: displayPrice,
      },
      description: `VMflow Vend - Item #${decoded.itemNumber}`,
    },
  };

  const qrResponse = await fetch(`${baseUrl}/qr/generate/DYN`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "authorization": yappyToken,
      "api-key": apiKey,
      "secret-key": secretKey,
    },
    body: JSON.stringify(qrPayload),
  });

  const qrData: any = await qrResponse.json();

  if (!qrResponse.ok || qrData.status?.code !== "YP-0000") {
    console.error("[yappy] QR generation failed:", JSON.stringify(qrData));
    return jsonResponse({ error: "Yappy QR generation failed", details: qrData }, 502);
  }

  if (!qrData.body?.hash || !qrData.body?.transactionId) {
    return jsonResponse({ error: "Invalid Yappy response: missing hash or transactionId" }, 502);
  }

  console.log(`[yappy] QR generated: txn=${qrData.body.transactionId}`);

  return jsonResponse({
    qr_hash: qrData.body.hash,
    transaction_id: qrData.body.transactionId,
    amount: displayPrice,
  });
}

async function handleCheckStatus(
  body: any,
  supabaseAdmin: ReturnType<typeof createClient>,
  userId: string,
  authHeader: string
): Promise<Response> {
  const { transaction_id, payload: payloadB64, subdomain, lat, lng } = body;

  if (!transaction_id) {
    return jsonResponse({ error: "transaction_id is required" }, 400);
  }

  // Get Yappy session token
  const yappyToken = await getOrCreateYappyToken(supabaseAdmin);

  const baseUrl = Deno.env.get("YAPPY_BASE_URL") ?? "";
  const apiKey = Deno.env.get("YAPPY_API_KEY") ?? "";
  const secretKey = Deno.env.get("YAPPY_SECRET_KEY") ?? "";

  // Check transaction status with Yappy
  const statusResponse = await fetch(`${baseUrl}/transaction/${transaction_id}`, {
    method: "GET",
    headers: {
      "Content-Type": "application/json",
      "authorization": yappyToken,
      "api-key": apiKey,
      "secret-key": secretKey,
    },
  });

  const statusData: any = await statusResponse.json();

  // Log the FULL raw Yappy response for debugging
  console.log(`[yappy] Raw Yappy response for txn ${transaction_id}:`, JSON.stringify(statusData));

  if (!statusResponse.ok || statusData.status?.code !== "YP-0000") {
    console.error("[yappy] Status check failed:", JSON.stringify(statusData));
    return jsonResponse({ error: "Yappy status check failed", details: statusData }, 502);
  }

  // Try multiple possible paths for the transaction status
  const txStatus: string | undefined =
    statusData.body?.status ??
    statusData.body?.transaction?.status ??
    statusData.body?.transactionStatus;

  const txStatusUpper = (txStatus ?? "").toUpperCase().trim();

  console.log(`[yappy] Transaction ${transaction_id} status: "${txStatus}" (normalized: "${txStatusUpper}")`);

  // ── Debug: log EVERY check-status call to yappy_debug_log ──
  try {
    await supabaseAdmin.from("yappy_debug_log").insert([{
      transaction_id,
      raw_response: statusData,
      extracted_status: txStatusUpper || "EMPTY",
      action: "check-status",
    }]);
  } catch (dbgErr) {
    console.error("[yappy] Debug log insert failed:", dbgErr);
  }

  // Accept multiple possible "paid" statuses (Yappy may use different terms)
  const PAID_STATUSES = ["PAGADO", "EJECUTADO", "COMPLETADO", "APROBADO", "PAID", "COMPLETED", "APPROVED"];
  const isPaid = PAID_STATUSES.includes(txStatusUpper);

  // If not paid yet, return the current status + debug info
  if (!isPaid) {
    return jsonResponse({
      status: txStatus ?? "UNKNOWN",
      yappy_raw_status: txStatus,
      yappy_body_keys: statusData.body ? Object.keys(statusData.body) : [],
    });
  }

  // Payment confirmed! Process the approval.
  if (!payloadB64 || !subdomain) {
    return jsonResponse({
      error: "payload and subdomain required for payment confirmation",
    }, 400);
  }

  const payloadBytes = base64Decode(payloadB64);
  if (payloadBytes.length !== PAYLOAD_LENGTH) {
    return jsonResponse({ error: `Payload must be ${PAYLOAD_LENGTH} bytes` }, 400);
  }

  // Look up device
  const { data: embeddedData, error: embeddedError } = await supabaseAdmin
    .from("embedded")
    .select("passkey, id")
    .eq("subdomain", subdomain);

  if (embeddedError || !embeddedData || embeddedData.length === 0) {
    return jsonResponse({ error: "Device not found" }, 404);
  }

  const device = embeddedData[0];
  const passkey: string = device.passkey;

  // XOR decode to extract price/item
  const decoded = xorDecode(payloadBytes, passkey);
  const displayPrice = fromScaleFactor(decoded.itemPrice, SCALE_FACTOR, SCALE_DECIMALS);

  // Record the sale
  const { data: saleData, error: saleError } = await supabaseAdmin
    .from("sales")
    .insert([{
      embedded_id: device.id,
      item_number: decoded.itemNumber,
      item_price: displayPrice,
      channel: "yappy",
      owner_id: userId,
      lat: lat ?? null,
      lng: lng ?? null,
      external_transaction_id: transaction_id,
    }])
    .select("id")
    .single();

  if (saleError || !saleData) {
    console.error("[yappy] Sale insert failed:", saleError?.message);
    return jsonResponse({ error: saleError?.message ?? "Failed to record sale" }, 500);
  }

  // Re-encrypt with APPROVE command
  xorReEncrypt(payloadBytes, passkey, CMD_APPROVE_VEND);
  const approvalPayload = base64Encode(payloadBytes);

  console.log(`[yappy] PAID! Sale ID: ${saleData.id}, txn: ${transaction_id}`);

  // ── Push credit to ESP32 via MQTT (server-to-device, bypasses BLE) ────
  // The MDB session likely timed out while waiting for Yappy payment.
  // This MQTT credit push starts a NEW MDB session on the ESP32 so the
  // customer can re-select their product and it auto-dispenses.
  try {
    const mqttPushResponse = await fetch(`${VPS_BASE_URL}/api/credit/mqtt-push`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": authHeader,
      },
      body: JSON.stringify({
        amount: displayPrice,
        subdomain: subdomain,
      }),
    });

    const mqttPushData = await mqttPushResponse.json();

    if (mqttPushResponse.ok) {
      console.log(`[yappy] MQTT credit pushed to ${subdomain}: $${displayPrice}`);
    } else {
      console.error(`[yappy] MQTT push failed (non-fatal): ${JSON.stringify(mqttPushData)}`);
    }
  } catch (mqttErr) {
    // Non-fatal: sale is already recorded, MQTT push is best-effort
    console.error(`[yappy] MQTT push error (non-fatal): ${mqttErr instanceof Error ? mqttErr.message : String(mqttErr)}`);
  }

  return jsonResponse({
    status: "PAGADO",
    payload: approvalPayload,
    sales_id: saleData.id,
  });
}

async function handleCancel(
  body: any,
  supabaseAdmin: ReturnType<typeof createClient>
): Promise<Response> {
  const { transaction_id } = body;

  if (!transaction_id) {
    return jsonResponse({ error: "transaction_id is required" }, 400);
  }

  const yappyToken = await getOrCreateYappyToken(supabaseAdmin);

  const baseUrl = Deno.env.get("YAPPY_BASE_URL") ?? "";
  const apiKey = Deno.env.get("YAPPY_API_KEY") ?? "";
  const secretKey = Deno.env.get("YAPPY_SECRET_KEY") ?? "";

  const cancelResponse = await fetch(`${baseUrl}/transaction/${transaction_id}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      "authorization": yappyToken,
      "api-key": apiKey,
      "secret-key": secretKey,
    },
  });

  const cancelData: any = await cancelResponse.json();

  if (!cancelResponse.ok || cancelData.status?.code !== "YP-0000") {
    console.error("[yappy] Cancel failed:", JSON.stringify(cancelData));
    return jsonResponse({ error: "Yappy cancel failed", details: cancelData }, 502);
  }

  console.log(`[yappy] Transaction ${transaction_id} cancelled`);

  return jsonResponse({
    status: cancelData.body?.status ?? "CANCELLED",
    message: "Transaction cancelled",
  });
}

// ── Main handler ────────────────────────────────────────────────────────

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    // Manual auth verification (verify_jwt is disabled at gateway level)
    const authHeader = req.headers.get("Authorization");
    if (!authHeader || !authHeader.startsWith("Bearer ")) {
      return jsonResponse({ error: "Missing or malformed Authorization header" }, 401);
    }

    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const supabaseAnonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
    const supabaseServiceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

    // Verify user JWT
    const supabase = createClient(supabaseUrl, supabaseAnonKey, {
      global: { headers: { Authorization: authHeader } },
    });

    const { data: { user }, error: authError } = await supabase.auth.getUser();
    if (authError || !user) {
      return jsonResponse({ error: "Unauthorized", details: authError?.message }, 401);
    }

    // Use service role to bypass RLS
    const supabaseAdmin = createClient(supabaseUrl, supabaseServiceRoleKey);

    // Parse request body
    const body = await req.json();
    const { action } = body;

    console.log(`[yappy] Action: ${action}, User: ${user.email}`);

    switch (action) {
      case "generate-qr":
        return await handleGenerateQr(body, supabaseAdmin, user.id);

      case "check-status":
        return await handleCheckStatus(body, supabaseAdmin, user.id, authHeader);

      case "cancel":
        return await handleCancel(body, supabaseAdmin);

      default:
        return jsonResponse({ error: `Unknown action: ${action}` }, 400);
    }
  } catch (err) {
    console.error(`[yappy] Error: ${err instanceof Error ? err.message : String(err)}`);
    return jsonResponse(
      { error: err instanceof Error ? err.message : "Internal server error" },
      500
    );
  }
});
