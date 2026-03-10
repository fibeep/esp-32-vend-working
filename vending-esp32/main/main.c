/*
 * VMflow.xyz - Cashless Vending Machine Firmware
 *
 * main.c - Application entry point and system initialization
 *
 * This is the entry point for the ESP32-S3 firmware.  It initializes
 * all hardware peripherals, networking stacks, and FreeRTOS tasks.
 *
 * Initialization sequence:
 *   1. GPIO pin configuration (MDB, buzzer)
 *   2. WS2812 LED strip driver
 *   3. ADC for NTC thermistor
 *   4. UART1 for DEX/DDCMP telemetry
 *   5. Telemetry ring buffer + 12-hour periodic timer
 *   6. NVS flash initialization
 *   7. WiFi (STA+AP mode) with event handlers
 *   8. BLE (NimBLE) with GATT service + PAX counter timer
 *   9. MQTT client (broker URI from NVS, with Kconfig defaults)
 *  10. FreeRTOS tasks (MDB on Core 1, LED on Core 0)
 *
 * WiFi behaviour:
 *   - Starts in STA mode, attempts to connect
 *   - On 5 consecutive failures, enables SoftAP + captive portal
 *   - On IP acquisition, starts MQTT and SNTP
 *
 * MQTT broker configuration (NEW):
 *   - Default URI/credentials from Kconfig (menuconfig)
 *   - Overridden by NVS values if present (set via BLE cmds 0x08/0x09
 *     or via the Web UI)
 *   - NVS namespace "storage", keys: "mqtt_server", "mqtt_user", "mqtt_pass"
 */

#include <esp_log.h>
#include <sdkconfig.h>
#include <driver/gpio.h>
#include <driver/uart.h>
#include <esp_wifi.h>
#include <esp_timer.h>
#include <freertos/FreeRTOS.h>
#include <mqtt_client.h>
#include <nvs_flash.h>
#include <stdio.h>
#include <string.h>
#include <esp_sntp.h>

#include <esp_adc/adc_oneshot.h>
#include <esp_adc/adc_cali.h>
#include <esp_adc/adc_cali_scheme.h>

#include "led_strip.h"

#include "config.h"
#include "mdb_handler.h"
#include "ble_handler.h"
#include "mqtt_handler.h"
#include "xor_crypto.h"
#include "telemetry.h"
#include "led_status.h"
#include "nimble.h"
#include "webui_server.h"

/* ======================== GLOBAL VARIABLE DEFINITIONS ================= */
/*
 * These variables are declared as extern in config.h and defined here.
 * They are shared across all modules via the extern declarations.
 */

/* FreeRTOS event group for LED / buzzer status signalling */
EventGroupHandle_t xLedEventGroup;

/* WS2812 LED strip driver handle */
led_strip_handle_t led_strip;

/* Current MDB state machine state */
machine_state_t machine_state = INACTIVE_STATE;

/* MDB control flags (one-shot signals between BLE/MQTT and MDB task) */
bool session_begin_todo     = false;
bool session_cancel_todo    = false;
bool session_end_todo       = false;
bool vend_approved_todo     = false;
bool vend_denied_todo       = false;
bool cashless_reset_todo    = false;
bool out_of_sequence_todo   = false;

/* Device identity from NVS */
char my_subdomain[32];
char my_passkey[18];

/* MQTT client handle */
esp_mqtt_client_handle_t mqttClient = NULL;

/* MDB session queue (depth 1, carries uint16_t fundsAvailable) */
QueueHandle_t mdbSessionQueue = NULL;

/* DEX telemetry ring buffer (8 KB) */
RingbufHandle_t dexRingbuf;

/* MQTT connection state tracking */
bool mqtt_started = false;
static bool sntp_started = false;

/* WiFi retry counter */
static int wifi_retry_num = 0;

/* Flag: set by BLE cmd 0x08/0x09 to trigger MQTT client restart */
bool mqtt_reconnect_pending = false;

/* ======================================================================
 * wifi_event_handler - WiFi and IP event callback
 * ======================================================================
 *
 * Handles WiFi lifecycle events:
 *
 * WIFI_EVENT_STA_START:
 *   Reset retry counter and attempt connection.
 *
 * WIFI_EVENT_STA_DISCONNECTED:
 *   If retries < WIFI_MAX_RETRY (5):
 *     - Disconnect MQTT if running
 *     - Retry WiFi connection
 *   Else:
 *     - Fall back to SoftAP mode
 *     - Start DNS captive portal
 *     - Start REST config server
 *
 * IP_EVENT_STA_GOT_IP:
 *   - Reset retry counter
 *   - Stop captive portal (if running)
 *   - Start MQTT client (if not already running)
 *   - Start SNTP time sync (if not already running)
 */
static void wifi_event_handler(void *arg, esp_event_base_t event_base,
                               int32_t event_id, void *event_data)
{
    if (event_base == WIFI_EVENT) {
        switch (event_id) {
        case WIFI_EVENT_STA_START:
            wifi_retry_num = 0;
            esp_wifi_connect();
            break;

        case WIFI_EVENT_STA_CONNECTED:
            break;

        case WIFI_EVENT_STA_DISCONNECTED:
            if (wifi_retry_num++ < WIFI_MAX_RETRY) {
                /* Disconnect MQTT to avoid stale connection state */
                if (mqtt_started) {
                    esp_mqtt_client_disconnect(mqttClient);
                    mqtt_started = false;
                }

                esp_wifi_connect();
            } else {
                /*
                 * Too many retries - fall back to SoftAP mode so the
                 * user can configure WiFi via the captive portal.
                 */
                start_softap();
                start_dns_server();
                start_rest_server();
            }
            break;
        }
    }

    if (event_base == IP_EVENT) {
        switch (event_id) {
        case IP_EVENT_STA_GOT_IP:
            wifi_retry_num = 0;

            /* Stop captive portal (if running from a previous AP fallback) */
            stop_rest_server();
            stop_dns_server();

            /* Start MQTT if not already running */
            if (!mqtt_started) {
                esp_mqtt_client_start(mqttClient);
                mqtt_started = true;
            }

            /* Start SNTP for time synchronization (needed for XOR timestamps) */
            if (!sntp_started) {
                esp_sntp_setoperatingmode(ESP_SNTP_OPMODE_POLL);
                esp_sntp_setservername(0, "pool.ntp.org");
                esp_sntp_init();
                sntp_started = true;
            }
            break;
        }
    }
}

/* ======================================================================
 * request_pax_counter - Timer callback: start BLE scan for PAX counting
 * ======================================================================
 *
 * Called every 5 minutes by a periodic esp_timer.  Starts a BLE scan
 * that detects phone-like devices (Apple, Google, Samsung, etc.).
 * The scan results are accumulated in nimble.c and reported via the
 * ble_pax_event_handler callback when the scan interval expires.
 */
static void request_pax_counter(void *arg)
{
    ble_scan_start(PAX_SCAN_DURATION_SEC);
}

/* ======================================================================
 * app_main - Application entry point
 * ======================================================================
 *
 * Called by the ESP-IDF framework after the bootloader and system
 * initialization.  Sets up all peripherals and starts the FreeRTOS
 * tasks that implement the vending machine cashless protocol.
 */
void app_main(void)
{
    /* ---- GPIO Configuration ---- */
    gpio_set_direction(PIN_MDB_RX, GPIO_MODE_INPUT);
    gpio_set_direction(PIN_MDB_TX, GPIO_MODE_OUTPUT);

    gpio_set_direction(PIN_BUZZER_PWR, GPIO_MODE_OUTPUT);
    gpio_set_level(PIN_BUZZER_PWR, 0);

    /* ---- WS2812 LED Strip ---- */
    xLedEventGroup = xEventGroupCreate();

    led_strip_config_t strip_config = {
        .strip_gpio_num = PIN_MDB_LED,
        .max_leds = 1,
        .color_component_format = LED_STRIP_COLOR_COMPONENT_FMT_GRB,
        .led_model = LED_MODEL_WS2812,
        .flags.invert_out = false,
    };

    led_strip_rmt_config_t rmt_config = {
        .clk_src = RMT_CLK_SRC_DEFAULT,
        .resolution_hz = 10 * 1000 * 1000,  /* 10 MHz for good precision */
        .mem_block_symbols = 64,
    };

    ESP_ERROR_CHECK(led_strip_new_rmt_device(&strip_config, &rmt_config, &led_strip));

    /* ---- ADC Init (NTC Thermistor) ---- */
    adc_oneshot_unit_handle_t adc_handle;
    adc_oneshot_unit_init_cfg_t init_config = {
        .unit_id = ADC_UNIT_THERMISTOR,
    };
    ESP_ERROR_CHECK(adc_oneshot_new_unit(&init_config, &adc_handle));

    adc_oneshot_chan_cfg_t adc_chan_config = {
        .atten = ADC_ATTEN_DB_12,
        .bitwidth = ADC_BITWIDTH_DEFAULT,
    };
    ESP_ERROR_CHECK(adc_oneshot_config_channel(adc_handle, ADC_CHANNEL_THERMISTOR, &adc_chan_config));

    int adc_raw_value;
    ESP_ERROR_CHECK(adc_oneshot_read(adc_handle, ADC_CHANNEL_THERMISTOR, &adc_raw_value));
    ESP_LOGI(TAG, "ADC Raw Data: %d", adc_raw_value);

    /* ---- UART1 - DEX/DDCMP Telemetry Port ---- */
    uart_config_t uart_config_1 = {
        .baud_rate = 9600,
        .data_bits = UART_DATA_8_BITS,
        .parity = UART_PARITY_DISABLE,
        .stop_bits = UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
    };

    uart_param_config(UART_NUM_1, &uart_config_1);
    uart_set_pin(UART_NUM_1, PIN_DEX_TX, PIN_DEX_RX, -1, -1);
    uart_driver_install(UART_NUM_1, 256, 256, 0, NULL, 0);

    /* ---- DEX Ring Buffer + 12-hour Telemetry Timer ---- */
    dexRingbuf = xRingbufferCreate(8 * 1024, RINGBUF_TYPE_BYTEBUF);

    const double INTERVAL_12H_US = 12ULL * 60 * 60 * 1000000;  /* 12 hours */

    const esp_timer_create_args_t periodic_timer_args = {
        .callback = &requestTelemetryData,
        .name = "task_dex_12h",
    };

    esp_timer_handle_t periodic_timer;
    esp_timer_create(&periodic_timer_args, &periodic_timer);
    esp_timer_start_periodic(periodic_timer, INTERVAL_12H_US);

    /* ---- Network Stack Initialization ---- */
    nvs_flash_init();

    esp_netif_init();
    esp_event_loop_create_default();

    esp_netif_create_default_wifi_sta();
    esp_netif_create_default_wifi_ap();

    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    esp_wifi_init(&cfg);

    /* Register WiFi and IP event handlers */
    esp_event_handler_instance_register(WIFI_EVENT, ESP_EVENT_ANY_ID,
                                        wifi_event_handler, NULL, NULL);
    esp_event_handler_instance_register(IP_EVENT, ESP_EVENT_ANY_ID,
                                        wifi_event_handler, NULL, NULL);

    esp_wifi_set_mode(WIFI_MODE_APSTA);
    esp_wifi_start();

    /* ---- BLE (NimBLE) Initialization ---- */
    char myhost[64];
    strcpy(myhost, "0.panamavendingmachines.com");  /* Default name for unconfigured device */

    nvs_handle_t handle;
    if (nvs_open("vmflow", NVS_READONLY, &handle) == ESP_OK) {
        size_t s_len = 0;

        /* Read passkey from NVS */
        if (nvs_get_str(handle, "passkey", NULL, &s_len) == ESP_OK) {
            nvs_get_str(handle, "passkey", my_passkey, &s_len);

            /* Read subdomain from NVS */
            if (nvs_get_str(handle, "domain", NULL, &s_len) == ESP_OK) {
                nvs_get_str(handle, "domain", my_subdomain, &s_len);

                snprintf(myhost, sizeof(myhost), "%s.panamavendingmachines.com", my_subdomain);

                /* Mark device as provisioned (both passkey and domain set) */
                xEventGroupSetBits(xLedEventGroup,
                                   BIT_EVT_PSSKEY | BIT_EVT_DOMAIN | BIT_EVT_TRIGGER);
            }
        }

        nvs_close(handle);
    }

    /* Initialize BLE with callbacks */
    ble_init(myhost, ble_event_handler, ble_pax_event_handler);

    /* ---- PAX Counter Timer (5-minute interval) ---- */
    const esp_timer_create_args_t periodic_pax_timer_args = {
        .callback = &request_pax_counter,
        .name = "task_paxcounter",
    };

    esp_timer_handle_t periodic_pax_timer;
    esp_timer_create(&periodic_pax_timer_args, &periodic_pax_timer);
    esp_timer_start_periodic(periodic_pax_timer, PAX_SCAN_INTERVAL_US);

    /* ---- MQTT Client Initialization ---- */
    /*
     * MQTT broker configuration with NVS override:
     *
     * Priority (highest to lowest):
     *   1. NVS values (set via BLE cmd 0x08/0x09 or Web UI)
     *   2. Kconfig defaults (set at build time via menuconfig)
     *
     * The Kconfig defaults are:
     *   CONFIG_MQTT_BROKER_URI  = "mqtt://mqtt.panamavendingmachines.com"
     *   CONFIG_MQTT_USERNAME    = "vmflow"
     *   CONFIG_MQTT_PASSWORD    = "vmflow"
     */
    char mqtt_uri[128];
    char mqtt_user[64];
    char mqtt_pass[64];

    /* Start with Kconfig defaults */
    strncpy(mqtt_uri,  CONFIG_MQTT_BROKER_URI, sizeof(mqtt_uri) - 1);
    mqtt_uri[sizeof(mqtt_uri) - 1] = '\0';
    strncpy(mqtt_user, CONFIG_MQTT_USERNAME,   sizeof(mqtt_user) - 1);
    mqtt_user[sizeof(mqtt_user) - 1] = '\0';
    strncpy(mqtt_pass, CONFIG_MQTT_PASSWORD,   sizeof(mqtt_pass) - 1);
    mqtt_pass[sizeof(mqtt_pass) - 1] = '\0';

    /* Try to override from NVS ("storage" namespace) */
    nvs_handle_t nvs_mqtt;
    if (nvs_open("storage", NVS_READONLY, &nvs_mqtt) == ESP_OK) {
        size_t len;

        len = sizeof(mqtt_uri);
        nvs_get_str(nvs_mqtt, "mqtt_server", mqtt_uri, &len);

        len = sizeof(mqtt_user);
        nvs_get_str(nvs_mqtt, "mqtt_user", mqtt_user, &len);

        len = sizeof(mqtt_pass);
        nvs_get_str(nvs_mqtt, "mqtt_pass", mqtt_pass, &len);

        nvs_close(nvs_mqtt);
    }

    /* Also check the "vmflow" namespace for legacy mqtt key */
    nvs_handle_t nvs_vmflow_mqtt;
    if (nvs_open("vmflow", NVS_READONLY, &nvs_vmflow_mqtt) == ESP_OK) {
        char legacy_mqtt[128] = {0};
        size_t len = sizeof(legacy_mqtt);
        if (nvs_get_str(nvs_vmflow_mqtt, "mqtt", legacy_mqtt, &len) == ESP_OK) {
            if (strlen(legacy_mqtt) > 0) {
                /*
                 * Legacy format: the web UI stores just the hostname
                 * (e.g. "mqtt.example.com").  Prepend "mqtt://" if no
                 * scheme is present.
                 */
                if (strstr(legacy_mqtt, "://") == NULL) {
                    snprintf(mqtt_uri, sizeof(mqtt_uri), "mqtt://%s", legacy_mqtt);
                } else {
                    strncpy(mqtt_uri, legacy_mqtt, sizeof(mqtt_uri) - 1);
                    mqtt_uri[sizeof(mqtt_uri) - 1] = '\0';
                }
            }
        }
        nvs_close(nvs_vmflow_mqtt);
    }

    ESP_LOGI(TAG, "MQTT URI: %s", mqtt_uri);
    ESP_LOGI(TAG, "MQTT User: %s", mqtt_user);

    /* Build LWT (Last Will and Testament) topic */
    char lwt_topic[64];
    snprintf(lwt_topic, sizeof(lwt_topic), "domain.panamavendingmachines.com/%s/status", my_subdomain);

    /*
     * MQTT client configuration.
     *
     * Security model notes (from original firmware):
     *   - MQTT credentials are for broker ACL control only
     *   - Transport is intentionally non-TLS (constrained device)
     *   - Application payloads are XOR-encrypted with per-device passkey
     *   - The broker is a routing layer, not a security boundary
     */
    const esp_mqtt_client_config_t mqttCfg = {
        .broker.address.uri = mqtt_uri,
        .credentials = {
            .username = mqtt_user,
            .authentication.password = mqtt_pass,
        },
        .session.last_will.topic = lwt_topic,
        .session.last_will.msg = "offline",
        .session.last_will.qos = 1,
        .session.last_will.retain = 1,
    };

    mqttClient = esp_mqtt_client_init(&mqttCfg);
    esp_mqtt_client_register_event(mqttClient, ESP_EVENT_ANY_ID, mqtt_event_handler, NULL);

    /* ---- FreeRTOS Tasks ---- */
    /*
     * MDB task: Core 1, priority 1, 4 KB stack
     *   Handles all MDB bus communication via bit-banging.
     *   Pinned to Core 1 to avoid interference with BLE (Core 0).
     *
     * LED task: Core 0, priority 1, 2 KB stack
     *   Updates the WS2812 status LED and buzzer.
     *   Runs on Core 0 alongside the BLE host task.
     */
    mdbSessionQueue = xQueueCreate(1, sizeof(uint16_t));
    xTaskCreatePinnedToCore(vTaskMdbEvent, "TaskMdbEvent", 4096, NULL, 1, NULL, 1);
    xTaskCreatePinnedToCore(vTaskBitEvent, "TaskBitEvent", 2048, NULL, 1, NULL, 0);

    /* Trigger initial LED update */
    xEventGroupSetBits(xLedEventGroup, BIT_EVT_TRIGGER);
}
