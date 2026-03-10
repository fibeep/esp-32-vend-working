# BLE Communication Protocol

This document details the BLE GATT protocol between the Android app and the ESP32-S3 vending machine controller.

## GATT Configuration

| Property | Value |
|----------|-------|
| Service UUID | `020012ac-4202-78b8-ed11-da4642c6bbb2` |
| Characteristic UUID | `020012ac-4202-78b8-ed11-de46769cafc9` |
| Properties | READ, WRITE, NOTIFY |
| Device Name Pattern | `{subdomain}.panamavendingmachines.com` |
| Unconfigured Name | `0.panamavendingmachines.com` |

## Command Reference

### Write Commands (Android -> ESP32)

| Byte | Name | Payload Size | Description |
|------|------|-------------|-------------|
| 0x00 | SET_SUBDOMAIN | 22 bytes | `[0x00, subdomain..., 0x00]` |
| 0x01 | SET_PASSKEY | 22 bytes | `[0x01, passkey..., 0x00]` |
| 0x02 | START_SESSION | 1 byte | `[0x02]` - Begins vending session |
| 0x03 | APPROVE_VEND | 19 bytes | XOR-encrypted approval payload |
| 0x04 | CLOSE_SESSION | 1 byte | `[0x04]` - Cancels/closes session |
| 0x06 | SET_WIFI_SSID | up to 22 bytes | `[0x06, ssid..., 0x00]` |
| 0x07 | SET_WIFI_PASS | up to 63 bytes | `[0x07, password..., 0x00]` |

### Notification Events (ESP32 -> Android)

| Byte | Name | Payload | Description |
|------|------|---------|-------------|
| 0x0A | VEND_REQUEST | 19 bytes XOR-encrypted | Product selected, payment needed |
| 0x0B | VEND_SUCCESS | 19 bytes XOR-encrypted | Product dispensed OK |
| 0x0C | VEND_FAILURE | 19 bytes XOR-encrypted | Dispense failed |
| 0x0D | SESSION_COMPLETE | 19 bytes XOR-encrypted | Session ended |

## XOR Encryption

### Payload Format (19 bytes)

```
Byte:  0     1     2-5          6-7         8-11        12-13       14-17     18
Field: CMD   VER   ITEM_PRICE   ITEM_NUM    TIMESTAMP   PAX_COUNT   RANDOM    CHK
Size:  1B    1B    4B (u32 BE)  2B (u16 BE) 4B (i32 BE) 2B (u16 BE) 4B        1B
```

- **CMD** (byte 0): Command type. NOT encrypted.
- **VER** (byte 1): Protocol version, always 0x01.
- **ITEM_PRICE** (bytes 2-5): Price in scale factor units, big-endian uint32.
- **ITEM_NUM** (bytes 6-7): Item/slot number, big-endian uint16.
- **TIMESTAMP** (bytes 8-11): Unix timestamp in seconds, big-endian int32.
- **PAX_COUNT** (bytes 12-13): Foot traffic count, big-endian uint16.
- **RANDOM** (bytes 14-17): Random padding for security.
- **CHK** (byte 18): Checksum byte.

### Encryption Process

```
1. Build plaintext payload (bytes 0-17)
2. Calculate checksum: sum(bytes[0..17]) & 0xFF -> byte 18
3. XOR encrypt: for(k=0; k<18; k++) payload[k+1] ^= passkey[k]
```

Key points:
- Byte 0 (command) is NEVER encrypted
- The passkey is 18 ASCII characters
- The checksum covers bytes 0-17 before encryption

### Decryption Process

```
1. XOR decrypt: for(k=0; k<18; k++) payload[k+1] ^= passkey[k]
2. Calculate checksum: sum(bytes[0..17]) & 0xFF
3. Validate: calculated checksum == payload[18]
4. Extract fields from decrypted bytes
```

### Price Conversion

Prices are stored in scale factor units. Default conversion:

```
displayPrice = rawPrice / 100.0
```

Examples:
- rawPrice=150 -> $1.50
- rawPrice=250 -> $2.50
- rawPrice=99 -> $0.99

## Complete Vending Flow

```
     Android App                   ESP32                    Vending Machine
         |                           |                           |
    [1] Scan for BLE devices         |                           |
         |--- BLE Scan ------------->|                           |
         |<-- Advertisement ---------|                           |
         |    "123456.panamavendingmachines.com"    |                           |
         |                           |                           |
    [2] Connect to device            |                           |
         |--- BLE Connect ---------->|                           |
         |<-- Connected -------------|                           |
         |                           |                           |
    [3] Start vending session        |                           |
         |--- Write [0x02] --------->|                           |
         |                           |--- POLL: Begin Session -->|
         |                           |    (funds = 0xFFFF)       |
         |                           |                           |
    [4] User selects product         |                           |
         |                           |<-- VEND_REQUEST ----------|
         |                           |    (price + item#)        |
         |<-- Notify [0x0A] ---------|                           |
         |   (19B XOR-encrypted)     |                           |
         |                           |                           |
    [5] Request credit from backend  |                           |
         |--- POST /api/credit ----->| (via HTTPS)               |
         |   { payload: base64,      |                           |
         |     subdomain: "123456" } |                           |
         |                           |                           |
    [6] Backend validates & approves |                           |
         |<-- Response --------------|                           |
         |   { payload: base64 }     |                           |
         |   (cmd=0x03, re-encrypted)|                           |
         |                           |                           |
    [7] Write approval to BLE       |                           |
         |--- Write [0x03 payload] ->|                           |
         |                           |--- POLL: Vend Approved -->|
         |                           |    (approved price)       |
         |                           |                           |
    [8] Machine dispenses product    |                           |
         |                           |                           |
         |                           |<-- VEND_SUCCESS ----------|
         |<-- Notify [0x0B] ---------|                           |
         |                           |                           |
    [9] Session ends                 |                           |
         |                           |<-- SESSION_COMPLETE ------|
         |<-- Notify [0x0D] ---------|                           |
         |                           |                           |
   [10] Disconnect                   |                           |
         |--- BLE Disconnect ------->|                           |
```

## Device Provisioning Flow

New (unconfigured) devices advertise as "0.panamavendingmachines.com" and need three pieces of configuration:

```
     Android App                   ESP32 (0.panamavendingmachines.com)
         |                           |
    [1] Scan for "0.panamavendingmachines.com"      |
         |--- BLE Scan ------------->|
         |<-- Advertisement ---------|
         |                           |
    [2] Register with backend        |
         |--- POST /api/devices ---->| (via HTTPS)
         |   { mac_address: "AA:..." }
         |<-- { subdomain, passkey } |
         |                           |
    [3] Connect to device            |
         |--- BLE Connect ---------->|
         |                           |
    [4] Set subdomain                |
         |--- Write [0x00, "123456", 0x00] -->|
         |                           |        stored in NVS
         |                           |
    [5] Set passkey                  |
         |--- Write [0x01, passkey, 0x00] --->|
         |                           |        stored in NVS
         |                           |
    [6] (Optional) Set WiFi          |
         |--- Write [0x06, ssid, 0x00] ------>|
         |--- Write [0x07, password, 0x00] -->|
         |                           |
    [7] Device reboots with new name |
         |--- BLE Disconnect ------->|
         |                           |
         | Now advertises as "123456.panamavendingmachines.com"
```

## Error Handling

| Scenario | App Behavior |
|----------|-------------|
| BLE scan timeout | Show "No devices found" message |
| Connection failure | Show error, return to Idle state |
| Payment failure | Send CLOSE_SESSION (0x04), show error |
| Credit request 401 | Auto-refresh JWT, retry once |
| Credit request failure | Send CLOSE_SESSION, show error |
| BLE write failure | Show error, attempt disconnect |
| VEND_FAILURE (0x0C) | Show "Dispense failed" message |
| Connection lost | Show "Connection lost" error |

## Security Considerations

1. The XOR passkey is stored only in the ESP32 NVS and the backend database. The Android app never persists passkeys locally.

2. All payloads include a timestamp that the backend validates (within 8 seconds of current time) to prevent replay attacks.

3. The checksum prevents bit-flip corruption during BLE transmission.

4. The random padding (bytes 14-17) ensures that identical transactions produce different encrypted payloads.

5. All backend communication uses HTTPS with JWT authentication.
