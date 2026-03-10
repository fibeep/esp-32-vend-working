# Architecture

## Module Layout

```
vending-esp32/
├── main/
│   ├── main.c              # Entry point: app_main(), init, WiFi, MQTT setup
│   ├── config.h            # Shared constants, pins, enums, extern globals
│   ├── mdb_handler.h/c     # MDB 9-bit protocol (bit-banging on GPIO4/5)
│   ├── ble_handler.h/c     # BLE command processing (0x00-0x09)
│   ├── mqtt_handler.h/c    # MQTT event handler, credit processing
│   ├── xor_crypto.h/c      # 19-byte XOR payload encode/decode
│   ├── telemetry.h/c       # DEX + DDCMP audit data collection
│   ├── led_status.h/c      # WS2812 LED status + buzzer
│   ├── nimble.h/c           # NimBLE GATT service + PAX scanner
│   ├── bleprph.h           # GATT service definitions
│   ├── webui_server.h/c    # HTTP captive portal + REST API
│   ├── Kconfig.projbuild   # Build-time configuration options
│   └── CMakeLists.txt      # Component source list
├── webui/
│   └── index.html          # Captive portal web page (embedded)
├── CMakeLists.txt          # Project-level CMake
├── sdkconfig.defaults      # Default SDK configuration
└── docs/
    ├── README.md           # Build and flash instructions
    ├── ARCHITECTURE.md     # This file
    ├── MDB_PROTOCOL.md     # MDB implementation details
    └── CONFIGURATION.md    # Device configuration guide
```

## Module Descriptions

### main.c
Application entry point. Initializes all hardware (GPIO, ADC, UART, LED), networking (WiFi, MQTT, SNTP), BLE, and creates FreeRTOS tasks. Contains the WiFi event handler for STA/AP mode switching. Reads MQTT broker configuration from NVS with Kconfig fallback defaults.

### config.h
Central header with all shared definitions. Pin assignments, MDB constants, state machine enum, event group bit definitions, and extern declarations for all global variables. Every module includes this file.

### mdb_handler.c
Implements the MDB cashless peripheral protocol using GPIO bit-banging. The read_9() and write_9() functions implement the 9-bit serial interface at 9600 baud. vTaskMdbEvent() runs the main protocol loop, processing commands from the VMC and responding during POLL.

### ble_handler.c
Processes BLE GATT write operations. Handles device provisioning (subdomain, passkey), session management (start, approve, close), WiFi configuration, and the new MQTT configuration commands (0x08 for URI, 0x09 for credentials). Also contains the PAX counter MQTT publisher.

### mqtt_handler.c
MQTT event callback. Subscribes to the device topic on connection, processes incoming credit messages (XOR-decrypted), and manages LED status on connect/disconnect.

### xor_crypto.c
XOR-based payload encryption and decryption. Handles the 19-byte payload format with random fill, structured fields, checksum, and passkey-based XOR. Includes timestamp validation for replay protection.

### telemetry.c
DEX and DDCMP protocol implementations for reading audit data from vending machines via UART1. Data is buffered in a ring buffer and published to MQTT periodically.

### led_status.c
FreeRTOS task that manages the WS2812 LED colour based on device state and the piezo buzzer for credit notifications.

### nimble.c
NimBLE BLE stack setup. Defines the GATT service and characteristic, handles advertising, connection events, and BLE scanning for the PAX counter feature.

### webui_server.c
HTTP server for the captive portal. Serves the configuration web page and provides REST endpoints for WiFi and MQTT settings.

## FreeRTOS Task Layout

| Task | Function | Core | Priority | Stack | Purpose |
|------|----------|------|----------|-------|---------|
| TaskMdbEvent | vTaskMdbEvent() | 1 | 1 | 4096 | MDB bus protocol |
| TaskBitEvent | vTaskBitEvent() | 0 | 1 | 2048 | LED + buzzer |
| BLE Host | ble_host_task() | 0 | 1 | 4096 | NimBLE host stack |

## Timer Callbacks

| Timer | Function | Interval | Purpose |
|-------|----------|----------|---------|
| task_dex_12h | requestTelemetryData() | 12 hours | DEX/DDCMP audit collection |
| task_paxcounter | request_pax_counter() | 5 minutes | BLE scan for foot traffic |

## Inter-Module Communication

### MDB Session Queue
- Type: FreeRTOS Queue (depth 1, uint16_t)
- Writers: ble_handler.c (cmd 0x02), mqtt_handler.c (credit)
- Reader: mdb_handler.c (during POLL)
- Data: fundsAvailable value

### One-Shot Flags
- Type: bool globals (defined in main.c, extern in config.h)
- Writers: ble_handler.c, mdb_handler.c
- Reader: mdb_handler.c (during POLL)
- Flags: vend_approved_todo, session_cancel_todo, session_end_todo, etc.

### LED Event Group
- Type: FreeRTOS EventGroup
- Writers: mdb_handler.c, mqtt_handler.c, ble_handler.c
- Reader: led_status.c (vTaskBitEvent)
- Bits: BIT_EVT_INTERNET, BIT_EVT_MDB, BIT_EVT_PSSKEY, BIT_EVT_DOMAIN, BIT_EVT_BUZZER, BIT_EVT_TRIGGER

### BLE Callbacks
- ble_event_report_handler -> ble_event_handler() in ble_handler.c
- ble_pax_report_handler -> ble_pax_event_handler() in ble_handler.c

## NVS Storage Map

| Namespace | Key | Type | Module | Description |
|-----------|-----|------|--------|-------------|
| vmflow | domain | string | ble_handler | Device subdomain |
| vmflow | passkey | string | ble_handler | 18-byte XOR key |
| vmflow | mqtt | string | webui_server | MQTT server (legacy) |
| storage | mqtt_server | string | ble_handler | MQTT broker URI |
| storage | mqtt_user | string | ble_handler | MQTT username |
| storage | mqtt_pass | string | ble_handler | MQTT password |

## Memory Layout

- Flash: Uses "Two OTA Large" partition table
- SRAM: ~320 KB available
  - MDB task stack: 4 KB
  - LED task stack: 2 KB
  - BLE host stack: 4 KB
  - DEX ring buffer: 8 KB
  - BLE device list: ~6 KB (1024 * 6 bytes)
  - MQTT buffers: managed by ESP-MQTT library
