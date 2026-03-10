# VMflow Vending ESP32 Firmware

Cashless vending machine peripheral firmware for the ESP32-S3, implementing the MDB (Multi-Drop Bus) cashless device protocol with BLE, MQTT, and WiFi connectivity.

## Prerequisites

- ESP-IDF v5.5.x (tested with v5.5.1)
- ESP32-S3 target board (mdb-slave-esp32s3 PCB)
- USB cable for flashing

### Install ESP-IDF

Follow the official guide: https://docs.espressif.com/projects/esp-idf/en/latest/esp32s3/get-started/

```bash
# Clone ESP-IDF
git clone -b v5.5.1 --recursive https://github.com/espressif/esp-idf.git
cd esp-idf
./install.sh esp32s3
source export.sh
```

## Build

```bash
cd vending-esp32

# Set target
idf.py set-target esp32s3

# Configure (optional - defaults are in sdkconfig.defaults)
idf.py menuconfig

# Build
idf.py build
```

### Build Configuration (menuconfig)

Under "MDB Cashless Device":
- Peripheral Address: #1 (0x10) or #2 (0x60)
- Currency Code: EUR, USD, BRL, or UNKNOWN
- Scale Factor: 1, 10, or 100
- Decimal Places: 0-3

Under "MQTT Broker Configuration":
- Broker URI: default "mqtt://mqtt.panamavendingmachines.com"
- Username: default "vmflow"
- Password: default "vmflow"

## Flash

```bash
# Flash firmware
idf.py -p /dev/ttyUSB0 flash

# Monitor serial output
idf.py -p /dev/ttyUSB0 monitor

# Flash and monitor
idf.py -p /dev/ttyUSB0 flash monitor
```

### macOS

```bash
idf.py -p /dev/cu.usbserial-* flash monitor
```

## Device Provisioning

After flashing, the device broadcasts as "0.panamavendingmachines.com" (unconfigured). Use the VMflow Android app to provision:

1. Connect via BLE
2. Set subdomain (BLE cmd 0x00) - e.g., "123456"
3. Set passkey (BLE cmd 0x01) - 18-character encryption key
4. Set WiFi SSID (BLE cmd 0x06)
5. Set WiFi password (BLE cmd 0x07)
6. Optionally set MQTT broker (BLE cmd 0x08)
7. Optionally set MQTT credentials (BLE cmd 0x09)

## LED Status

The WS2812 LED on the board indicates:

| Colour | Meaning |
|--------|---------|
| Yellow | Not provisioned (missing passkey or subdomain) |
| Green  | Fully operational (MDB + Internet) |
| Blue   | MDB active, no Internet |
| Red    | MDB inactive or disabled |

## WiFi Fallback

If WiFi fails to connect after 5 attempts, the device falls back to SoftAP mode:
- SSID: "VMflow"
- Password: "12345678"
- Connect and navigate to http://192.168.4.1 to configure WiFi and MQTT settings

## Project Structure

See `docs/ARCHITECTURE.md` for detailed module descriptions.
