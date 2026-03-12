/*
 * VMflow.xyz - Cashless Vending Machine Firmware
 *
 * yappy_handler.h - Yappy mobile payment via Supabase Edge Function
 *
 * This module handles the Yappy payment flow by calling the Supabase
 * Edge Function (yappy-payment) which proxies all Yappy API interactions.
 * This matches the Android app's approach and offloads session management,
 * token caching, and sale recording to the server.
 *
 * Flow:
 *   1. ESP32 XOR-encodes price/item into a 19-byte payload
 *   2. Base64-encodes it and POSTs to the Edge Function (generate-qr)
 *   3. Edge Function calls Yappy API, returns QR hash + transaction ID
 *   4. ESP32 polls Edge Function (check-status) every 3 seconds
 *   5. On payment: ESP32 sets vend_approved_todo for MDB dispensing
 *   6. Edge Function records the sale in Supabase automatically
 *
 * The Yappy state machine runs in its own FreeRTOS task (vTaskYappyPoll)
 * on Core 0 and communicates with the MDB task via shared flags defined
 * in config.h (vend_approved_todo, session_timer_reset_todo).
 *
 * Authentication: ESP32 uses x-device-key header (shared secret)
 * instead of Supabase JWT. The Edge Function accepts both auth methods.
 */

#ifndef YAPPY_HANDLER_H
#define YAPPY_HANDLER_H

#include <stdint.h>
#include <stdbool.h>

/* ======================== YAPPY STATE MACHINE ============================ */
/*
 * Payment flow:
 *
 *   IDLE  --(VEND_REQUEST)--> QR_PENDING --(API ok)--> QR_READY
 *     ^                          |                        |
 *     |                     (API fail)              (poll starts)
 *     |                          |                        |
 *     |                          v                        v
 *     |                        ERROR                   POLLING
 *     |                          |                        |
 *     +--------(cancel/reset)----+-----(PAGADO)------>  PAID
 *     |                                                   |
 *     +<-----------(auto-reset after dispense)------------+
 */
typedef enum {
    YAPPY_IDLE       = 0,   /* No payment in progress                    */
    YAPPY_QR_PENDING = 1,   /* QR generation request sent, waiting       */
    YAPPY_QR_READY   = 2,   /* QR hash received, displayed on dashboard  */
    YAPPY_POLLING    = 3,   /* Polling Edge Function for payment status  */
    YAPPY_PAID       = 4,   /* Payment confirmed, vend approved          */
    YAPPY_ERROR      = 5    /* Error state (API failure, timeout, etc.)  */
} yappy_state_t;

/* ======================== YAPPY STATE STRUCT ============================= */
/*
 * Thread-safe state container accessed by:
 *   - yappy_handler.c (writer: Yappy poll task)
 *   - webui_server.c  (reader: HTTP state endpoint)
 *   - mdb_handler.c   (reader: VEND_REQUEST triggers QR generation)
 */
typedef struct {
    yappy_state_t state;

    char qr_hash[512];          /* QR code data string for client rendering */
    char transaction_id[64];    /* Yappy transaction ID for status polling   */

    int64_t token_expires_at;   /* Unused (Edge Function manages tokens)    */

    uint16_t item_price;        /* Current item price (MDB scale units)     */
    uint16_t item_number;       /* Current item/slot number                 */
    float display_price;        /* Human-readable price (e.g. 1.50)         */

    char error_msg[128];        /* Last error message for dashboard display */
} yappy_payment_state_t;

/* ======================== PUBLIC API ====================================== */

/**
 * @brief Initialize the Yappy payment handler.
 *
 * Loads Supabase credentials from Kconfig.
 * Must be called after NVS and WiFi initialization in main.c.
 */
void yappy_init(void);

/**
 * @brief Request a Yappy QR code for payment.
 *
 * Called when a VEND_REQUEST is received and kiosk mode is active.
 * Transitions state from IDLE to QR_PENDING, then QR_READY on success.
 *
 * @param price       Item price in MDB scale-factor units.
 * @param item_number Item/slot number from the vending machine.
 */
void yappy_request_qr(uint16_t price, uint16_t item_number);

/**
 * @brief Cancel the current Yappy payment.
 *
 * Calls Edge Function to cancel the transaction and resets state to IDLE.
 * Called from the dashboard cancel button (via webui_server.c).
 */
void yappy_cancel(void);

/**
 * @brief Get the current Yappy payment state (thread-safe copy).
 *
 * Returns a snapshot of the current state for the web dashboard
 * to render.  Called by the /api/v1/vending/state endpoint.
 *
 * @return Copy of the current yappy_payment_state_t.
 */
yappy_payment_state_t yappy_get_state(void);

/**
 * @brief FreeRTOS task: Yappy payment polling loop.
 *
 * Runs on Core 0, sleeps when IDLE, polls every 3 seconds when
 * QR_READY or POLLING.  On payment confirmation:
 *   1. Sets vend_approved_todo = true
 *   2. Sets session_timer_reset_todo = true
 *   3. Edge Function records the sale automatically
 *   4. Transitions to YAPPY_PAID
 *
 * @param pvParameters  Unused (NULL).
 */
void vTaskYappyPoll(void *pvParameters);

#endif /* YAPPY_HANDLER_H */
