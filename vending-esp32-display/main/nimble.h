#ifndef NIMBLE_H
#define NIMBLE_H

#define PAX_REPORT_INTERVAL_SEC     (60*60)         // 1 hora
#define PAX_SCAN_DURATION_SEC       (7)             // 7 segundos
#define PAX_SCAN_INTERVAL_US        (5*60*1000000)  // 5 minutos

#if CONFIG_BT_ENABLED

void ble_notify_send(char *notification, int notification_length);
void ble_init(char *deviceName, void* ble_event_handler_, void* ble_pax_event_handler_);
void ble_set_device_name(char *deviceName);

void ble_scan_start(int duration_seconds);
void ble_scan_stop(void);

#else  /* BLE disabled — provide no-op stubs so callers compile cleanly */

static inline void ble_notify_send(char *notification, int notification_length) { (void)notification; (void)notification_length; }
static inline void ble_init(char *deviceName, void* ble_event_handler_, void* ble_pax_event_handler_) { (void)deviceName; (void)ble_event_handler_; (void)ble_pax_event_handler_; }
static inline void ble_set_device_name(char *deviceName) { (void)deviceName; }
static inline void ble_scan_start(int duration_seconds) { (void)duration_seconds; }
static inline void ble_scan_stop(void) {}

#endif /* CONFIG_BT_ENABLED */

#endif /* NIMBLE_H */
