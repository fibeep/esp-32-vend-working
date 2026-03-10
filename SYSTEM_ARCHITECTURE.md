# VMflow Vending System - Architecture & Status Document

> **Purpose:** This document serves as a comprehensive reference for any developer or AI agent joining the project. It describes the full system architecture, current state of deployment, configurations, and known issues.
>
> **Last Updated:** 2026-03-10
> **GitHub Repo:** https://github.com/fibeep/esp-32-vend-working

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Component Architecture](#2-component-architecture)
3. [vending-esp32 (ESP32-S3 Firmware)](#3-vending-esp32-esp32-s3-firmware)
4. [vending-app (Android Mobile App)](#4-vending-app-android-mobile-app)
5. [vending-backend (VPS Services)](#5-vending-backend-vps-services)
6. [Supabase (Cloud Backend)](#6-supabase-cloud-backend)
7. [BLE Communication Protocol](#7-ble-communication-protocol)
8. [Vending Flow (End-to-End)](#8-vending-flow-end-to-end)
9. [Infrastructure & Domains](#9-infrastructure--domains)
10. [Database Schema](#10-database-schema)
11. [Current Status & Known Issues](#11-current-status--known-issues)
12. [Development Environment Setup](#12-development-environment-setup)
13. [Key File Reference](#13-key-file-reference)

---

## 1. System Overview

VMflow is a cashless vending machine payment system built around the ESP32-S3 microcontroller. It enables BLE-based mobile payments for vending machines that use the MDB (Multi-Drop Bus) protocol.

### High-Level Architecture

```
                          CLOUD (Supabase)
                     +-----------------------+
                     | PostgreSQL Database    |
                     | GoTrue Auth            |
                     | Edge Functions         |
                     | PostgREST API          |
                     +-----------+-----------+
                                 |
                          HTTPS/REST
                                 |
     +---------------------------+---------------------------+
     |                                                       |
ANDROID APP                                          VPS (Backend)
+------------------+                           +------------------+
| Kotlin/Compose   |                           | Next.js API      |
| Kable BLE        |                           | Mosquitto MQTT   |
| Ktor HTTP        |                           | MQTT Bridge      |
| Koin DI          |                           +--------+---------+
+--------+---------+                                    |
         |                                         MQTT/TLS
     BLE GATT                                          |
         |                                    +--------+---------+
+--------+---------+                          |                  |
|  ESP32-S3        +--- WiFi/MQTT ------------+                  |
|  NimBLE BLE      |                                             |
|  MDB Protocol    |                                             |
+--------+---------+                                             |
         |                                                       |
    9-bit UART (MDB)                                             |
         |                                                       |
+--------+---------+                                             |
| VENDING MACHINE  |                                             |
| (VMC Controller) |                                             |
+------------------+                                             |
```

### Communication Channels

| Channel | From | To | Protocol | Purpose |
|---------|------|----|----------|---------|
| BLE GATT | Android App | ESP32-S3 | BLE 5.0 | Device provisioning, vend session control |
| 9-bit UART | ESP32-S3 | Vending Machine | MDB @ 9600 baud | Cashless reader emulation |
| MQTT/TLS | ESP32-S3 | VPS Mosquitto | MQTT 3.1.1 | Telemetry, remote credit, status |
| HTTPS/REST | Android App | Supabase | REST/JSON | Auth, device CRUD, sales recording |
| HTTPS/REST | VPS Backend | Supabase | REST/JSON | Data sync, auth verification |

---

## 2. Component Architecture

The system has **4 main components**:

| # | Component | Location | Technology | Status |
|---|-----------|----------|------------|--------|
| 1 | **vending-esp32** | `vending-esp32/` | C, ESP-IDF v6.1, NimBLE | Working |
| 2 | **vending-app** | `vending-app/` | Kotlin, Jetpack Compose, Kable | Working |
| 3 | **vending-backend** | `vending-backend/` | Next.js 15, Mosquitto, MQTT Bridge | Deployed on VPS |
| 4 | **Supabase** | Cloud (luntgcliwnyvrmrqpdts) | PostgreSQL, GoTrue, Edge Functions | Active |

---

## 3. vending-esp32 (ESP32-S3 Firmware)

### Overview
The ESP32-S3 firmware acts as a **cashless payment device** on the MDB bus. It emulates a cashless reader (MDB address 16), communicates with the Android app via BLE, and reports telemetry via MQTT.

### Key Configuration
- **Target:** ESP32-S3
- **Flash:** 16MB
- **Partition Table:** Two OTA (supports OTA updates)
- **BLE Stack:** NimBLE (lightweight, BLE-only)
- **MDB Address:** 16 (cashless reader)
- **MDB Scale Factor:** 1, Decimal Places: 2
- **MQTT Broker:** `mqtt://mqtt.panamavendingmachines.com`

### Source Files

| File | Purpose |
|------|---------|
| `main/main.c` | Application entry point, task initialization |
| `main/ble_handler.c/h` | BLE GATT server, provisioning commands |
| `main/nimble.c` | NimBLE stack configuration |
| `main/mdb_handler.c/h` | MDB protocol implementation (9-bit UART) |
| `main/mqtt_handler.c` | MQTT client, telemetry publishing |
| `main/xor_crypto.c` | XOR encryption/decryption for BLE payloads |
| `main/webui_server.c/h` | Local web UI for device status |
| `main/telemetry.c` | Metrics collection |
| `main/led_status.c/h` | Status LED control |
| `main/config.h` | Global configuration constants |

### BLE Service
- **Service UUID:** `020012ac-4202-78b8-ed11-da4642c6bbb2`
- **Characteristic UUID:** `020012ac-4202-78b8-ed11-de46769cafc9`
- **IMPORTANT:** NimBLE's `BLE_UUID128_INIT` macro takes bytes in **little-endian** (reversed) order

### NVS Storage
The ESP32 stores provisioning data in NVS (Non-Volatile Storage):
- `subdomain` - Device identifier (assigned by Supabase)
- `passkey` - 18-character XOR encryption key
- `wifi_ssid` / `wifi_pass` - WiFi credentials

### Build Commands
```bash
export IDF_PATH="/Users/salomoncohen/esp/esp-idf"
export PATH="/usr/local/bin:$PATH"  # Python 3.10+ required
source $IDF_PATH/export.sh
cd vending-esp32
idf.py build
idf.py -p /dev/cu.usbmodem14201 flash monitor
```

---

## 4. vending-app (Android Mobile App)

### Overview
The Android app is the user-facing component. It handles:
- User authentication (login/register)
- BLE scanning and device connection
- Device provisioning (writing subdomain/passkey/WiFi to ESP32)
- Vending flow (product selection display, payment approval)
- Sales history viewing
- Device management

### Tech Stack
- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **BLE:** Kable library
- **HTTP:** Ktor (OkHttp engine)
- **DI:** Koin
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 35 (Android 15)
- **Package:** `xyz.vmflow.vending`

### App Architecture (MVVM)

```
UI Layer (Composables)
    ├── LoginScreen / RegisterScreen
    ├── DevicesScreen (device management + provisioning)
    ├── VendingScreen (core vending flow)
    └── SalesScreen (transaction history)
         │
ViewModel Layer
    ├── LoginViewModel
    ├── RegisterViewModel
    ├── DevicesViewModel
    ├── VendingViewModel (state machine)
    └── SalesViewModel
         │
Repository Layer
    ├── AuthRepository (login, register, token management)
    ├── DeviceRepository (CRUD for ESP32 devices)
    ├── VendingRepository (credit request API)
    └── SalesRepository (sales history)
         │
Data Layer
    ├── ApiClient (Ktor HTTP client)
    ├── BleManager (Kable BLE operations)
    └── XorCrypto (payload encryption/decryption)
```

### Vending State Machine
The core vending flow follows this state machine:

```
Idle
  └─► Scanning (BLE scan for VMflow devices)
       └─► DevicesFound (list of discovered devices)
            └─► Connecting (BLE connection in progress)
                 └─► WaitingForSelection (session started, waiting for product)
                      └─► VendRequestReceived (shows price + item number)
                           ├─► [User taps "Send Payment"]
                           │    └─► ProcessingPayment (calling backend)
                           │         └─► Dispensing (waiting for machine)
                           │              ├─► Success (product dispensed)
                           │              └─► Failure (dispensing failed)
                           └─► [User taps "Cancel"]
                                └─► Idle

SessionComplete ─► Idle (machine ended session)
Error ─► Idle (reset on error)
```

### Key API Endpoints Used
| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/auth/v1/token?grant_type=password` | POST | Login |
| `/auth/v1/signup` | POST | Register |
| `/auth/v1/token?grant_type=refresh_token` | POST | Token refresh |
| `/rest/v1/embedded` | GET | List devices |
| `/rest/v1/embedded` | POST | Register new device |
| `/rest/v1/sales` | GET | Sales history |
| `/functions/v1/request-credit` | POST | Credit request (vending) |

### Build & Install
```bash
export ANDROID_HOME=~/Library/Android/sdk
cd vending-app
./gradlew assembleDebug
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
adb shell am start -n xyz.vmflow.vending/.ui.MainActivity

# Logs
adb logcat -s VendingViewModel:D BleManager:D
```

### Test Device
- **Phone:** Samsung (device ID: `RFCY7060K9Z`)
- **Connected via:** USB, accessible via ADB
- **Note:** Do NOT take screenshots of the phone (API errors with image analysis)

---

## 5. vending-backend (VPS Services)

### Overview
The VPS hosts three services:

| Service | Technology | Port | Purpose |
|---------|-----------|------|---------|
| **Next.js API** | Next.js 15.1.6 | 3000 | Web dashboard, REST API proxy |
| **Mosquitto** | Eclipse Mosquitto | 1883/8883 | MQTT broker for ESP32 devices |
| **MQTT Bridge** | Node.js/TypeScript | N/A | Bridges MQTT events to Supabase |

### VPS Details
- **Domain:** `api.panamavendingmachines.com` (Next.js API)
- **MQTT Domain:** `mqtt.panamavendingmachines.com`
- **Deployment:** Docker containers (via EasyPanel)

### Directory Structure
```
vending-backend/
├── next-app/          # Next.js web app + API routes
│   ├── src/
│   ├── package.json
│   └── .env           # Supabase credentials
├── mqtt-bridge/       # MQTT-to-Supabase bridge service
│   ├── src/
│   └── package.json
├── mosquitto/         # MQTT broker configuration
│   └── Dockerfile
└── docker/            # Docker compose for deployment
```

### MQTT Topics
The ESP32 publishes and subscribes to MQTT topics using its subdomain:

| Topic Pattern | Direction | Purpose |
|---------------|-----------|---------|
| `vending/{subdomain}/status` | ESP32 -> Cloud | Online/offline LWT |
| `vending/{subdomain}/telemetry` | ESP32 -> Cloud | Sensor data, PAX count |
| `vending/{subdomain}/credit/request` | ESP32 -> Cloud | Credit request via MQTT |
| `vending/{subdomain}/credit/response` | Cloud -> ESP32 | Approved/denied credit |

---

## 6. Supabase (Cloud Backend)

### Project Details
- **Project ID:** `luntgcliwnyvrmrqpdts`
- **URL:** `https://luntgcliwnyvrmrqpdts.supabase.co`
- **Region:** (Supabase managed)
- **Plan:** Free tier

### Anon Key
```
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imx1bnRnY2xpd255dnJtcnFwZHRzIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzMxMDA2MzEsImV4cCI6MjA4ODY3NjYzMX0.s5mBlh0YTXi2xhfd1vOX_R9Aof6S6D5ZXc8zK_G7074
```

### Auth Users
| Email | User ID | Status |
|-------|---------|--------|
| `salomonco@gmail.com` | `f29e91b9-...` | Confirmed |
| `salomonco+1@gmail.com` | `be8bedb3-90c1-4688-81ca-ab4971799906` | Confirmed, Active |

### Edge Functions

| Function | Slug | JWT Verification | Status |
|----------|------|------------------|--------|
| **request-credit** | `request-credit` | Manual (verify_jwt=false) | Active (v2) |

The `request-credit` function handles the BLE vending payment flow:
1. Verifies user JWT via `supabase.auth.getUser()`
2. Decodes Base64 BLE payload
3. Looks up device passkey from `embedded` table (using service role)
4. XOR-decrypts and validates checksum + timestamp
5. Inserts sale record into `sales` table (using service role)
6. Re-encrypts payload with CMD_APPROVE_VEND (0x03)
7. Returns Base64-encoded approval payload + sale ID

**Important:** Originally deployed with `verify_jwt: true`, which caused persistent 401 errors because the Supabase gateway rejected valid user JWTs. Redeployed as v2 with `verify_jwt: false` and manual auth inside the function. The function now uses the service role key for database operations to bypass RLS.

### RLS (Row Level Security)
All public tables have RLS enabled. Policies filter by `owner_id = auth.uid()`, meaning users can only see their own data. The Edge Function uses the service role key to bypass RLS for inserts.

---

## 7. BLE Communication Protocol

### Payload Format
All BLE communication uses a **19-byte XOR-encrypted payload**:

```
Byte Layout (19 bytes total):
[CMD(1)] [VER(1)] [ITEM_PRICE(4,u32BE)] [ITEM_NUM(2,u16BE)] [TIMESTAMP(4,i32BE)] [PAX_COUNT(2,u16BE)] [RANDOM(4)] [CHK(1)]

Encryption:
- Byte 0 (CMD): Unencrypted
- Bytes 1-18: XOR'd with 18-byte device passkey
- Byte 18 (CHK): Checksum = sum of bytes 0-17 & 0xFF (computed before encryption)
```

### Command Bytes (App -> ESP32)

| Byte | Name | Description |
|------|------|-------------|
| 0x00 | SET_SUBDOMAIN | Write device subdomain ID |
| 0x01 | SET_PASSKEY | Write 18-char encryption key |
| 0x02 | BEGIN_SESSION | Start a vending session |
| 0x03 | APPROVE_VEND | Approve product dispensing |
| 0x04 | CLOSE_SESSION | End the vending session |
| 0x06 | SET_WIFI_SSID | Write WiFi network name |
| 0x07 | SET_WIFI_PASS | Write WiFi password |

### Event Bytes (ESP32 -> App via BLE Notifications)

| Byte | Name | Description |
|------|------|-------------|
| 0x0A | VEND_REQUEST | Product selected, contains price + item number |
| 0x0B | VEND_SUCCESS | Product dispensed successfully |
| 0x0C | VEND_FAILURE | Dispensing failed |
| 0x0D | SESSION_COMPLETE | Session ended by machine |

### Price Format
- Raw value in the payload is in **cents** (e.g., 150 = $1.50)
- `displayPrice = itemPrice / 100.0`
- Scale factor: 1, Decimal places: 2

### XOR Encryption/Decryption
```
Encrypt:
1. Set command byte (byte 0)
2. Fill data fields (bytes 1-17)
3. Compute checksum: sum bytes 0-17, mask with 0xFF, store in byte 18
4. XOR bytes 1-18 with passkey characters

Decrypt:
1. XOR bytes 1-18 with passkey characters (reverse the encryption)
2. Validate checksum: sum bytes 0-17, compare with byte 18
3. Validate timestamp: must be within 8 seconds of current time
4. Extract fields from decrypted bytes
```

---

## 8. Vending Flow (End-to-End)

### Step-by-Step Flow

```
1. USER opens app, logs in
   └─ App: POST /auth/v1/token -> gets access_token + refresh_token

2. USER taps "Scan" on VendingScreen
   └─ App: BLE scan for devices advertising VMflow service UUID
   └─ ESP32: BLE advertising with name "VMflow-{subdomain}"

3. USER selects a device from the list
   └─ App: Fetches device passkey from GET /rest/v1/embedded
   └─ App: BLE connect to device
   └─ App: Writes BEGIN_SESSION (0x02) to BLE characteristic
   └─ ESP32: Sends MDB BEGIN_SESSION to vending machine

4. USER selects a product ON THE VENDING MACHINE (physical button press)
   └─ Vending Machine: Sends MDB VEND_REQUEST to ESP32
   └─ ESP32: Creates 19-byte XOR-encrypted payload with price + item number
   └─ ESP32: Sends BLE notification (0x0A) to app

5. APP receives notification, decrypts locally with stored passkey
   └─ App: Shows "Product Selected" screen with price and item number
   └─ App: User sees "Item #3 - $1.50" and "Send Payment" button

6. USER taps "Send Payment"
   └─ App: Base64-encodes the raw BLE payload
   └─ App: POST /functions/v1/request-credit with {payload, subdomain}
   └─ Edge Function: Decrypts, validates, records sale, re-encrypts with 0x03
   └─ App: Receives approval payload

7. APP writes approval payload to BLE characteristic
   └─ ESP32: Decrypts, sends MDB APPROVE_VEND to vending machine
   └─ Vending Machine: Dispenses product

8. ESP32 sends BLE notification:
   └─ 0x0B (VEND_SUCCESS): App shows "Success!"
   └─ 0x0C (VEND_FAILURE): App shows "Failed"
   └─ 0x0D (SESSION_COMPLETE): App disconnects and resets
```

### Sequence Diagram

```
  App              ESP32            Vending Machine      Supabase
   |                 |                    |                  |
   |--BLE Connect--->|                    |                  |
   |--0x02 Session-->|                    |                  |
   |                 |--MDB Session------>|                  |
   |                 |                    |                  |
   |                 |   [User presses button on machine]   |
   |                 |                    |                  |
   |                 |<--MDB VendReq------|                  |
   |<-0x0A Notif-----|                    |                  |
   |                 |                    |                  |
   |  [Shows price, user taps "Send"]    |                  |
   |                 |                    |                  |
   |---POST /request-credit----------------------------->|  |
   |<--{approval payload, sales_id}----------------------|  |
   |                 |                    |                  |
   |--0x03 Approve-->|                    |                  |
   |                 |--MDB Approve------>|                  |
   |                 |                    |--Dispense------->|
   |                 |<--MDB Success------|                  |
   |<-0x0B Success---|                    |                  |
   |                 |                    |                  |
   |<-0x0D Complete--|                    |                  |
   |--Disconnect---->|                    |                  |
```

---

## 9. Infrastructure & Domains

### Active Domains & Services

| Domain | Service | Status |
|--------|---------|--------|
| `luntgcliwnyvrmrqpdts.supabase.co` | Supabase (DB, Auth, Edge Functions) | Active |
| `api.panamavendingmachines.com` | Next.js API (VPS) | Active |
| `mqtt.panamavendingmachines.com` | Mosquitto MQTT Broker (VPS) | Active |
| `vmflow.xyz` | Main website | Active |
| `vmflow.xyz/dashboard` | Web dashboard | Active |
| `install.vmflow.xyz` | ESP32 Web Installer (ESP Web Tools) | Active |

### VPS Deployment
- **Platform:** EasyPanel (Docker-based PaaS)
- **Services deployed via Docker:**
  - Next.js API container
  - Mosquitto MQTT broker container
  - MQTT Bridge container

### Supabase Configuration
- **Project URL:** `https://luntgcliwnyvrmrqpdts.supabase.co`
- **Auth:** GoTrue (email/password)
- **Database:** PostgreSQL (managed)
- **Edge Functions:** Deno runtime
- **Storage:** Not currently used

---

## 10. Database Schema

### Enums

| Enum | Values | Usage |
|------|--------|-------|
| `embedded_status` | `online`, `offline` | Device status via MQTT LWT |
| `metric_name` | `paxcounter` | Telemetry metric types |
| `sale_channel` | `ble`, `mqtt`, `cash` | How the sale was initiated |
| `metric_unit` | `-` | Metric measurement units |

### Tables

#### `embedded` (ESP32 Devices)
| Column | Type | Description |
|--------|------|-------------|
| `id` | uuid (PK) | Auto-generated |
| `owner_id` | uuid (FK -> auth.users) | Device owner, auto-set via `auth.uid()` |
| `subdomain` | bigint (identity) | Auto-incrementing device ID |
| `mac_address` | text | BLE MAC address (e.g., "1C:DB:D4:78:51:56") |
| `passkey` | text | 18-char XOR key, auto-generated from `md5(random())` |
| `status` | embedded_status | online/offline (updated by MQTT LWT) |
| `status_at` | timestamptz | Last status change |
| `machine_id` | uuid (FK -> machines) | Optional link to physical machine |

**Current data:** 1 device (subdomain=2, mac=1C:DB:D4:78:51:56)

#### `sales` (Transactions)
| Column | Type | Description |
|--------|------|-------------|
| `id` | uuid (PK) | Auto-generated |
| `embedded_id` | uuid (FK -> embedded) | Which device processed the sale |
| `machine_id` | uuid (FK -> machines) | Optional: which machine |
| `product_id` | uuid (FK -> products) | Optional: which product |
| `item_price` | double precision | Price in dollars (e.g., 1.50) |
| `item_number` | bigint | Item slot number on the machine |
| `channel` | sale_channel | ble, mqtt, or cash |
| `owner_id` | uuid (FK -> auth.users) | Device owner, auto-set |
| `lat` / `lng` | double precision | GPS coordinates (optional) |

**Current data:** 0 records (was broken due to 401 bug, now fixed)

#### Other Tables
- `machines` - Physical vending machines (name, serial_number, owner)
- `machine_models` - Machine type templates
- `model_coils` - Slot definitions per model
- `machine_coils` - Inventory per machine slot
- `products` - Items sold (name, barcode)
- `payments` - Placeholder for payment provider integration
- `metrics` - Time-series telemetry data (partitioned by year)

---

## 11. Current Status & Known Issues

### What's Working
- [x] ESP32-S3 firmware compiles and runs (ESP-IDF v6.1)
- [x] BLE advertising with VMflow service UUID
- [x] BLE provisioning (subdomain, passkey, WiFi via app)
- [x] MDB protocol communication with vending machine
- [x] Android app: login, register, device management
- [x] Android app: BLE scan, connect, start session
- [x] Android app: receive vend request, show price + item number
- [x] Android app: manual "Send Payment" approval button
- [x] Supabase: auth, device CRUD, Edge Function deployed
- [x] Edge Function: XOR decrypt, validate, record sale, re-encrypt
- [x] VPS: Mosquitto MQTT broker running
- [x] VPS: Next.js API running

### Recently Fixed
- **BLE UUID byte order** - NimBLE's `BLE_UUID128_INIT` requires reversed (little-endian) byte order
- **Provisioning error handling** - Added `executeWithRetry` for 401 token refresh
- **Vending flow** - Added local XOR decryption and manual "Send" button
- **Edge Function 401** - Redeployed with `verify_jwt: false` and manual auth (the gateway was rejecting valid JWTs)

### Pending Verification
- [ ] Sales recording in Supabase (Edge Function was redeployed, needs re-test)
- [ ] MQTT-based credit flow (alternative to BLE)
- [ ] PAX counter telemetry via MQTT

### Known Issues
1. **Sales table is empty** - The `request-credit` Edge Function was returning 401 for all requests due to `verify_jwt: true` gateway issue. Redeployed as v2 with manual auth. Needs re-testing.
2. **MockPaymentProvider** - The app uses a mock payment provider. Real payment integration (Stripe, Square, etc.) is not yet implemented.
3. **No GPS data** - The credit request currently sends `lat=null, lng=null`. GPS permission and location retrieval not yet implemented.
4. **Timestamp window** - The XOR payload timestamp must be within 8 seconds of server time. ESP32 and server clocks must be synchronized (ESP32 gets time via NTP over WiFi).

---

## 12. Development Environment Setup

### Prerequisites
| Tool | Version | Path |
|------|---------|------|
| ESP-IDF | v6.1 | `/Users/salomoncohen/esp/esp-idf` |
| Python | 3.10+ | `/usr/local/bin/python3` |
| Android SDK | Latest | `~/Library/Android/sdk` |
| ADB | Latest | `~/Library/Android/sdk/platform-tools/adb` |
| JDK | 17 | System default |
| Node.js | 18+ | System default |

### Environment Variables
```bash
# ESP-IDF
export IDF_PATH="/Users/salomoncohen/esp/esp-idf"
export PATH="/usr/local/bin:$PATH"
source $IDF_PATH/export.sh

# Android
export ANDROID_HOME=~/Library/Android/sdk
```

### Hardware
- **ESP32-S3** connected via USB at `/dev/cu.usbmodem14201`
- **Samsung phone** (device ID: `RFCY7060K9Z`) connected via USB

### Quick Commands

```bash
# Build and flash ESP32
cd vending-esp32 && idf.py build && idf.py -p /dev/cu.usbmodem14201 flash monitor

# Build and install Android app
cd vending-app && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Monitor Android logs
adb logcat -s VendingViewModel:D BleManager:D

# Clear app data (force fresh login)
adb shell pm clear xyz.vmflow.vending

# Check Supabase Edge Function logs
# Use Supabase MCP tool: get_logs(project_id="luntgcliwnyvrmrqpdts", service="edge-function")
```

---

## 13. Key File Reference

### ESP32 Firmware
| File | Path | Purpose |
|------|------|---------|
| Main entry | `vending-esp32/main/main.c` | App start, task init |
| BLE handler | `vending-esp32/main/ble_handler.c` | GATT server, provisioning |
| MDB handler | `vending-esp32/main/mdb_handler.c` | MDB protocol |
| XOR crypto | `vending-esp32/main/xor_crypto.c` | Payload encryption |
| Config | `vending-esp32/main/config.h` | Constants |

### Android App
| File | Path | Purpose |
|------|------|---------|
| DI Module | `app/.../di/AppModule.kt` | Koin dependency graph |
| API Config | `app/.../data/remote/ApiClient.kt` | URLs, keys, HTTP client |
| Auth Repo | `app/.../data/repository/AuthRepository.kt` | Login, register, tokens |
| Device Repo | `app/.../data/repository/DeviceRepository.kt` | Device CRUD |
| Vending Repo | `app/.../data/repository/VendingRepository.kt` | Credit request |
| Sales Repo | `app/.../data/repository/SalesRepository.kt` | Sales history |
| BLE Manager | `app/.../bluetooth/BleManager.kt` | Kable BLE operations |
| XOR Crypto | `app/.../bluetooth/XorCrypto.kt` | Payload encrypt/decrypt |
| BLE Constants | `app/.../bluetooth/BleConstants.kt` | UUIDs, commands, events |
| Vend Request | `app/.../domain/model/VendRequest.kt` | Decoded payload model |
| Vending VM | `app/.../ui/vending/VendingViewModel.kt` | Core state machine |
| Vending UI | `app/.../ui/vending/VendingScreen.kt` | Vending flow screens |

### Supabase
| File | Path | Purpose |
|------|------|---------|
| Migration | `supabase/migrations/20260309000000_initial_schema.sql` | DB schema |
| Edge Fn | `supabase/functions/request-credit/index.ts` | Credit request handler |
| Config | `supabase/config.toml` | Local dev config |

### Backend (VPS)
| File | Path | Purpose |
|------|------|---------|
| Next.js App | `vending-backend/next-app/` | Web dashboard + API |
| MQTT Bridge | `vending-backend/mqtt-bridge/` | MQTT-Supabase sync |
| Mosquitto | `vending-backend/mosquitto/` | MQTT broker config |
