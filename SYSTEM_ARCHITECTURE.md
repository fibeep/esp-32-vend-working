# VMflow Cashless Vending System - Architecture Documentation

> **Last Updated:** 2026-03-12
>
> This document describes the current system architecture where the ESP32-S3
> communicates directly with a Supabase Edge Function to process Yappy mobile
> payments. There is no Android app or tablet in this flow.

---

## Overview

VMflow is a cashless vending machine payment system that connects an **ESP32-S3 microcontroller** (installed inside a vending machine) to the **Yappy mobile payment network** (Panama's leading mobile wallet) through a **Supabase Edge Function** backend.

The ESP32 never talks to Yappy directly. All Yappy API interactions are proxied through the Edge Function, which also manages session tokens, records sales, and handles authentication.

```
+-------------------+       HTTPS/TLS        +---------------------+       HTTPS        +-------------+
|                   |  --(JSON over POST)-->  |                     |  --(Yappy API)--> |             |
|    ESP32-S3       |                         |  Supabase Edge Fn   |                    |  Yappy API  |
|  (in vending      |  <--(JSON response)--  |  (yappy-payment)    |  <--(response)--  |  (Panama)   |
|   machine)        |                         |                     |                    |             |
+-------------------+                         +---------------------+                    +-------------+
        |                                             |
        |  SPI bus                                    |  SQL (service role)
        v                                             v
+-------------------+                         +---------------------+
|  ILI9488 Display  |                         |  Supabase Postgres  |
|  (320x480, QR)    |                         |  (sales, devices)   |
+-------------------+                         +---------------------+
```

---

## Components

### 1. ESP32-S3 Firmware (`vending-esp32-display/`)

The ESP32-S3 sits inside the vending machine and communicates with the machine's controller (VMC) over the **MDB bus** (Multi-Drop Bus, the industry-standard vending protocol). It acts as a **Level 1 cashless peripheral in kiosk mode** -- meaning it auto-starts a payment session with unlimited funds as soon as the machine is ready.

**Key modules:**

| Module | File | Purpose |
|--------|------|---------|
| MDB Handler | `mdb_handler.c` | UART-based MDB bus protocol, talks to vending machine |
| Yappy Handler | `yappy_handler.c` | Payment state machine, calls Edge Function |
| XOR Crypto | `xor_crypto.c` | 19-byte XOR payload encryption/decryption |
| Display Handler | `display_handler.c` | ILI9488 SPI display via LVGL (QR codes, status) |
| MQTT Handler | `mqtt_handler.c` | MQTT client for telemetry and remote credit push |
| BLE Handler | `ble_handler.c` | NimBLE for device provisioning and BLE payments |
| Web UI Server | `webui_server.c` | HTTP server for WiFi/device configuration |
| Config | `config.h` | Pin map, MDB constants, shared flags |

### 2. Supabase Edge Function (`supabase/functions/yappy-payment/`)

A single Deno-based serverless function (`index.ts`) that acts as a proxy between the ESP32 and the Yappy API. It handles three actions:

| Action | Purpose |
|--------|---------|
| `generate-qr` | Creates a dynamic Yappy QR code for a given price |
| `check-status` | Polls Yappy for transaction payment status |
| `cancel` | Cancels a pending transaction |

### 3. Supabase Postgres Database

Stores device registrations, sales records, Yappy session tokens, and debug logs. The Edge Function uses the **service role key** to bypass Row Level Security for all database operations.

---

## Complete Payment Flow

### Step 0: Boot Sequence

```
ESP32 powers on
    |
    +--> Load subdomain + passkey from NVS (or Kconfig defaults)
    +--> Initialize MDB bus (UART on GPIO 4/5)
    +--> Initialize ILI9488 display (SPI on GPIO 35-41)
    +--> Connect to WiFi (credentials from NVS)
    +--> Sync clock via SNTP (pool.ntp.org)
    +--> Connect to MQTT broker
    +--> Start Yappy poll task (FreeRTOS, Core 0)
    +--> Display shows "VMflow" idle screen
    |
    +--> MDB: Send RESET, wait for VMC poll
    +--> MDB: Enter ENABLED state (kiosk mode)
    +--> MDB: Auto-start session with max funds ($65,535.00)
         VMC now shows all products as available
```

### Step 1: Customer Selects a Product

The customer presses a button on the vending machine. The VMC sends a **VEND REQUEST** over MDB with the item price and item number.

```
VMC  --[MDB VEND REQUEST: price=125, item=3]-->  ESP32 (mdb_handler.c)
```

The MDB handler receives this in `vTaskMdbEvent` (Core 1) and triggers the Yappy flow:

```c
// mdb_handler.c (simplified)
case VEND_STATE:
    // VMC is asking "can this customer pay?"
    yappy_request_qr(item_price, item_number);  // Starts QR generation
    // MDB task keeps session alive while Yappy processes
```

### Step 2: QR Code Generation

**ESP32 side** (`yappy_handler.c`):

1. State transitions: `IDLE -> QR_PENDING`
2. XOR-encode the price and item number into a 19-byte payload (see Payload Encryption below)
3. Base64-encode the payload
4. POST to Edge Function:

```json
{
    "action": "generate-qr",
    "payload": "CgEAfQADAGfWwxK...==",
    "subdomain": "3"
}
```

**Edge Function side** (`index.ts`):

1. Authenticate the request (see Authentication below)
2. Look up the device in the `embedded` table by subdomain
3. XOR-decode the payload using the device's passkey to extract price and item number
4. Get or create a Yappy session token (cached for 6 hours in `yappy_sessions` table)
5. Call Yappy API: `POST /qr/generate/DYN` with the price
6. Return the QR hash and transaction ID to ESP32:

```json
{
    "qr_hash": "00020101021226570016...",
    "transaction_id": "TXN-12345-ABCDE",
    "amount": 1.25
}
```

**Back on ESP32:**

7. State transitions: `QR_PENDING -> QR_READY`
8. Display handler detects the state change (polls every 200ms)
9. LVGL renders the QR code on the ILI9488 display using `lv_qrcode_update()`
10. Screen shows: price, item number, QR code, and "Scan with Yappy app"

### Step 3: Customer Scans QR Code

The customer opens the Yappy app on their phone and scans the QR code displayed on the ILI9488 screen. This is entirely between the customer and Yappy -- the ESP32 is not involved in this step.

### Step 4: Payment Status Polling

**ESP32 side:**

State transitions: `QR_READY -> POLLING`

Every 3 seconds, the ESP32 polls the Edge Function:

```json
{
    "action": "check-status",
    "transaction_id": "TXN-12345-ABCDE",
    "payload": "CgEAfQADAGfWwxK...==",
    "subdomain": "3"
}
```

The `payload` and `subdomain` are included so the Edge Function can decode them for sale recording when payment is confirmed.

**Edge Function side:**

1. Get a valid Yappy session token
2. Call Yappy API: `GET /transaction/{transaction_id}`
3. Check the status field against known "paid" statuses:
   - `PAGADO`, `EJECUTADO`, `COMPLETADO`, `APROBADO` (Spanish)
   - `PAID`, `COMPLETED`, `APPROVED` (English)
4. Log every check to `yappy_debug_log` table for debugging
5. If NOT paid: return current status to ESP32
6. If PAID: proceed to payment confirmation (Step 5)

**Timeout:** If 5 minutes pass without payment, the ESP32 cancels the transaction and denies the vend.

### Step 5: Payment Confirmed

When the Edge Function detects `PAGADO`:

1. XOR-decode the payload to extract price and item number
2. Insert a sale record into the `sales` table:
   ```
   embedded_id, item_price, item_number, channel="yappy", external_transaction_id
   ```
3. Re-encrypt the payload with command `0x03` (APPROVE_VEND) for the MQTT credit push
4. Call VPS MQTT endpoint to push credit to the ESP32 (fallback mechanism)
5. Return `{ "status": "PAGADO" }` to the ESP32

**Back on ESP32:**

1. `check_payment_status()` returns `true`
2. State transitions: `POLLING -> PAID`
3. **Critical line:** `vend_approved_todo = true` -- this flag tells the MDB task to send **VEND APPROVED** to the VMC
4. Display shows green checkmark + "Payment Confirmed!" + "Dispensing..."
5. The VMC dispenses the product

```
ESP32 (yappy_handler.c)  --[vend_approved_todo = true]-->  ESP32 (mdb_handler.c)
                                                                    |
ESP32 (mdb_handler.c)  --[MDB VEND APPROVED]-->  VMC  -->  DISPENSE PRODUCT
```

### Step 6: Cleanup

After 5 seconds of showing the success screen:

1. State transitions: `PAID -> IDLE`
2. Display returns to idle screen ("Select a product")
3. QR hash, transaction ID, and error message are cleared
4. MDB session restarts (kiosk mode auto-begins new session)

---

## Cancellation Flow

If the customer walks away or the machine operator cancels:

1. **ESP32 calls `yappy_cancel()`**
2. Edge Function: `PUT /transaction/{transaction_id}` on Yappy API
3. State resets to `IDLE`
4. MDB: `vend_denied_todo = true` -- tells VMC the payment was denied
5. Display returns to idle

---

## Payload Encryption (XOR Crypto)

All communication between ESP32 and Edge Function includes a **19-byte XOR-encrypted payload** that carries the vend request data. This ensures the price and item number cannot be tampered with in transit (beyond TLS).

### Payload Layout (19 bytes)

```
Byte 0       : CMD         (command opcode, NOT encrypted)
Byte 1       : VER         (protocol version, always 0x01)
Bytes 2-5    : ITEM_PRICE  (uint32, big-endian)
Bytes 6-7    : ITEM_NUMBER (uint16, big-endian)
Bytes 8-11   : TIMESTAMP   (int32, big-endian, Unix seconds)
Bytes 12-13  : PAX_COUNT   (uint16, big-endian, unused for Yappy)
Bytes 14-17  : RANDOM      (random padding from esp_fill_random)
Byte 18      : CHECKSUM    (sum of bytes 0-17, lower 8 bits)
```

### Encryption Steps (ESP32 -> Edge Function)

1. Fill bytes 1-17 with cryptographic random data (`esp_fill_random`)
2. Overwrite structured fields (VER, PRICE, ITEM, TIMESTAMP, PAX)
3. Compute checksum: `sum(bytes[0..17]) & 0xFF` -> byte 18
4. XOR bytes 1-18 with the device's 18-byte passkey
5. Base64-encode the result for JSON transport

### Decryption Steps (Edge Function)

1. Base64-decode the payload string
2. Look up the device's passkey from `embedded` table by subdomain
3. XOR bytes 1-18 with the passkey (undoes encryption)
4. Verify checksum matches
5. Extract price and item number

### Security Properties

- **Per-device passkey:** Each ESP32 has a unique 18-byte passkey auto-generated at registration (`SUBSTRING(md5(random()), 1, 18)`)
- **Timestamp validation:** ESP32-side decryption rejects payloads older than 8 seconds (replay protection)
- **Random padding:** Bytes 14-17 are random, making it harder to recover the passkey from captured payloads
- **Transport encryption:** The entire payload is additionally protected by TLS 1.2/1.3 over HTTPS

---

## Authentication Layers

The system uses five layers of authentication/validation between the ESP32 and the Yappy API:

### Layer 1: TLS Certificate Verification

All HTTPS calls from ESP32 to Supabase are verified against an embedded **GTS Root R4** certificate (Google Trust Services ECDSA root). This is pinned in firmware rather than using the full ESP-IDF cert bundle because the bundle is missing the GlobalSign cross-signer that Supabase's chain requires.

### Layer 2: Supabase API Key (`apikey` header)

Every Edge Function call includes the project's **anon key** in the `apikey` header. This identifies the request as coming from an authorized client of this Supabase project. The anon key is baked into firmware via Kconfig.

### Layer 3: Device Authentication (`x-device-key` header)

The ESP32 sends a shared secret in the `x-device-key` HTTP header. The Edge Function compares this against the `ESP32_DEVICE_KEY` environment variable.

```
ESP32 sends:    x-device-key: <shared-secret>
Server checks:  Deno.env.get("ESP32_DEVICE_KEY") === deviceKey
```

This replaces Supabase JWT authentication (which would require user login on the ESP32). The Edge Function sets `userId = "__device__"` for device-authenticated requests and resolves the actual owner from the `embedded` table when recording sales.

### Layer 4: XOR Payload Validation

Even after authentication, the Edge Function XOR-decrypts the payload using the device's passkey from the database. If the passkey is wrong, decryption produces garbage (checksum will fail). This proves the request came from the specific registered device, not just any device with the shared key.

### Layer 5: Yappy API Authentication

The Edge Function authenticates with Yappy using three credentials stored as Supabase secrets:

| Secret | Purpose |
|--------|---------|
| `YAPPY_API_KEY` | Identifies the merchant account |
| `YAPPY_SECRET_KEY` | Proves ownership of the merchant account |
| `YAPPY_BASE_URL` | Yappy API endpoint |

A **device session token** is obtained by calling `POST /session/device` and is cached in the `yappy_sessions` table for 6 hours to avoid re-authentication on every QR generation.

---

## Database Schema

### `embedded` (Device Registry)

Each ESP32 board is registered here. The `subdomain` is the device's unique identifier, and the `passkey` is used for XOR payload encryption/decryption.

| Column | Type | Purpose |
|--------|------|---------|
| `id` | uuid (PK) | Primary key |
| `subdomain` | bigint (identity) | Unique device ID (auto-increment) |
| `passkey` | text | 18-char XOR key (auto-generated: `md5(random())[1:18]`) |
| `owner_id` | uuid (FK auth.users) | Device owner |
| `machine_id` | uuid (FK machines) | Link to physical machine (nullable) |
| `mac_address` | text | ESP32 MAC address |
| `status` | enum | `online` or `offline` |
| `status_at` | timestamptz | Last status change |

### `sales` (Transaction Records)

Every completed payment creates a row here. The Edge Function inserts this automatically when Yappy reports `PAGADO`.

| Column | Type | Purpose |
|--------|------|---------|
| `id` | uuid (PK) | Primary key |
| `embedded_id` | uuid (FK embedded) | Which device processed the sale |
| `item_price` | float8 | Price in dollars (e.g., 1.25) |
| `item_number` | bigint | Slot/item number on the machine |
| `channel` | enum | `ble`, `mqtt`, `cash`, or `yappy` |
| `owner_id` | uuid | Resolved from `embedded.owner_id` for device auth |
| `external_transaction_id` | text | Yappy transaction ID |
| `lat`, `lng` | float8 | GPS coordinates (nullable, unused by ESP32) |
| `machine_id` | uuid (FK machines) | Physical machine (nullable) |
| `product_id` | uuid (FK products) | Product record (nullable) |

### `yappy_sessions` (Token Cache)

Yappy session tokens are cached here for 6 hours to avoid re-authenticating on every API call.

| Column | Type | Purpose |
|--------|------|---------|
| `token` | text | Yappy API session token |
| `expires_at` | timestamptz | Expiration time (created_at + 6 hours) |

### `yappy_debug_log` (Debug Logging)

Every `check-status` call logs the raw Yappy API response here for debugging.

| Column | Type | Purpose |
|--------|------|---------|
| `transaction_id` | text | Yappy transaction ID |
| `raw_response` | jsonb | Full Yappy API response body |
| `extracted_status` | text | Normalized status string (e.g., "PAGADO") |
| `action` | text | Which Edge Function action triggered this log |

---

## State Machine

The Yappy payment handler runs as a FreeRTOS task (`vTaskYappyPoll`) that loops forever, checking the current state and taking action:

```
                    VEND_REQUEST from MDB
                           |
                           v
    +------+         +-----------+         +----------+
    | IDLE | ------> | QR_PENDING| ------> | QR_READY |
    +------+         +-----------+         +----------+
       ^                   |                     |
       |              (API fail)           (start polling)
       |                   |                     |
       |                   v                     v
       |              +---------+           +---------+
       +<----(5s)---- | ERROR   |           | POLLING | ---(every 3s)--+
       |              +---------+           +---------+                |
       |                   ^                     |                     |
       |                   |                (PAGADO!)                  |
       |              (5min timeout)             |                     |
       |                                         v                     |
       |                                    +--------+                |
       +<-----------(5s)------------------- | PAID   |                |
                                            +--------+                |
                                                                      |
                                                  (not paid)----------+
```

| State | Duration | Display Shows | Action |
|-------|----------|---------------|--------|
| `IDLE` | Indefinite | "VMflow" logo + "Select a product" | Sleep 500ms |
| `QR_PENDING` | ~1-3s | Spinner / "Generating..." | POST generate-qr to Edge Function |
| `QR_READY` | Instant | Price + QR code + "Scan with Yappy" | Transition to POLLING |
| `POLLING` | Up to 5 min | Same as QR_READY | GET check-status every 3s |
| `PAID` | 5s | Green checkmark + "Payment Confirmed!" | `vend_approved_todo = true` |
| `ERROR` | 5s | Warning icon + error message | `vend_denied_todo = true` |

---

## MDB Integration

The ESP32 communicates with the vending machine controller (VMC) over the **MDB bus** (Multi-Drop Bus), a 9-bit UART protocol at 9600 baud.

### Kiosk Mode

The ESP32 operates as a **Level 1 cashless peripheral** in kiosk mode:

- On ENABLE: auto-starts a session with maximum funds ($65,535.00)
- Customer sees all products as available (the machine thinks a credit card is inserted)
- On VEND REQUEST: the ESP32 decides whether to approve based on Yappy payment status
- On VEND APPROVED: the machine dispenses the product
- On VEND DENIED: the machine cancels and returns to idle

### MDB-Yappy Coordination Flags

The MDB task (Core 1) and Yappy task (Core 0) communicate through shared volatile flags defined in `config.h`:

| Flag | Set by | Read by | Purpose |
|------|--------|---------|---------|
| `vend_approved_todo` | Yappy handler | MDB handler | Approve vend after Yappy payment |
| `vend_denied_todo` | Yappy handler | MDB handler | Deny vend on cancel/timeout/error |
| `session_timer_reset_todo` | Yappy handler | MDB handler | Keep MDB session alive while polling |

### Session Keep-Alive

MDB sessions have a configurable timeout (typically 30-60 seconds). Since Yappy payments can take up to 5 minutes, the Yappy handler sets `session_timer_reset_todo = true` every polling cycle. The MDB handler resets the session timer when it sees this flag, preventing the VMC from timing out the session.

---

## Network Topology

```
ESP32 (STA mode)
    |
    +--[WiFi]--> Router --> Internet
    |                          |
    |                     +----+----+
    |                     |         |
    |                Supabase    Yappy API
    |              (us-east-1)  (Panama)
    |
    +--[SoftAP: "VMflow"]--> Phone/laptop for configuration
    |                         http://192.168.4.1/
    |
    +--[SPI bus]--> ILI9488 display (QR codes, status)
    |
    +--[MDB UART]--> Vending machine controller
    |
    +--[MQTT]--> VPS broker (telemetry, remote credit push)
```

**WiFi:** The ESP32 runs in dual mode -- STA (station) connects to the venue's WiFi for internet access, while SoftAP ("VMflow") always broadcasts for configuration access. Only the configuration endpoints are served on the SoftAP; all Yappy traffic goes through the STA connection.

**Configuration:** Any device can connect to the "VMflow" WiFi network and navigate to `http://192.168.4.1/` to configure WiFi credentials, MQTT settings, and device parameters.

---

## Error Handling

### ESP32 Errors

| Error | State Transition | Recovery |
|-------|-----------------|----------|
| WiFi not connected | Stays IDLE | Yappy handler checks `wifi_sta_connected` before any HTTP call |
| Edge Function HTTP error | QR_PENDING -> ERROR | Shows error for 5s, returns to IDLE |
| JSON parse failure | QR_PENDING -> ERROR | Shows "Failed to generate QR code" |
| Clock not synced | Blocks up to 15s | SNTP wait loop before HTTPS call (TLS needs correct time) |
| Polling timeout (5 min) | POLLING -> ERROR | Cancels transaction on Yappy, denies vend |
| Payment confirmed but not in VEND_STATE | Logs warning | Sale is still recorded server-side |

### Edge Function Errors

| Error | HTTP Status | Response |
|-------|------------|----------|
| Missing authentication | 401 | `{ "error": "Missing authentication" }` |
| Invalid device key | 401 | `{ "error": "Invalid device key" }` |
| Device not found by subdomain | 404 | `{ "error": "Device not found" }` |
| Payload decode failure | 400 | `{ "error": "Failed to decode payload" }` |
| Yappy API failure | 502 | `{ "error": "Yappy QR generation failed", "details": {...} }` |
| Sale insert failure | 500 | `{ "error": "..." }` |

### MQTT Credit Push (Fallback)

When Yappy payment is confirmed, the Edge Function also attempts to push credit to the ESP32 via MQTT through the VPS. This is a **fallback mechanism** -- the primary dispense path is the direct `vend_approved_todo` flag set by the ESP32's own polling. The MQTT push is non-fatal; if it fails, the sale is still recorded and the ESP32 has already approved the vend.

---

## Sequence Diagram (Full Flow)

```
  Customer           Vending Machine        ESP32                  Edge Function           Yappy API
     |                     |                  |                         |                      |
     |--[press button]--->|                   |                         |                      |
     |                     |--MDB VEND_REQ-->|                         |                      |
     |                     |                  |                         |                      |
     |                     |                  |--POST generate-qr---->|                      |
     |                     |                  |                         |--POST /session/device->|
     |                     |                  |                         |<--{token}------------|
     |                     |                  |                         |                      |
     |                     |                  |                         |--POST /qr/generate/DYN->|
     |                     |                  |                         |<--{hash, txn_id}-----|
     |                     |                  |<--{qr_hash, txn_id}---|                      |
     |                     |                  |                         |                      |
     |                     |                  |--[display QR on        |                      |
     |                     |                  |   ILI9488 screen]      |                      |
     |                     |                  |                         |                      |
     |--[scan QR with Yappy app]-------------------------------------------[pay in Yappy]-->|
     |                     |                  |                         |                      |
     |                     |                  |--POST check-status---->|                      |
     |                     |                  |                         |--GET /transaction/-->|
     |                     |                  |                         |<--{PENDIENTE}--------|
     |                     |                  |<--{status: PENDIENTE}--|                      |
     |                     |                  |                         |                      |
     |                     |                  |  ... (every 3 seconds)  |                      |
     |                     |                  |                         |                      |
     |                     |                  |--POST check-status---->|                      |
     |                     |                  |                         |--GET /transaction/-->|
     |                     |                  |                         |<--{PAGADO}-----------|
     |                     |                  |                         |                      |
     |                     |                  |                         |--INSERT INTO sales---|
     |                     |                  |                         |                      |
     |                     |                  |<--{status: PAGADO}----|                      |
     |                     |                  |                         |                      |
     |                     |                  |--[vend_approved=true]  |                      |
     |                     |<-MDB VEND_APPR--|                         |                      |
     |                     |                  |                         |                      |
     |<--[product dispensed]|                  |                         |                      |
     |                     |                  |                         |                      |
     |                     |                  |--[display: "Payment    |                      |
     |                     |                  |   Confirmed!"]         |                      |
     |                     |                  |                         |                      |
     |                     |                  |--[5s delay, back to    |                      |
     |                     |                  |   IDLE screen]         |                      |
```

---

## Environment Variables

### ESP32 Firmware (Kconfig / NVS)

| Variable | Source | Purpose |
|----------|--------|---------|
| `CONFIG_SUPABASE_URL` | Kconfig | Supabase project URL |
| `CONFIG_SUPABASE_ANON_KEY` | Kconfig | Supabase public API key |
| `CONFIG_ESP32_DEVICE_KEY` | Kconfig | Shared secret for device auth |
| `CONFIG_MDB_SCALE_FACTOR` | Kconfig | MDB price multiplier (default: 1) |
| `CONFIG_MDB_DECIMAL_PLACES` | Kconfig | MDB price decimal places (default: 2) |
| `my_subdomain` | NVS (or Kconfig default) | Device identifier for Edge Function |
| `my_passkey` | NVS (or Kconfig default) | 18-byte XOR encryption key |

### Supabase Edge Function (Secrets)

| Secret | Purpose |
|--------|---------|
| `SUPABASE_URL` | Auto-injected by Supabase |
| `SUPABASE_ANON_KEY` | Auto-injected by Supabase |
| `SUPABASE_SERVICE_ROLE_KEY` | Auto-injected, bypasses RLS |
| `ESP32_DEVICE_KEY` | Shared secret (must match firmware) |
| `YAPPY_BASE_URL` | Yappy API base URL |
| `YAPPY_API_KEY` | Yappy merchant API key |
| `YAPPY_SECRET_KEY` | Yappy merchant secret key |
| `YAPPY_ID_DEVICE` | Yappy device registration ID |
| `YAPPY_NAME_DEVICE` | Yappy device name |
| `YAPPY_USER_DEVICE` | Yappy device user |
| `YAPPY_ID_GROUP` | Yappy merchant group ID |
| `VPS_BASE_URL` | VPS URL for MQTT credit push fallback |

---

## Task Layout (FreeRTOS)

| Task | Core | Priority | Stack | Purpose |
|------|------|----------|-------|---------|
| `vTaskMdbEvent` | 1 | 1 | 4096 | MDB bus protocol (timing-critical) |
| `vTaskBitEvent` | 0 | 1 | 2048 | LED + buzzer status indicators |
| `vTaskYappyPoll` | 0 | 1 | 6144 | Yappy payment state machine |
| `vTaskDisplayUpdate` | 0 | 2 | 4096 | Polls yappy state, updates LVGL widgets |
| LVGL port (auto) | 0 | 2 | 4096 | Runs lv_timer_handler(), SPI DMA transfers |

---

## Device Provisioning

To register a new ESP32 device:

1. Insert a row in the `embedded` table (subdomain auto-increments, passkey auto-generates)
2. Flash the firmware with matching `subdomain` and `passkey` in Kconfig defaults (or write via BLE/NVS)
3. Configure WiFi credentials via the SoftAP web UI at `http://192.168.4.1/`
4. The device connects to WiFi, syncs time, and is ready to process payments

The `subdomain` ties the ESP32 to its database record. The `passkey` ensures only that specific device can generate valid XOR payloads that the Edge Function will accept.
