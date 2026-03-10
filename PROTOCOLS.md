# Communication Protocols & System Architecture

## Overview

This document defines the communication protocols between the three system components:
1. **ESP32-S3 Firmware** - MDB slave device inside the vending machine
2. **Android App** - User-facing mobile application (BLE + REST)
3. **Backend Server** - Next.js API + Mosquitto MQTT + Supabase Cloud

```
┌──────────────┐     BLE GATT      ┌───────────────┐     REST/HTTPS     ┌─────────────────┐
│  Android App │◄──────────────────►│  ESP32-S3     │                    │  Backend (VPS)  │
│              │                    │  Firmware     │◄──MQTT──────────────│                 │
│              │────REST/HTTPS─────►│               │                    │  Next.js API    │
│              │                    │               │                    │  Mosquitto      │
└──────────────┘                    └───────┬───────┘                    │  MQTT Bridge    │
                                           │ MDB 9-bit UART            └────────┬────────┘
                                    ┌──────▼───────┐                            │
                                    │   Vending    │                    ┌───────▼────────┐
                                    │   Machine    │                    │ Supabase Cloud │
                                    │   (VMC)     │                    │ (PostgreSQL +  │
                                    └──────────────┘                    │  GoTrue Auth)  │
                                                                        └────────────────┘
```

---

## 1. MDB Protocol (ESP32 ↔ Vending Machine)

### Physical Layer
- **Interface**: 9-bit UART via GPIO bit-banging
- **Pins**: GPIO4 (RX), GPIO5 (TX)
- **Baud Rate**: 9600
- **Bit Timing**: 104µs per bit
- **Mode Bit**: Bit 8 indicates address/command (1) vs data (0)

### Message Format
```
┌──────────┬──────────┬────────────────┬──────────┐
│ Address  │ Command  │ Data (0-N)     │ Checksum │
│ (1 byte) │ (1 byte) │ (variable)     │ (1 byte) │
└──────────┴──────────┴────────────────┴──────────┘
Address byte: Bit 8 = mode, Bits 6-0 = peripheral address
Checksum: sum of all preceding bytes (lower 8 bits)
```

### Acknowledgment Codes
| Code | Name | Value |
|------|------|-------|
| ACK | Acknowledgment | `0x00` |
| RET | Retransmit | `0xAA` |
| NAK | Negative Ack | `0xFF` |

### Commands (VMC → ESP32)

| Command | Code | Sub-command | Description |
|---------|------|-------------|-------------|
| RESET | `0x00` | - | Reset device → INACTIVE_STATE |
| SETUP | `0x01` | `0x00` CONFIG_DATA | VMC sends capabilities, ESP32 responds with reader config |
| | | `0x01` MAX_MIN_PRICES | VMC sends price constraints |
| POLL | `0x02` | - | VMC polls for status/events |
| VEND | `0x03` | `0x00` VEND_REQUEST | Item price (2B) + item number (2B) |
| | | `0x01` VEND_CANCEL | VMC cancels vend |
| | | `0x02` VEND_SUCCESS | Item number (2B) - dispensed OK |
| | | `0x03` VEND_FAILURE | Dispense failed |
| | | `0x04` SESSION_COMPLETE | Session ending |
| | | `0x05` CASH_SALE | Cash transaction occurred |
| READER | `0x04` | `0x00` DISABLE | Disable reader |
| | | `0x01` ENABLE | Enable reader |
| | | `0x02` CANCEL | Cancel pending operation |
| EXPANSION | `0x07` | `0x00` REQUEST_ID | Request 30-byte peripheral ID |

### POLL Responses (ESP32 → VMC)

| Response | Code | Payload | When |
|----------|------|---------|------|
| Just Reset | `0x00` | - | After RESET, in INACTIVE_STATE |
| Begin Session | `0x03` | fundsAvailable (2B big-endian) | Credit received |
| Session Cancel | `0x04` | - | Session cancelled |
| Vend Approved | `0x05` | approvedPrice (2B big-endian) | After approve |
| Vend Denied | `0x06` | - | Insufficient funds |
| End Session | `0x07` | - | Session complete |
| Out of Sequence | `0x0B` | - | Invalid command for state |

### State Machine
```
                RESET
                  │
                  ▼
          ┌───────────────┐
          │ INACTIVE_STATE│
          └───────┬───────┘
                  │ SETUP CONFIG_DATA
                  ▼
          ┌───────────────┐
          │ DISABLED_STATE│◄──── READER_DISABLE
          └───────┬───────┘
                  │ READER_ENABLE
                  ▼
          ┌───────────────┐
  ┌──────►│ ENABLED_STATE │◄──── SESSION_COMPLETE / Timeout (60s)
  │       └───────┬───────┘
  │               │ Session begin (credit received)
  │               ▼
  │       ┌───────────────┐
  │       │  IDLE_STATE   │
  │       └───────┬───────┘
  │               │ VEND_REQUEST
  │               ▼
  │       ┌───────────────┐
  └───────│  VEND_STATE   │──── VEND_SUCCESS/FAILURE → back to IDLE
          └───────────────┘
```

---

## 2. BLE GATT Protocol (ESP32 ↔ Android)

### Service Configuration
- **Service UUID**: `020012ac-4202-78b8-ed11-da4642c6bbb2`
- **Characteristic UUID**: `020012ac-4202-78b8-ed11-de46769cafc9`
- **Android UUID** (reversed byte order): `c9af9c76-46de-11ed-b878-0242ac120002`
- **Properties**: READ | WRITE | NOTIFY
- **Device Name Pattern**: `{subdomain}.panamavendingmachines.com` (e.g., `123456.panamavendingmachines.com`)
- **Unconfigured Device Name**: `0.panamavendingmachines.com`

### Write Commands (Android → ESP32)

| Cmd Byte | Name | Payload | Description |
|----------|------|---------|-------------|
| `0x00` | SET_SUBDOMAIN | 22 bytes: `[0x00, subdomain_string..., 0x00]` | Set device subdomain (stored in NVS) |
| `0x01` | SET_PASSKEY | 22 bytes: `[0x01, passkey_string..., 0x00]` | Set 18-byte encryption key (stored in NVS) |
| `0x02` | START_SESSION | 1 byte: `[0x02]` | Begin vending session (funds=0xFFFF unlimited) |
| `0x03` | APPROVE_VEND | 19 bytes: XOR-encrypted payload | Approve pending vend (validated with passkey) |
| `0x04` | CLOSE_SESSION | 1 byte: `[0x04]` | Cancel/close active session |
| `0x06` | SET_WIFI_SSID | up to 22 bytes: `[0x06, ssid..., 0x00]` | Configure WiFi SSID |
| `0x07` | SET_WIFI_PASS | up to 63 bytes: `[0x07, password..., 0x00]` | Configure WiFi password |

### Notification Events (ESP32 → Android)

| Cmd Byte | Name | Payload | Description |
|----------|------|---------|-------------|
| `0x0A` | VEND_REQUEST | 19 bytes: XOR-encrypted (price + item#) | User selected product, payment needed |
| `0x0B` | VEND_SUCCESS | 19 bytes: XOR-encrypted | Product dispensed successfully |
| `0x0C` | VEND_FAILURE | 19 bytes: XOR-encrypted | Dispensing failed |
| `0x0D` | SESSION_COMPLETE | 19 bytes: XOR-encrypted | Session ended |
| `0x22` | PAX_COUNTER | 19 bytes: XOR-encrypted (pax count) | Foot traffic count |

---

## 3. XOR Encryption Protocol

### Payload Format (19 bytes)
```
Byte:  0     1     2-5          6-7         8-11        12-13       14-17     18
Field: CMD   VER   ITEM_PRICE   ITEM_NUM    TIMESTAMP   PAX_COUNT   RANDOM    CHK
Size:  1B    1B    4B (u32 BE)  2B (u16 BE) 4B (i32 BE) 2B (u16 BE) 4B        1B
```

- **CMD** (byte 0): Command type - NOT encrypted
- **VER** (byte 1): Protocol version (always `0x01`)
- **ITEM_PRICE** (bytes 2-5): Price in scale factor units, big-endian uint32
- **ITEM_NUM** (bytes 6-7): Item/slot number, big-endian uint16
- **TIMESTAMP** (bytes 8-11): Unix timestamp in seconds, big-endian int32
- **PAX_COUNT** (bytes 12-13): Phone count from BLE scan, big-endian uint16
- **RANDOM** (bytes 14-17): Random padding (from `esp_fill_random`)
- **CHK** (byte 18): Checksum

### Encryption Process
```
1. Fill bytes 1-17 with random data
2. Set byte 0 = command code
3. Set byte 1 = 0x01 (version)
4. Set bytes 2-5 = itemPrice (big-endian u32)
5. Set bytes 6-7 = itemNumber (big-endian u16)
6. Set bytes 8-11 = current Unix timestamp (big-endian i32)
7. Set bytes 12-13 = paxCounter (big-endian u16)
8. Calculate checksum: sum(bytes[0..17]) & 0xFF → byte 18
9. XOR encrypt: for(k=0; k<18; k++) payload[k+1] ^= passkey[k]
```

### Decryption Process
```
1. XOR decrypt: for(k=0; k<18; k++) payload[k+1] ^= passkey[k]
2. Calculate checksum: sum(bytes[0..17]) & 0xFF
3. Validate: calculated checksum == payload[18]
4. Validate timestamp: abs(now - payload_timestamp) <= 8 seconds
5. Extract itemPrice from bytes 2-5 (big-endian u32)
6. Extract itemNumber from bytes 6-7 (big-endian u16)
```

### Passkey
- **Length**: 18 bytes (stored as ASCII string)
- **Storage**: NVS on ESP32, `embedded.passkey` column in database
- **Configuration**: Set via BLE command `0x01` during device provisioning

### Scale Factor
Prices are stored in scale factor units. Conversion:
- **To display**: `displayPrice = rawPrice / (scaleFactor * 10^decimalPlaces)`
- **Default**: scaleFactor=1, decimalPlaces=2 → `rawPrice / 100`
- **Example**: rawPrice=150 → $1.50

---

## 4. MQTT Protocol (ESP32 ↔ Backend)

### Broker Configuration
- **Current**: `mqtt://mqtt.panamavendingmachines.com` (self-hosted Mosquitto)
- **New**: `mqtt://<VPS_IP>:1883` (Mosquitto on Hostinger VPS)
- **Credentials**: username/password (configurable via NVS)

### Topic Structure

| Topic | Direction | Payload | Description |
|-------|-----------|---------|-------------|
| `domain.panamavendingmachines.com/{subdomain}/status` | ESP32 → Broker | `"online"` or `"offline"` (LWT) | Device online status |
| `domain.panamavendingmachines.com/{subdomain}/sale` | ESP32 → Broker | 19B XOR-encrypted (cmd=0x05) | Cash/card sale report |
| `domain.panamavendingmachines.com/{subdomain}/dex` | ESP32 → Broker | Raw telemetry bytes | DEX/DDCMP data |
| `domain.panamavendingmachines.com/{subdomain}/paxcounter` | ESP32 → Broker | 19B XOR-encrypted (cmd=0x22) | Foot traffic count |
| `{subdomain}.panamavendingmachines.com/credit` | Broker → ESP32 | 19B XOR-encrypted | Credit transfer to device |

### ESP32 Subscriptions
- Subscribes to: `{subdomain}.panamavendingmachines.com/#`
- Matches credit messages by topic suffix `/credit`

### LWT (Last Will and Testament)
- **Topic**: `domain.panamavendingmachines.com/{subdomain}/status`
- **Message**: `"offline"`
- **QoS**: 0
- **Retain**: false

---

## 5. REST API (Android ↔ Backend)

### Authentication
- **Method**: JWT Bearer token via Supabase GoTrue
- **Header**: `Authorization: Bearer <jwt_token>`
- **API Key**: `apikey: <SUPABASE_ANON_KEY>` (for Supabase Cloud direct access)

### Endpoints

| Method | Path | Request Body | Response | Description |
|--------|------|-------------|----------|-------------|
| POST | `/api/auth/login` | `{ email, password }` | `{ access_token, refresh_token, user }` | Login |
| POST | `/api/auth/register` | `{ email, password, full_name }` | `{ access_token, user }` | Register |
| POST | `/api/auth/refresh` | `{ refresh_token }` | `{ access_token, refresh_token }` | Refresh JWT |
| GET | `/api/devices` | - | `[{ id, subdomain, passkey, mac_address, status }]` | List devices |
| POST | `/api/devices` | `{ mac_address }` | `{ id, subdomain, passkey, mac_address }` | Register device |
| GET | `/api/sales` | query: `?order=created_at.desc` | `[{ id, channel, item_number, item_price, created_at, embedded }]` | List sales |
| POST | `/api/credit/request` | `{ payload: base64, subdomain, lat?, lng? }` | `{ payload: base64, sales_id }` | BLE credit flow |
| POST | `/api/credit/send` | `{ amount, subdomain }` | `{ success: true }` | MQTT credit push |
| GET | `/api/metrics` | query: `?subdomain=X` | `[{ timestamp, value, type }]` | Paxcounter data |

### `/api/credit/request` Flow (Critical Path)
```
Android sends:
  POST /api/credit/request
  Body: { payload: "base64...", subdomain: "123456", lat: 40.71, lng: -74.00 }

Backend:
  1. Verify JWT → get user_id
  2. Base64 decode payload → 19 bytes
  3. SELECT passkey FROM embedded WHERE subdomain = '123456'
  4. XOR decrypt payload with passkey
  5. Validate checksum + timestamp
  6. Extract itemPrice, itemNumber
  7. INSERT INTO sales (embedded_id, item_number, item_price, channel, lat, lng)
  8. payload[0] = 0x03 (approve command)
  9. Recalculate checksum
  10. XOR re-encrypt
  11. Return { payload: base64(encrypted), sales_id }

Android:
  Write payload bytes to BLE characteristic → ESP32 approves vend
```

---

## 6. Complete Vending Flow (Sequence)

```
User          Android App        ESP32           VMC (Vending Machine)    Backend
 │                │                │                │                       │
 │  Open app      │                │                │                       │
 │──────────────►│                │                │                       │
 │                │  BLE Scan      │                │                       │
 │                │───────────────►│                │                       │
 │                │  Found device  │                │                       │
 │                │◄───────────────│                │                       │
 │  Tap device    │                │                │                       │
 │──────────────►│  BLE Connect   │                │                       │
 │                │───────────────►│                │                       │
 │                │  Write 0x02    │                │                       │
 │                │  (begin sess)  │                │                       │
 │                │───────────────►│  Session begin │                       │
 │                │                │  POLL → 0x03   │                       │
 │                │                │───────────────►│                       │
 │                │                │                │                       │
 │  Select product on machine     │                │                       │
 │────────────────────────────────────────────────►│                       │
 │                │                │  VEND_REQUEST  │                       │
 │                │                │◄───────────────│                       │
 │                │  Notify 0x0A   │  (price+item)  │                       │
 │                │  (XOR payload) │                │                       │
 │                │◄───────────────│                │                       │
 │                │                │                │                       │
 │                │  POST /api/credit/request       │                       │
 │                │─────────────────────────────────────────────────────────►│
 │                │                │                │     Decrypt, validate │
 │                │                │                │     Record sale       │
 │                │                │                │     Re-encrypt (0x03) │
 │                │  Response (approved payload)    │                       │
 │                │◄─────────────────────────────────────────────────────────│
 │                │                │                │                       │
 │                │  Write 0x03    │                │                       │
 │                │  (approve)     │                │                       │
 │                │───────────────►│  POLL → 0x05   │                       │
 │                │                │  (approved)    │                       │
 │                │                │───────────────►│                       │
 │                │                │                │  Dispense product     │
 │                │                │                │────────┐              │
 │                │                │                │        │              │
 │                │                │  VEND_SUCCESS  │◄───────┘              │
 │                │  Notify 0x0B   │◄───────────────│                       │
 │                │◄───────────────│                │                       │
 │  "Dispensed!"  │                │                │                       │
 │◄──────────────│                │                │                       │
 │                │                │  SESSION_COMP  │                       │
 │                │  Notify 0x0D   │◄───────────────│                       │
 │                │◄───────────────│                │                       │
 │                │  Disconnect    │                │                       │
 │                │───────────────►│                │                       │
```

---

## 7. Database Schema (Key Tables)

### `embedded` (ESP32 Devices)
| Column | Type | Description |
|--------|------|-------------|
| id | uuid | Primary key |
| subdomain | text | Device identifier (e.g., "123456") |
| passkey | text | 18-char XOR encryption key |
| mac_address | text | BLE MAC address |
| status | text | "online" or "offline" |
| owner_id | uuid | FK to auth.users |
| created_at | timestamptz | |

### `sales` (Transactions)
| Column | Type | Description |
|--------|------|-------------|
| id | uuid | Primary key |
| embedded_id | uuid | FK to embedded |
| channel | text | "ble", "mqtt", "cash" |
| item_number | integer | Vend slot number |
| item_price | numeric | Display price (after scale factor) |
| lat | numeric | GPS latitude (BLE transactions) |
| lng | numeric | GPS longitude (BLE transactions) |
| owner_id | uuid | FK to auth.users |
| created_at | timestamptz | |

### `metrics` (Time-Series)
| Column | Type | Description |
|--------|------|-------------|
| id | bigint | Primary key |
| embedded_id | uuid | FK to embedded |
| type | text | "paxcounter" |
| value | numeric | Metric value |
| created_at | timestamptz | Partitioned by year |
