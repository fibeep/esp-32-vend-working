/*
 * VMflow.xyz - Cashless Vending Machine Firmware
 *
 * mqtt_handler.h - MQTT event handler and message processing
 *
 * Handles all MQTT client events: connection, disconnection,
 * subscription, and incoming messages.  The primary incoming message
 * of interest is a "credit" message on the topic
 *   {subdomain}.panamavendingmachines.com/credit
 * which triggers a vending session.
 *
 * On connection, the handler:
 *   1. Subscribes to {subdomain}.panamavendingmachines.com/#
 *   2. Publishes "online" to domain.panamavendingmachines.com/{subdomain}/status
 */

#ifndef MQTT_HANDLER_H
#define MQTT_HANDLER_H

#include <esp_event.h>
#include <stdint.h>

/**
 * @brief MQTT event handler callback.
 *
 * Registered with esp_mqtt_client_register_event() in main.c.
 * Processes MQTT_EVENT_CONNECTED, MQTT_EVENT_DISCONNECTED, and
 * MQTT_EVENT_DATA events.
 *
 * On MQTT_EVENT_DATA, checks if the topic ends with "/credit".
 * If so, decrypts the 19-byte XOR payload to extract fundsAvailable
 * and pushes it to the MDB session queue.
 *
 * @param handler_args  Unused (NULL).
 * @param base          Event base (always MQTT_EVENTS).
 * @param event_id      MQTT event ID (esp_mqtt_event_id_t).
 * @param event_data    Pointer to esp_mqtt_event_t with event details.
 */
void mqtt_event_handler(void *handler_args, esp_event_base_t base,
                        int32_t event_id, void *event_data);

#endif /* MQTT_HANDLER_H */
