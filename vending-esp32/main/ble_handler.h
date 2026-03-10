/*
 * VMflow.xyz - Cashless Vending Machine Firmware
 *
 * ble_handler.h - BLE GATT write command processor
 *
 * This module processes BLE GATT characteristic write operations from
 * the Android app (or any BLE client).  Each write contains a command
 * byte followed by command-specific payload data.
 *
 * Supported commands:
 *   0x00 SET_SUBDOMAIN    - Set the device subdomain in NVS
 *   0x01 SET_PASSKEY      - Set the 18-byte encryption key in NVS
 *   0x02 START_SESSION    - Begin a vending session (funds = unlimited)
 *   0x03 APPROVE_VEND     - Approve a pending vend (XOR-encrypted)
 *   0x04 CLOSE_SESSION    - Cancel / close the active session
 *   0x05 (reserved)       - Not implemented
 *   0x06 SET_WIFI_SSID    - Configure WiFi SSID
 *   0x07 SET_WIFI_PASS    - Configure WiFi password and connect
 *   0x08 SET_MQTT_URI     - Configure MQTT broker URI in NVS (NEW)
 *   0x09 SET_MQTT_CREDS   - Configure MQTT username/password in NVS (NEW)
 *
 * This function is registered as the callback for NimBLE GATT writes
 * via ble_init() in main.c.
 */

#ifndef BLE_HANDLER_H
#define BLE_HANDLER_H

/**
 * @brief BLE GATT write event callback.
 *
 * Called by the NimBLE stack (via nimble.c) whenever a BLE client writes
 * to the VMflow GATT characteristic.  The first byte of ble_payload
 * determines the command; subsequent bytes carry command-specific data.
 *
 * This function is passed as a function pointer to ble_init() and is
 * stored in nimble.c as ble_event_report_handler.
 *
 * @param ble_payload  Pointer to the received BLE write data.
 *                     Minimum 1 byte (command only).
 *                     Maximum 500 bytes (characteristic_received_value buffer).
 *
 * Thread safety:
 *   Called from the NimBLE host task context.  Accesses global state
 *   (NVS, WiFi config, MDB flags) that may be read from other tasks.
 *   Flag-based communication (vend_approved_todo, etc.) is safe for
 *   single-writer scenarios.
 */
void ble_event_handler(char *ble_payload);

/**
 * @brief PAX counter BLE scan result callback.
 *
 * Called by the NimBLE scan event handler (nimble.c) when the periodic
 * BLE scan window expires and devices have been counted.  Encodes the
 * count into a 19-byte XOR-encrypted payload and publishes it to the
 * MQTT paxcounter topic.
 *
 * @param devices_count  Number of unique phone-like BLE devices detected
 *                       during the scan window.
 */
void ble_pax_event_handler(uint16_t devices_count);

#endif /* BLE_HANDLER_H */
