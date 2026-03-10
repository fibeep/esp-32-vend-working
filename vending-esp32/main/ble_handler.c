/*
 * VMflow.xyz - Cashless Vending Machine Firmware
 *
 * ble_handler.c - BLE GATT write command processor
 *
 * Processes all BLE characteristic writes from the Android app.
 * Each write begins with a command byte that determines the operation.
 *
 * Commands 0x00-0x07 are carried over from the original firmware.
 * Commands 0x08-0x09 are new additions for remote MQTT configuration.
 *
 * The PAX counter callback is also here because it directly publishes
 * to MQTT (same dependency as the BLE event handler).
 */

#include <esp_log.h>
#include <esp_wifi.h>
#include <nvs_flash.h>
#include <string.h>

#include "config.h"
#include "ble_handler.h"
#include "xor_crypto.h"
#include "nimble.h"

/* ======================================================================
 * ble_pax_event_handler - PAX counter result callback
 * ======================================================================
 *
 * Called from nimble.c when the periodic BLE scan detects phone-like
 * devices.  The count is encoded into a 19-byte XOR payload and
 * published to MQTT topic: domain.panamavendingmachines.com/{subdomain}/paxcounter
 *
 * The backend uses this data to track foot traffic near the vending
 * machine over time.
 */
void ble_pax_event_handler(uint16_t devices_count)
{
    uint8_t payload[19];
    xorEncodeWithPasskey(0x22, 0, 0, devices_count, (uint8_t *)&payload);

    char topic[128];
    snprintf(topic, sizeof(topic), "domain.panamavendingmachines.com/%s/paxcounter", my_subdomain);

    esp_mqtt_client_publish(mqttClient, topic, (char *)&payload, sizeof(payload), 1, 0);
}

/* ======================================================================
 * ble_event_handler - Main BLE command dispatcher
 * ======================================================================
 *
 * Dispatches on the first byte of the BLE write payload.  Each case
 * handles one command and its associated data.
 *
 * Important design notes:
 *   - SET_SUBDOMAIN (0x00) and SET_PASSKEY (0x01) are one-time
 *     provisioning operations.  They only succeed if the value has
 *     NOT been previously set in NVS (prevents accidental overwrite).
 *   - START_SESSION (0x02) pushes fundsAvailable=0xFFFF (unlimited)
 *     to the MDB session queue.  The VMC's VEND_REQUEST will carry
 *     the actual price.
 *   - APPROVE_VEND (0x03) receives an XOR-encrypted payload from the
 *     app (which got it from the backend after payment validation).
 *   - SET_WIFI_SSID (0x06) disconnects WiFi first, then updates the
 *     SSID.  SET_WIFI_PASS (0x07) updates the password and reconnects.
 *   - SET_MQTT_URI (0x08) and SET_MQTT_CREDS (0x09) store values in
 *     NVS and signal a pending MQTT reconnect.
 */
void ble_event_handler(char *ble_payload)
{
    printf("ble_event_handler %x\n", (uint8_t)ble_payload[0]);

    switch ((uint8_t)ble_payload[0]) {

    /* ------------------------------------------------------------------
     * 0x00 SET_SUBDOMAIN
     * ------------------------------------------------------------------
     * Payload: [0x00, subdomain_string..., 0x00]
     *
     * Sets the device subdomain (e.g. "123456") which is used for:
     *   - BLE device name: "{subdomain}.panamavendingmachines.com"
     *   - MQTT topics: "domain.panamavendingmachines.com/{subdomain}/..."
     *   - MQTT subscription: "{subdomain}.panamavendingmachines.com/#"
     *
     * Only writes if the subdomain has NOT been previously set in NVS.
     * This prevents accidental re-provisioning of a deployed device.
     */
    case 0x00: {
        nvs_handle_t handle;
        ESP_ERROR_CHECK(nvs_open("vmflow", NVS_READWRITE, &handle));

        size_t s_len;
        if (nvs_get_str(handle, "domain", NULL, &s_len) != ESP_OK) {

            strcpy((char *)&my_subdomain, ble_payload + 1);

            ESP_ERROR_CHECK(nvs_set_str(handle, "domain", (char *)&my_subdomain));
            ESP_ERROR_CHECK(nvs_commit(handle));

            char myhost[64];
            snprintf(myhost, sizeof(myhost), "%s.panamavendingmachines.com", my_subdomain);

            ble_set_device_name((char *)&myhost);

            xEventGroupSetBits(xLedEventGroup, BIT_EVT_DOMAIN | BIT_EVT_TRIGGER);

            ESP_LOGI(TAG, "HOST= %s", myhost);
        }
        nvs_close(handle);
        break;
    }

    /* ------------------------------------------------------------------
     * 0x01 SET_PASSKEY
     * ------------------------------------------------------------------
     * Payload: [0x01, passkey_string(18 bytes)..., 0x00]
     *
     * Sets the 18-byte XOR encryption key used for all payload
     * encryption/decryption.  Only writes if not previously set.
     */
    case 0x01: {
        nvs_handle_t handle;
        ESP_ERROR_CHECK(nvs_open("vmflow", NVS_READWRITE, &handle));

        size_t s_len;
        if (nvs_get_str(handle, "passkey", NULL, &s_len) != ESP_OK) {

            strcpy((char *)&my_passkey, ble_payload + 1);

            ESP_ERROR_CHECK(nvs_set_str(handle, "passkey", (char *)&my_passkey));
            ESP_ERROR_CHECK(nvs_commit(handle));

            xEventGroupSetBits(xLedEventGroup, BIT_EVT_PSSKEY | BIT_EVT_TRIGGER);

            ESP_LOGI(TAG, "PASSKEY= %s", my_passkey);
        }
        nvs_close(handle);

        break;
    }

    /* ------------------------------------------------------------------
     * 0x02 START_SESSION
     * ------------------------------------------------------------------
     * Payload: [0x02] (1 byte only)
     *
     * Begins a vending session with unlimited funds (0xFFFF).
     * The Android app sends this after the user taps "Start" in the UI.
     * The MDB task will report "Begin Session" with fundsAvailable=0xFFFF
     * on the next POLL, and the VMC will display available products.
     */
    case 0x02: {
        uint16_t fundsAvailable = 0xffff;
        xQueueSend(mdbSessionQueue, &fundsAvailable, 0 /* if full, do not wait */);
        break;
    }

    /* ------------------------------------------------------------------
     * 0x03 APPROVE_VEND
     * ------------------------------------------------------------------
     * Payload: 19 bytes XOR-encrypted (from backend via Android app)
     *
     * The app received a VEND_REQUEST notification (0x0A), forwarded it
     * to the backend (POST /api/credit/request), got back an approved
     * payload (cmd byte changed to 0x03), and writes it here.
     *
     * We decrypt and validate the payload (checksum + timestamp), then
     * set vend_approved_todo if we're in VEND_STATE.
     */
    case 0x03: {
        if (xorDecodeWithPasskey(NULL, NULL, (uint8_t *)ble_payload)) {
            vend_approved_todo = (machine_state == VEND_STATE) ? true : false;
        }
        break;
    }

    /* ------------------------------------------------------------------
     * 0x04 CLOSE_SESSION
     * ------------------------------------------------------------------
     * Payload: [0x04] (1 byte only)
     *
     * Cancels the active vending session.  Only effective if we're in
     * IDLE_STATE or VEND_STATE (machine_state >= IDLE_STATE).
     */
    case 0x04: {
        session_cancel_todo = (machine_state >= IDLE_STATE) ? true : false;
        break;
    }

    /* ------------------------------------------------------------------
     * 0x05 (reserved / not implemented)
     * ------------------------------------------------------------------
     */
    case 0x05:
        break;

    /* ------------------------------------------------------------------
     * 0x06 SET_WIFI_SSID
     * ------------------------------------------------------------------
     * Payload: [0x06, ssid_string..., 0x00]
     *
     * Disconnects from the current WiFi network and updates the STA
     * SSID configuration.  The password is set separately via 0x07.
     * WiFi reconnection happens when 0x07 is received.
     */
    case 0x06: {
        esp_wifi_disconnect();

        wifi_config_t wifi_config = {0};
        esp_wifi_get_config(WIFI_IF_STA, &wifi_config);

        strcpy((char *)wifi_config.sta.ssid, ble_payload + 1);
        esp_wifi_set_config(WIFI_IF_STA, &wifi_config);

        ESP_LOGI(TAG, "SSID= %s", wifi_config.sta.ssid);
        break;
    }

    /* ------------------------------------------------------------------
     * 0x07 SET_WIFI_PASS
     * ------------------------------------------------------------------
     * Payload: [0x07, password_string..., 0x00]
     *
     * Updates the WiFi STA password and triggers a connection attempt.
     * Typically sent right after 0x06 (SET_WIFI_SSID).
     */
    case 0x07: {
        wifi_config_t wifi_config = {0};
        esp_wifi_get_config(WIFI_IF_STA, &wifi_config);

        strcpy((char *)wifi_config.sta.password, ble_payload + 1);
        esp_wifi_set_config(WIFI_IF_STA, &wifi_config);

        esp_wifi_connect();

        ESP_LOGI(TAG, "PASSWORD= %s", wifi_config.sta.password);
        break;
    }

    /* ------------------------------------------------------------------
     * 0x08 SET_MQTT_URI  (NEW)
     * ------------------------------------------------------------------
     * Payload: [0x08, uri_string..., 0x00]
     *
     * Stores a new MQTT broker URI in NVS (key: "mqtt_server" in the
     * "storage" namespace).  This overrides the Kconfig default
     * (CONFIG_MQTT_BROKER_URI) on the next MQTT client restart.
     *
     * Examples:
     *   "mqtt://192.168.1.100"
     *   "mqtt://mqtt.example.com"
     *   "mqtts://secure.broker.com"
     *
     * After storing, sets mqtt_reconnect_pending so that the main loop
     * or a dedicated task can restart the MQTT client with the new URI.
     */
    case 0x08: {
        nvs_handle_t handle;
        if (nvs_open("storage", NVS_READWRITE, &handle) == ESP_OK) {
            nvs_set_str(handle, "mqtt_server", ble_payload + 1);
            nvs_commit(handle);
            nvs_close(handle);

            mqtt_reconnect_pending = true;

            ESP_LOGI(TAG, "MQTT_URI= %s", ble_payload + 1);
        }
        break;
    }

    /* ------------------------------------------------------------------
     * 0x09 SET_MQTT_CREDENTIALS  (NEW)
     * ------------------------------------------------------------------
     * Payload: [0x09, username..., 0x00, password..., 0x00]
     *
     * The username and password are packed as two consecutive
     * null-terminated strings after the command byte.
     *
     * Example payload (hex):
     *   09 75 73 65 72 00 70 61 73 73 00
     *   ^  ^ "user\0"    ^ "pass\0"
     *   cmd
     *
     * Both are stored in NVS ("storage" namespace, keys "mqtt_user"
     * and "mqtt_pass") and will be used on the next MQTT reconnect.
     */
    case 0x09: {
        /* Parse: username starts at ble_payload+1, password follows after null */
        const char *username = ble_payload + 1;
        const char *password = username + strlen(username) + 1;

        nvs_handle_t handle;
        if (nvs_open("storage", NVS_READWRITE, &handle) == ESP_OK) {
            nvs_set_str(handle, "mqtt_user", username);
            nvs_set_str(handle, "mqtt_pass", password);
            nvs_commit(handle);
            nvs_close(handle);

            mqtt_reconnect_pending = true;

            ESP_LOGI(TAG, "MQTT_USER= %s", username);
            ESP_LOGI(TAG, "MQTT_PASS= (set)");
        }
        break;
    }
    }
}
