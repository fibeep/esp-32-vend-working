/*
 * VMflow.xyz - Cashless Vending Machine Firmware
 *
 * mqtt_handler.c - MQTT event handler and message processing
 *
 * This module handles all MQTT client lifecycle events and incoming
 * messages.  The MQTT client is created and started in main.c; this
 * module only provides the event callback.
 *
 * Topic structure:
 *   Publish:
 *     domain.panamavendingmachines.com/{subdomain}/status   - "online" on connect
 *     domain.panamavendingmachines.com/{subdomain}/sale     - Cash sale reports
 *     domain.panamavendingmachines.com/{subdomain}/dex      - DEX telemetry data
 *     domain.panamavendingmachines.com/{subdomain}/paxcounter - Foot traffic count
 *
 *   Subscribe:
 *     {subdomain}.panamavendingmachines.com/#               - Wildcard subscription
 *
 *   Incoming (matched):
 *     {subdomain}.panamavendingmachines.com/credit          - Credit / session start
 *
 * The credit message contains a 19-byte XOR-encrypted payload with the
 * funds amount.  On successful decryption and validation, the handler
 * pushes fundsAvailable to the MDB session queue and activates the buzzer.
 */

#include <esp_log.h>
#include <mqtt_client.h>
#include <string.h>

#include "config.h"
#include "mqtt_handler.h"
#include "xor_crypto.h"

/* ======================================================================
 * mqtt_event_handler - MQTT client event callback
 * ======================================================================
 *
 * Called by the ESP-MQTT library for every client event.  The important
 * events are:
 *
 * MQTT_EVENT_CONNECTED:
 *   - Subscribe to {subdomain}.panamavendingmachines.com/# to receive credit messages
 *   - Publish "online" to the status topic (retained)
 *   - Set the BIT_EVT_INTERNET flag for LED status
 *
 * MQTT_EVENT_DISCONNECTED:
 *   - Clear the BIT_EVT_INTERNET flag
 *
 * MQTT_EVENT_DATA:
 *   - Check if topic ends with "/credit"
 *   - Decrypt the 19-byte payload
 *   - Validate checksum and timestamp
 *   - Extract fundsAvailable and push to MDB session queue
 *   - Activate buzzer to alert nearby user
 */
void mqtt_event_handler(void *handler_args, esp_event_base_t base,
                        int32_t event_id, void *event_data)
{
    esp_mqtt_event_handle_t event = event_data;
    esp_mqtt_client_handle_t client = event->client;

    switch ((esp_mqtt_event_id_t)event_id) {

    case MQTT_EVENT_CONNECTED: {
        /*
         * Connected to the broker.  Subscribe to the wildcard topic
         * for this device and publish our online status.
         */
        char topic[128];
        snprintf(topic, sizeof(topic), "%s.panamavendingmachines.com/#", my_subdomain);
        esp_mqtt_client_subscribe(client, topic, 0);

        char topic_status[128];
        snprintf(topic_status, sizeof(topic_status),
                 "domain.panamavendingmachines.com/%s/status", my_subdomain);
        esp_mqtt_client_publish(client, topic_status, "online", 0, 1, 1);

        /* Update LED: internet is available */
        xEventGroupSetBits(xLedEventGroup, BIT_EVT_INTERNET | BIT_EVT_TRIGGER);

        break;
    }

    case MQTT_EVENT_DISCONNECTED:
        /* Update LED: internet is unavailable */
        xEventGroupClearBits(xLedEventGroup, BIT_EVT_INTERNET);
        xEventGroupSetBits(xLedEventGroup, BIT_EVT_TRIGGER);
        break;

    case MQTT_EVENT_SUBSCRIBED:
        break;

    case MQTT_EVENT_UNSUBSCRIBED:
        break;

    case MQTT_EVENT_PUBLISHED:
        break;

    case MQTT_EVENT_DATA: {
        /*
         * Incoming message.  Log the topic and data for debugging.
         * Then check if the topic ends with "/credit" (7 characters).
         */
        ESP_LOGI(TAG, "TOPIC= %.*s", event->topic_len, event->topic);
        ESP_LOGI(TAG, "DATA_LEN= %d", event->data_len);
        ESP_LOGI(TAG, "DATA= %.*s", event->data_len, event->data);

        size_t topic_len = strlen(event->topic);

        if (topic_len > 7 &&
            strncmp(event->topic + event->topic_len - 7, "/credit", 7) == 0) {

            /*
             * Credit message received.  Decrypt the payload to extract
             * the funds amount and start a vending session.
             */
            uint16_t fundsAvailable;
            if (xorDecodeWithPasskey(&fundsAvailable, NULL, (uint8_t *)event->data)) {

                /* Push funds to MDB session queue (non-blocking) */
                xQueueSend(mdbSessionQueue, &fundsAvailable, 0);

                /* Activate buzzer to alert user near the machine */
                xEventGroupSetBits(xLedEventGroup, BIT_EVT_BUZZER | BIT_EVT_TRIGGER);

                ESP_LOGI(TAG, "Amount= %f",
                         FROM_SCALE_FACTOR(fundsAvailable,
                                           CONFIG_MDB_SCALE_FACTOR,
                                           CONFIG_MDB_DECIMAL_PLACES));
            }
        }

        break;
    }

    case MQTT_EVENT_ERROR:
        break;

    default:
        ESP_LOGI(TAG, "Other event id: %d", event->event_id);
        break;
    }
}
