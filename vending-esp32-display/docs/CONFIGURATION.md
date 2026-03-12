# Device Configuration Guide

## Configuration Methods

The ESP32 cashless device can be configured through three interfaces:

1. **BLE (Bluetooth Low Energy)** - Primary method, used by the Android app
2. **Web UI (Captive Portal)** - Fallback when WiFi is not configured
3. **Build-time (menuconfig)** - For firmware defaults

## BLE Configuration

### Prerequisites
- VMflow Android app (or any BLE client supporting GATT writes)
- The device must be within BLE range

### Service Details
- **Service UUID**: `020012ac-4202-78b8-ed11-da4642c6bbb2`
- **Characteristic UUID**: `020012ac-4202-78b8-ed11-de46769cafc9`
- **Properties**: READ, WRITE, NOTIFY

### Provisioning Commands

#### 0x00 - Set Subdomain
Sets the unique device identifier used in MQTT topics and BLE device name.

**Payload**: `[0x00, subdomain_bytes..., 0x00]`

**Example** (set subdomain to "123456"):
```
00 31 32 33 34 35 36 00
```

**Notes**:
- Maximum 31 characters
- Can only be set once (factory provisioning). To change, erase NVS flash first.
- After setting, the BLE name changes from "0.panamavendingmachines.com" to "{subdomain}.panamavendingmachines.com"

#### 0x01 - Set Passkey
Sets the 18-byte XOR encryption key used for all payload encryption.

**Payload**: `[0x01, passkey_bytes(18)..., 0x00]`

**Notes**:
- Exactly 18 characters required
- Can only be set once. To change, erase NVS flash first.
- Must match the passkey stored in the backend database for the device

#### 0x02 - Start Session
Begins a vending session with unlimited funds (0xFFFF).

**Payload**: `[0x02]`

**Notes**:
- The VMC will display available products
- The Android app will receive VEND_REQUEST notifications when the user selects a product

#### 0x03 - Approve Vend
Approves a pending vend operation.

**Payload**: 19 bytes XOR-encrypted (obtained from backend API)

**Notes**:
- The app forwards the backend's approval payload directly
- The firmware validates checksum and timestamp (must be within 8 seconds)

#### 0x04 - Close Session
Cancels or closes the current vending session.

**Payload**: `[0x04]`

#### 0x06 - Set WiFi SSID
Configures the WiFi network name.

**Payload**: `[0x06, ssid_bytes..., 0x00]`

**Notes**:
- Disconnects from current network immediately
- WiFi reconnection happens when password is set (0x07)

#### 0x07 - Set WiFi Password
Configures the WiFi password and triggers connection.

**Payload**: `[0x07, password_bytes..., 0x00]`

**Notes**:
- Send after 0x06 (Set SSID)
- Triggers immediate WiFi connection attempt

#### 0x08 - Set MQTT Broker URI (NEW)
Configures the MQTT broker address.

**Payload**: `[0x08, uri_bytes..., 0x00]`

**Examples**:
```
08 6D 71 74 74 3A 2F 2F 31 39 32 2E 31 36 38 2E 31 2E 31 30 30 00
                    (mqtt://192.168.1.100)
```

**Supported URI schemes**:
- `mqtt://hostname:port` (plain MQTT, default port 1883)
- `mqtts://hostname:port` (MQTT over TLS, default port 8883)
- `ws://hostname:port/path` (MQTT over WebSocket)
- `wss://hostname:port/path` (MQTT over secure WebSocket)

**Notes**:
- Stored in NVS ("storage" namespace, key "mqtt_server")
- Takes effect on next MQTT reconnection
- Overrides the build-time default (CONFIG_MQTT_BROKER_URI)

#### 0x09 - Set MQTT Credentials (NEW)
Configures the MQTT username and password.

**Payload**: `[0x09, username..., 0x00, password..., 0x00]`

**Example** (username="myuser", password="mypass"):
```
09 6D 79 75 73 65 72 00 6D 79 70 61 73 73 00
```

**Notes**:
- Both username and password are null-terminated strings packed sequentially
- Stored in NVS ("storage" namespace, keys "mqtt_user" and "mqtt_pass")
- Takes effect on next MQTT reconnection

## Web UI Configuration

### Accessing the Web UI

The Web UI is available when the device is in SoftAP mode (after 5 failed WiFi connection attempts):

1. Connect to WiFi network "VMflow" (password: "12345678")
2. A captive portal should open automatically
3. If not, navigate to http://192.168.4.1

### Available Settings

- **WiFi SSID**: Network name to connect to
- **WiFi Password**: Network password
- **MQTT Server Address**: MQTT broker hostname or IP

### REST API Endpoints

**GET /api/v1/system/info**
Returns current device configuration as JSON.

**POST /api/v1/settings/set**
Updates WiFi and MQTT settings. Expects JSON body:
```json
{
  "ssid": "MyWiFi",
  "password": "MyPassword",
  "mqtt_server": "mqtt.example.com"
}
```

## Build-Time Configuration (menuconfig)

Run `idf.py menuconfig` to configure:

### MDB Cashless Device
- **Peripheral Address**: Cashless #1 (0x10) or #2 (0x60)
- **Currency Code**: EUR, USD, BRL, or UNKNOWN
- **Scale Factor**: 1, 10, or 100
- **Decimal Places**: 0-3

### MQTT Broker Configuration
- **Default MQTT Broker URI**: Used when no NVS value is set
- **Default MQTT Username**: Used when no NVS value is set
- **Default MQTT Password**: Used when no NVS value is set

## Configuration Priority

MQTT broker settings are resolved in this order (highest priority first):

1. NVS "storage" namespace (set via BLE 0x08/0x09)
2. NVS "vmflow" namespace, key "mqtt" (set via Web UI, legacy)
3. Kconfig defaults (set at build time)

## Erasing Configuration

To reset the device to factory state, erase the NVS partition:

```bash
idf.py -p /dev/ttyUSB0 erase-flash
```

Or erase only NVS:

```bash
idf.py -p /dev/ttyUSB0 erase-otadata
```

After erasing, the device will:
- Broadcast as "0.panamavendingmachines.com" (unconfigured)
- Use Kconfig default MQTT settings
- Require re-provisioning via BLE
