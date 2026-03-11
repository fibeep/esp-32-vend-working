/*
 * VMflow.xyz - Cashless Vending Machine Firmware
 *
 * config.h - Shared configuration, constants, pin definitions, and global state
 *
 * This header is the single source of truth for all hardware pin assignments,
 * MDB protocol constants, state machine definitions, LED event flags, and
 * shared global variables.  Every module includes this file so that pin numbers
 * and protocol values are defined in exactly one place.
 *
 * -----------------------------------------------------------------------
 *  IMPORTANT: Do NOT duplicate any of these definitions in other files.
 *  If you need a new shared constant, add it here.
 * -----------------------------------------------------------------------
 */

#ifndef CONFIG_H
#define CONFIG_H

#include <stdint.h>
#include <stdbool.h>

#include <freertos/FreeRTOS.h>
#include <freertos/event_groups.h>
#include <freertos/queue.h>
#include <freertos/ringbuf.h>

#include <mqtt_client.h>
#include <sdkconfig.h>

#include "led_strip.h"

/* ======================== LOGGING TAG ================================= */
/* Used across all modules for consistent ESP_LOGx output.                */
#define TAG "mdb_cashless"

/* ======================== GPIO PIN MAP ================================ */
/*
 * Physical wiring between the ESP32-S3 PCB and the vending machine /
 * peripheral hardware.  Pin numbers match the schematic revisions
 * (v1 through v3) of the mdb-slave-esp32s3 board.
 *
 *  PIN_MDB_RX / PIN_MDB_TX  - 9-bit MDB bus (bit-banged, NOT UART)
 *  PIN_DEX_RX / PIN_DEX_TX  - UART1 for DEX / DDCMP telemetry
 *  PIN_MDB_LED              - WS2812 addressable LED (status indicator)
 *  PIN_BUZZER_PWR           - Piezo buzzer enable (active high)
 *  PIN_SIM7080G_*           - LTE modem (reserved, not used in this FW)
 *  PIN_I2C_SDA / SCL        - I2C bus (reserved for future sensors)
 *  PIN_PULSE_1              - Pulse counter input (reserved)
 */
#define PIN_I2C_SDA             GPIO_NUM_10
#define PIN_I2C_SCL             GPIO_NUM_11
#define PIN_PULSE_1             GPIO_NUM_13
#define PIN_MDB_RX              GPIO_NUM_4
#define PIN_MDB_TX              GPIO_NUM_5
#define PIN_MDB_LED             GPIO_NUM_21
#define PIN_DEX_RX              GPIO_NUM_8
#define PIN_DEX_TX              GPIO_NUM_9
#define PIN_SIM7080G_RX         GPIO_NUM_18
#define PIN_SIM7080G_TX         GPIO_NUM_17
#define PIN_SIM7080G_PWR        GPIO_NUM_14
#define PIN_BUZZER_PWR          GPIO_NUM_12

/* ======================== ADC CONFIGURATION =========================== */
/* NTC thermistor connected to ADC1 channel 6 (GPIO7 on ESP32-S3).        */
#define ADC_UNIT_THERMISTOR     ADC_UNIT_1
#define ADC_CHANNEL_THERMISTOR  ADC_CHANNEL_6

/* ======================== SCALE FACTOR MACROS ========================= */
/*
 * MDB prices are transmitted in "scale factor" units.  The VMC tells us
 * the scale factor and decimal places during SETUP CONFIG_DATA.  These
 * macros convert between the raw MDB representation and a normalised
 * integer representation used in the XOR-encrypted payloads.
 *
 *  TO_SCALE_FACTOR   - normalised --> MDB scale
 *  FROM_SCALE_FACTOR - MDB scale  --> normalised
 *
 * Example (default scale=1, dec=2):
 *   raw MDB price 150  -->  display price $1.50
 *   FROM_SCALE_FACTOR(150, 1, 2) == 150  (identity when scale=1, dec=2)
 */
#include <math.h>
#define TO_SCALE_FACTOR(p, scale_to, dec_to) \
    (p / scale_to / pow(10, -(dec_to)))
#define FROM_SCALE_FACTOR(p, scale_from, dec_from) \
    (p * scale_from * pow(10, -(dec_from)))

/* ======================== MDB PROTOCOL CONSTANTS ====================== */
/*
 * The MDB (Multi-Drop Bus) is a serial protocol used inside vending
 * machines.  It runs at 9600 baud with a 9th "mode" bit that
 * distinguishes address/command bytes from data bytes.
 *
 * ACK (0x00) - checksum correct, data accepted
 * RET (0xAA) - retransmit request (VMC only)
 * NAK (0xFF) - negative acknowledgement
 */
#define ACK     0x00
#define RET     0xAA
#define NAK     0xFF

/* Bit masks applied to the 9-bit value read from the MDB bus:
 *   BIT_MODE_SET  - isolates the 9th (mode) bit
 *   BIT_ADD_SET   - isolates the 5-bit peripheral address field
 *   BIT_CMD_SET   - isolates the 3-bit command field
 */
#define BIT_MODE_SET    0b100000000
#define BIT_ADD_SET     0b011111000
#define BIT_CMD_SET     0b000000111

/* ======================== MDB COMMAND ENUMS =========================== */
/*
 * Commands sent by the VMC (Vending Machine Controller) to the cashless
 * peripheral (us).  These are the 3-bit command codes extracted via
 * BIT_CMD_SET after the address match.
 */
enum MDB_COMMAND_FLOW {
    RESET       = 0x00,   /* Hard reset -> INACTIVE_STATE              */
    SETUP       = 0x01,   /* Configuration exchange                    */
    POLL        = 0x02,   /* Status poll (most frequent command)       */
    VEND        = 0x03,   /* Vend-related sub-commands                 */
    READER      = 0x04,   /* Reader enable/disable/cancel              */
    EXPANSION   = 0x07    /* Expansion commands (REQUEST_ID, etc.)     */
};

/* SETUP sub-commands */
enum MDB_SETUP_FLOW {
    CONFIG_DATA     = 0x00,   /* VMC sends display capabilities        */
    MAX_MIN_PRICES  = 0x01    /* VMC sends price range constraints     */
};

/* VEND sub-commands */
enum MDB_VEND_FLOW {
    VEND_REQUEST        = 0x00,   /* User selected a product           */
    VEND_CANCEL         = 0x01,   /* VMC cancels the vend              */
    VEND_SUCCESS        = 0x02,   /* Product dispensed OK               */
    VEND_FAILURE        = 0x03,   /* Dispensing failed                   */
    SESSION_COMPLETE    = 0x04,   /* Session is ending                   */
    CASH_SALE           = 0x05    /* A cash transaction was reported     */
};

/* READER sub-commands */
enum MDB_READER_FLOW {
    READER_DISABLE  = 0x00,   /* Disable the cashless reader          */
    READER_ENABLE   = 0x01,   /* Enable the cashless reader           */
    READER_CANCEL   = 0x02    /* Cancel pending reader operation       */
};

/* EXPANSION sub-commands */
enum MDB_EXPANSION_FLOW {
    REQUEST_ID  = 0x00,   /* VMC requests 30-byte peripheral ID       */
    DIAGNOSTICS = 0xFF    /* Diagnostics (not implemented)             */
};

/* ======================== STATE MACHINE =============================== */
/*
 * Cashless device state machine per MDB specification.
 *
 *   INACTIVE  --SETUP-->  DISABLED  --ENABLE-->  ENABLED
 *                              ^                     |
 *                              |               credit/session
 *                         DISABLE/timeout            |
 *                              |                     v
 *                          ENABLED  <--sess_end--  IDLE
 *                                                    |
 *                                              VEND_REQUEST
 *                                                    |
 *                                                    v
 *                                                  VEND
 *                                               (success/fail -> IDLE)
 *
 * Numeric ordering matters: code uses comparisons like
 *   `machine_state >= IDLE_STATE` to check "in session".
 */
typedef enum MACHINE_STATE {
    INACTIVE_STATE  = 0,
    DISABLED_STATE  = 1,
    ENABLED_STATE   = 2,
    IDLE_STATE      = 3,
    VEND_STATE      = 4
} machine_state_t;

/* ======================== LED EVENT FLAGS ============================= */
/*
 * The WS2812 LED task (vTaskBitEvent) waits on an EventGroup.
 * Each bit represents a status condition; the LED colour is chosen
 * from the combination of set bits.
 *
 *  BIT_EVT_INTERNET  - WiFi + MQTT connected
 *  BIT_EVT_MDB       - MDB bus active (ENABLED_STATE or higher)
 *  BIT_EVT_PSSKEY    - Passkey provisioned in NVS
 *  BIT_EVT_DOMAIN    - Subdomain provisioned in NVS
 *  BIT_EVT_BUZZER    - One-shot buzzer activation
 *  BIT_EVT_TRIGGER   - Signals the LED task to re-evaluate colour
 *
 *  MASK_EVT_INSTALLED = PSSKEY | DOMAIN  (device fully provisioned)
 */
enum BIT_EVENTS {
    BIT_EVT_INTERNET    = (1 << 0),
    BIT_EVT_MDB         = (1 << 1),
    BIT_EVT_PSSKEY      = (1 << 2),
    BIT_EVT_DOMAIN      = (1 << 3),
    BIT_EVT_BUZZER      = (1 << 4),
    BIT_EVT_TRIGGER     = (1 << 5),
    MASK_EVT_INSTALLED  = (BIT_EVT_PSSKEY | BIT_EVT_DOMAIN)
};

/* ======================== WiFi RETRY LIMIT ============================ */
/* After this many consecutive STA connection failures the firmware falls
 * back to SoftAP mode and starts the captive-portal web UI.              */
#define WIFI_MAX_RETRY  5

/* ======================== GLOBAL STATE (extern) ======================= */
/*
 * These variables are defined in main.c and shared across modules.
 * Every module that needs access includes this header and uses the
 * extern declarations below.
 */

/* FreeRTOS event group for LED / buzzer status */
extern EventGroupHandle_t xLedEventGroup;

/* WS2812 LED strip driver handle */
extern led_strip_handle_t led_strip;

/* Current MDB state machine state */
extern machine_state_t machine_state;

/* MDB control flags - set by BLE/MQTT handlers, consumed by vTaskMdbEvent.
 * These act as one-shot signals: the MDB task checks them during POLL and
 * clears them after building the corresponding MDB response. */
extern bool session_begin_todo;
extern bool session_cancel_todo;
extern bool session_end_todo;
extern bool vend_approved_todo;
extern bool vend_denied_todo;
extern bool cashless_reset_todo;
extern bool out_of_sequence_todo;

/* Device identity stored in NVS */
extern char my_subdomain[32];   /* e.g. "123456" */
extern char my_passkey[18];     /* 18-byte XOR encryption key */

/* MQTT client handle (initialised in main.c) */
extern esp_mqtt_client_handle_t mqttClient;

/* MDB session queue: carries fundsAvailable (uint16_t) from BLE/MQTT to
 * the MDB task.  Queue depth = 1 (only one session at a time). */
extern QueueHandle_t mdbSessionQueue;

/* DEX telemetry ring buffer (8 KB, byte-buffer mode) */
extern RingbufHandle_t dexRingbuf;

/* MQTT connection tracking flags */
extern bool mqtt_started;

/* Flag to indicate whether a mqtt reconnect is pending (set by BLE cmd 0x08/0x09) */
extern bool mqtt_reconnect_pending;

/* ======================== YAPPY KIOSK MODE =============================== */
/*
 * When yappy_kiosk_mode is true, the machine operates as a self-service
 * kiosk with Yappy payment:
 *   - Auto-pushes 0xFFFF to mdbSessionQueue on READER_ENABLE (always in session)
 *   - Auto-restarts session on SESSION_COMPLETE
 *   - VEND_REQUEST triggers Yappy QR generation instead of BLE/MQTT approval
 *   - session_timer_reset_todo keeps MDB session alive during Yappy polling
 *
 * These globals are defined in main.c and shared with mdb_handler.c
 * and yappy_handler.c.
 */
extern bool wifi_sta_connected;          /* STA has IP (internet reachable)        */
extern bool session_timer_reset_todo;   /* Yappy resets the 60s MDB session timer */
extern bool yappy_kiosk_mode;           /* Auto-session mode enabled              */
extern uint16_t yappy_item_price;       /* Current item price (set by VEND_REQUEST) */
extern uint16_t yappy_item_number;      /* Current item number (set by VEND_REQUEST) */

#endif /* CONFIG_H */
