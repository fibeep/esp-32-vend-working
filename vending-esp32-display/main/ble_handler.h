/*
 * VMflow.xyz - Cashless Vending Machine Firmware
 *
 * ble_handler.h - BLE GATT write command processor
 *
 * When CONFIG_BT_ENABLED is off, stubs are provided so callers compile
 * without modification.
 */

#ifndef BLE_HANDLER_H
#define BLE_HANDLER_H

#if CONFIG_BT_ENABLED

void ble_event_handler(char *ble_payload);
void ble_pax_event_handler(uint16_t devices_count);

#else  /* BLE disabled — no-op stubs */

#include <stdint.h>
static inline void ble_event_handler(char *ble_payload) { (void)ble_payload; }
static inline void ble_pax_event_handler(uint16_t devices_count) { (void)devices_count; }

#endif /* CONFIG_BT_ENABLED */

#endif /* BLE_HANDLER_H */
