/*
 * VMflow.xyz - Cashless Vending Machine Firmware
 *
 * mdb_handler.c - MDB (Multi-Drop Bus) cashless peripheral protocol
 *
 * This module implements the complete MDB cashless device protocol using
 * GPIO bit-banging for the 9-bit serial interface.  The ESP32 UART is
 * not suitable for MDB because it lacks native 9-bit support.
 *
 * Physical interface:
 *   PIN_MDB_RX (GPIO4) - Input from VMC  (active-low start bit)
 *   PIN_MDB_TX (GPIO5) - Output to VMC   (idle high)
 *   Baud rate: 9600 bps -> 104 us per bit
 *
 * The 9th bit (mode bit) distinguishes:
 *   1 = Address/command byte (first byte of a new message)
 *   0 = Data byte (subsequent bytes)
 *
 * The MDB address is configured at build time via Kconfig:
 *   CONFIG_CASHLESS_DEVICE_ADDRESS (16 for cashless #1, 96 for #2)
 *
 * State machine flow:
 *   INACTIVE -> (SETUP CONFIG_DATA) -> DISABLED
 *   DISABLED -> (READER_ENABLE)     -> ENABLED
 *   ENABLED  -> (session begin)     -> IDLE
 *   IDLE     -> (VEND_REQUEST)      -> VEND
 *   VEND     -> (SUCCESS/FAILURE)   -> IDLE
 *   IDLE     -> (SESSION_COMPLETE)  -> ENABLED
 *   Any      -> (RESET)             -> INACTIVE
 */

#include <esp_log.h>
#include <driver/gpio.h>
#include <rom/ets_sys.h>
#include <string.h>
#include <time.h>

#include "config.h"
#include "mdb_handler.h"
#include "xor_crypto.h"
#include "nimble.h"
#include "yappy_handler.h"

/* ======================================================================
 * read_9 - Read one 9-bit MDB word via GPIO bit-banging
 * ======================================================================
 *
 * The MDB bus idles high.  A transmission begins with a start bit (low).
 * We wait for the falling edge, skip past the start bit (104 us), then
 * sample at the centre of each data bit (first sample at 52 us into
 * bit 0, then every 104 us for 9 bits total).
 *
 * Timing:
 *   Start bit:  ~104 us low
 *   Data bits:  9 x 104 us (LSB first, bit 8 = mode)
 *   Stop bit:   ~104 us high
 *
 * The checksum accumulator adds the lower 8 bits of the received value.
 * Pass NULL for the checksum byte itself (the last byte of each MDB
 * message) to avoid corrupting the running sum.
 */
uint16_t read_9(uint8_t *checksum)
{
    uint16_t coming_read = 0;

    /* Wait for start bit (line goes low) */
    while (gpio_get_level(PIN_MDB_RX))
        ;

    /* Skip past the start bit duration */
    ets_delay_us(104);

    /* Sample at the centre of each bit (52 us offset into the bit cell) */
    ets_delay_us(52);
    for (int x = 0; x < 9; x++) {
        coming_read |= (gpio_get_level(PIN_MDB_RX) << x);
        ets_delay_us(104);  /* Advance to next bit cell */
    }

    /* Accumulate checksum (lower 8 bits only) */
    if (checksum)
        *checksum += coming_read;

    return coming_read;
}

/* ======================================================================
 * write_9 - Write one 9-bit MDB word via GPIO bit-banging
 * ======================================================================
 *
 * Transmits: start bit (low), 9 data bits LSB-first, stop bit (high).
 * Total frame time: 11 bits x 104 us = ~1.14 ms.
 */
void write_9(uint16_t nth9)
{
    /* Start bit */
    gpio_set_level(PIN_MDB_TX, 0);
    ets_delay_us(104);

    /* 9 data bits, LSB first */
    for (uint8_t x = 0; x < 9; x++) {
        gpio_set_level(PIN_MDB_TX, (nth9 >> x) & 1);
        ets_delay_us(104);
    }

    /* Stop bit */
    gpio_set_level(PIN_MDB_TX, 1);
    ets_delay_us(104);
}

/* ======================================================================
 * write_payload_9 - Transmit a multi-byte MDB response with checksum
 * ======================================================================
 *
 * Sends each byte as a 9-bit word with mode bit = 0 (data), then
 * appends the checksum byte with mode bit = 1 (indicating end of
 * response).
 *
 * If length is 0, nothing is transmitted (ACK-only response, handled
 * by the VMC detecting no response within the t_response window).
 */
void write_payload_9(uint8_t *mdb_payload, uint8_t length)
{
    uint8_t checksum = 0x00;

    /* Send data bytes and accumulate checksum */
    for (int x = 0; x < length; x++) {
        checksum += mdb_payload[x];
        write_9(mdb_payload[x]);
    }

    /* Send checksum with mode bit set (indicates end of message) */
    write_9(BIT_MODE_SET | checksum);
}

/* ======================================================================
 * vTaskMdbEvent - FreeRTOS task: MDB cashless protocol main loop
 * ======================================================================
 *
 * This is the heart of the firmware.  It runs an infinite loop reading
 * 9-bit words from the MDB bus.  When an address-matched command is
 * detected, it:
 *
 *   1. Decodes the command and sub-command
 *   2. Reads additional data bytes as needed
 *   3. Validates the checksum
 *   4. Updates the state machine
 *   5. Prepares the response payload
 *   6. Transmits the response via write_payload_9()
 *
 * During POLL processing, the task checks one-shot flags that are set
 * by other modules (BLE handler, MQTT handler):
 *   - cashless_reset_todo  -> sends "Just Reset" (0x00)
 *   - mdbSessionQueue      -> sends "Begin Session" (0x03) with funds
 *   - session_cancel_todo  -> sends "Session Cancel" (0x04)
 *   - vend_approved_todo   -> sends "Vend Approved" (0x05) with price
 *   - vend_denied_todo     -> sends "Vend Denied" (0x06)
 *   - session_end_todo     -> sends "End Session" (0x07)
 *   - out_of_sequence_todo -> sends "Out of Sequence" (0x0B)
 *
 * If no flags are set during POLL, the task checks for a 60-second
 * session timeout and triggers session_cancel_todo if expired.
 *
 * BLE notifications are sent on VEND_REQUEST, VEND_SUCCESS,
 * VEND_FAILURE, and SESSION_COMPLETE events.  CASH_SALE events are
 * published to MQTT.
 */
void vTaskMdbEvent(void *pvParameters)
{
    time_t session_begin_time = 0;

    uint16_t fundsAvailable = 0;
    uint16_t itemPrice      = 0;
    uint16_t itemNumber     = 0;

    /* Payload buffer for MDB responses and transmission length */
    uint8_t mdb_payload[36];
    uint8_t available_tx = 0;

    for (;;) {

        /* Each MDB message begins with a checksum reset */
        uint8_t checksum = 0x00;

        /* Read a 9-bit word from the bus */
        uint16_t coming_read = read_9(&checksum);

        /* Check if the mode bit is set (address/command byte) */
        if (coming_read & BIT_MODE_SET) {

            if ((uint8_t)coming_read == ACK) {
                /* VMC acknowledged our previous response - nothing to do */
            } else if ((uint8_t)coming_read == RET) {
                /* VMC requests retransmit - not implemented (rare) */
            } else if ((uint8_t)coming_read == NAK) {
                /* VMC rejected our response - nothing to do */
            } else if ((coming_read & BIT_ADD_SET) == CONFIG_CASHLESS_DEVICE_ADDRESS) {

                /*
                 * Address match!  The 3-bit command field tells us what
                 * the VMC wants.
                 */
                available_tx = 0;

                switch (coming_read & BIT_CMD_SET) {

                /* ---- RESET ---- */
                case RESET: {

                    if (read_9(NULL) != checksum) continue;

                    /*
                     * A RESET command returns us to INACTIVE_STATE.
                     * If we were in VEND_STATE, this is interpreted as
                     * an implicit VEND_SUCCESS by the VMC.
                     */
                    cashless_reset_todo = true;
                    machine_state = INACTIVE_STATE;

                    xEventGroupClearBits(xLedEventGroup, BIT_EVT_MDB);
                    xEventGroupSetBits(xLedEventGroup, BIT_EVT_TRIGGER);

                    ESP_LOGI(TAG, "RESET");
                    break;
                }

                /* ---- SETUP ---- */
                case SETUP: {
                    switch (read_9(&checksum)) {

                    /*
                     * CONFIG_DATA: VMC sends its capabilities (feature
                     * level, display size).  We respond with our reader
                     * configuration (currency, scale factor, etc.).
                     */
                    case CONFIG_DATA: {
                        uint8_t vmcFeatureLevel    = read_9(&checksum);
                        uint8_t vmcColumnsOnDisplay = read_9(&checksum);
                        uint8_t vmcRowsOnDisplay   = read_9(&checksum);
                        uint8_t vmcDisplayInfo     = read_9(&checksum);

                        (void)vmcDisplayInfo;
                        (void)vmcRowsOnDisplay;
                        (void)vmcColumnsOnDisplay;
                        (void)vmcFeatureLevel;

                        if (read_9(NULL) != checksum) continue;

                        machine_state = DISABLED_STATE;

                        /* Build Reader Config Data response */
                        mdb_payload[0] = 0x01;                              /* Reader Config Data */
                        mdb_payload[1] = 1;                                 /* Reader Feature Level */
                        mdb_payload[2] = CONFIG_MDB_CURRENCY_CODE >> 8;     /* Country Code High */
                        mdb_payload[3] = CONFIG_MDB_CURRENCY_CODE & 0xff;   /* Country Code Low */
                        mdb_payload[4] = CONFIG_MDB_SCALE_FACTOR;           /* Scale Factor */
                        mdb_payload[5] = CONFIG_MDB_DECIMAL_PLACES;         /* Decimal Places */
                        mdb_payload[6] = 3;                                 /* Max Response Time (5s) */
                        mdb_payload[7] = 0b00001001;                        /* Miscellaneous Options */
                        available_tx = 8;

                        ESP_LOGI(TAG, "CONFIG_DATA");
                        break;
                    }

                    /*
                     * MAX_MIN_PRICES: VMC tells us the price range it
                     * supports.  We just acknowledge (no response data).
                     */
                    case MAX_MIN_PRICES: {
                        uint16_t maxPrice = (read_9(&checksum) << 8) | read_9(&checksum);
                        uint16_t minPrice = (read_9(&checksum) << 8) | read_9(&checksum);

                        (void)maxPrice;
                        (void)minPrice;

                        if (read_9(NULL) != checksum) continue;

                        ESP_LOGI(TAG, "MAX_MIN_PRICES");
                        break;
                    }
                    }

                    break;
                }

                /* ---- POLL ---- */
                case POLL: {

                    if (read_9(NULL) != checksum) continue;

                    /*
                     * POLL is the most frequent command.  The VMC asks
                     * "do you have anything to report?"  We check our
                     * one-shot flags in priority order.
                     */

                    if (cashless_reset_todo) {
                        /* Report "Just Reset" after a RESET command */
                        cashless_reset_todo = false;
                        mdb_payload[0] = 0x00;
                        available_tx = 1;

                    } else if (machine_state <= ENABLED_STATE &&
                               xQueueReceive(mdbSessionQueue, &fundsAvailable, 0)) {
                        /*
                         * A BLE or MQTT credit has been received.
                         * Begin a new vending session with the given
                         * funds available.
                         */
                        session_begin_todo = false;

                        machine_state = IDLE_STATE;

                        mdb_payload[0] = 0x03;                  /* Begin Session */
                        mdb_payload[1] = fundsAvailable >> 8;   /* Funds high byte */
                        mdb_payload[2] = fundsAvailable;        /* Funds low byte */
                        available_tx = 3;

                        time(&session_begin_time);

                    } else if (session_cancel_todo) {
                        /* Session cancelled (by BLE cmd 0x04 or timeout) */
                        session_cancel_todo = false;

                        mdb_payload[0] = 0x04;
                        available_tx = 1;

                    } else if (vend_approved_todo) {
                        /* Vend approved (by BLE cmd 0x03 or MQTT credit) */
                        vend_approved_todo = false;

                        mdb_payload[0] = 0x05;                /* Vend Approved */
                        mdb_payload[1] = itemPrice >> 8;
                        mdb_payload[2] = itemPrice;
                        available_tx = 3;

                    } else if (vend_denied_todo) {
                        /* Vend denied (insufficient funds or cancel) */
                        vend_denied_todo = false;

                        mdb_payload[0] = 0x06;
                        available_tx = 1;
                        machine_state = IDLE_STATE;

                    } else if (session_end_todo) {
                        /* Session complete - return to ENABLED */
                        session_end_todo = false;

                        mdb_payload[0] = 0x07;
                        available_tx = 1;
                        machine_state = ENABLED_STATE;

                    } else if (out_of_sequence_todo) {
                        /* Command was out of sequence for current state */
                        out_of_sequence_todo = false;

                        mdb_payload[0] = 0x0b;
                        available_tx = 1;

                    } else {
                        /*
                         * No pending events.  Check for session timeout:
                         * if we've been in IDLE_STATE or VEND_STATE for
                         * more than 60 seconds, cancel the session.
                         *
                         * In kiosk mode, the Yappy handler sets
                         * session_timer_reset_todo to keep the session
                         * alive during Yappy payment polling.
                         */
                        time_t now = time(NULL);

                        if (session_timer_reset_todo) {
                            /* Yappy is actively polling - reset the timer */
                            session_timer_reset_todo = false;
                            session_begin_time = now;
                        }

                        if (machine_state >= IDLE_STATE &&
                            (now - session_begin_time) > 60) {
                            session_cancel_todo = true;
                        }
                    }

                    break;
                }

                /* ---- VEND ---- */
                case VEND: {
                    switch (read_9(&checksum)) {

                    /*
                     * VEND_REQUEST: User selected a product on the
                     * machine.  The VMC sends the price and item number.
                     * We transition to VEND_STATE and notify via BLE.
                     *
                     * If fundsAvailable is non-zero and not unlimited
                     * (0xFFFF), we auto-approve/deny based on the price.
                     */
                    case VEND_REQUEST: {
                        itemPrice  = (read_9(&checksum) << 8) | read_9(&checksum);
                        itemNumber = (read_9(&checksum) << 8) | read_9(&checksum);

                        if (read_9(NULL) != checksum) continue;

                        machine_state = VEND_STATE;

                        /*
                         * Share price/item with Yappy handler for QR generation.
                         * These globals are read by the dashboard state endpoint.
                         */
                        yappy_item_price = itemPrice;
                        yappy_item_number = itemNumber;

                        if (fundsAvailable && (fundsAvailable != 0xffff)) {
                            /* Specific credit amount - auto-approve/deny */
                            if (itemPrice <= fundsAvailable) {
                                vend_approved_todo = true;
                            } else {
                                vend_denied_todo = true;
                            }
                        } else if (yappy_kiosk_mode && fundsAvailable == 0xffff) {
                            /*
                             * Kiosk mode with unlimited funds (0xFFFF):
                             * Trigger Yappy QR generation instead of auto-approve.
                             * The Yappy handler will set vend_approved_todo when
                             * payment is confirmed.
                             */
                            yappy_request_qr(itemPrice, itemNumber);
                            ESP_LOGI(TAG, "VEND_REQUEST -> Yappy QR requested");
                        }

                        /* Send BLE notification: VEND_REQUEST (0x0A) */
                        uint8_t payload[19];
                        xorEncodeWithPasskey(0x0a, itemPrice, itemNumber, 0, (uint8_t *)&payload);
                        ble_notify_send((char *)&payload, sizeof(payload));

                        ESP_LOGI(TAG, "VEND_REQUEST price=%u item=%u", itemPrice, itemNumber);
                        break;
                    }

                    /* VEND_CANCEL: VMC cancels the pending vend */
                    case VEND_CANCEL: {
                        if (read_9(NULL) != checksum) continue;

                        vend_denied_todo = true;
                        break;
                    }

                    /*
                     * VEND_SUCCESS: Product was dispensed successfully.
                     * Return to IDLE and notify via BLE.
                     */
                    case VEND_SUCCESS: {
                        itemNumber = (read_9(&checksum) << 8) | read_9(&checksum);

                        if (read_9(NULL) != checksum) continue;

                        machine_state = IDLE_STATE;

                        /* Send BLE notification: VEND_SUCCESS (0x0B) */
                        uint8_t payload[19];
                        xorEncodeWithPasskey(0x0b, itemPrice, itemNumber, 0, (uint8_t *)&payload);
                        ble_notify_send((char *)&payload, sizeof(payload));

                        ESP_LOGI(TAG, "VEND_SUCCESS");
                        break;
                    }

                    /* VEND_FAILURE: Dispensing failed, return to IDLE */
                    case VEND_FAILURE: {
                        if (read_9(NULL) != checksum) continue;

                        machine_state = IDLE_STATE;

                        /* Send BLE notification: VEND_FAILURE (0x0C) */
                        uint8_t payload[19];
                        xorEncodeWithPasskey(0x0c, itemPrice, itemNumber, 0, (uint8_t *)&payload);
                        ble_notify_send((char *)&payload, sizeof(payload));
                        break;
                    }

                    /*
                     * SESSION_COMPLETE: VMC signals end of the vending
                     * session.  We'll report End Session on the next POLL.
                     */
                    case SESSION_COMPLETE: {
                        if (read_9(NULL) != checksum) continue;

                        session_end_todo = true;

                        /* Send BLE notification: SESSION_COMPLETE (0x0D) */
                        uint8_t payload[19];
                        xorEncodeWithPasskey(0x0d, itemPrice, itemNumber, 0, (uint8_t *)&payload);
                        ble_notify_send((char *)&payload, sizeof(payload));

                        /*
                         * Kiosk mode: auto-restart session after completion.
                         * The session_end_todo flag will transition us back to
                         * ENABLED_STATE, then the READER_ENABLE handler won't
                         * fire again.  So we schedule a new session start here.
                         * The queue write will be consumed on the next POLL after
                         * session_end_todo is processed and state returns to ENABLED.
                         */
                        if (yappy_kiosk_mode) {
                            uint16_t unlimited = 0xFFFF;
                            xQueueOverwrite(mdbSessionQueue, &unlimited);
                            ESP_LOGI(TAG, "SESSION_COMPLETE -> Kiosk auto-restart");
                        }

                        ESP_LOGI(TAG, "SESSION_COMPLETE");
                        break;
                    }

                    /*
                     * CASH_SALE: A cash transaction occurred on the
                     * machine.  We publish it to MQTT for the backend
                     * to record.
                     */
                    case CASH_SALE: {
                        uint16_t cashItemPrice  = (read_9(&checksum) << 8) | read_9(&checksum);
                        uint16_t cashItemNumber = (read_9(&checksum) << 8) | read_9(&checksum);

                        if (read_9(NULL) != checksum) continue;

                        /* Build encrypted payload and publish to MQTT */
                        uint8_t payload[19];
                        xorEncodeWithPasskey(0x21, cashItemPrice, cashItemNumber, 0, (uint8_t *)&payload);

                        char topic[128];
                        snprintf(topic, sizeof(topic), "domain.panamavendingmachines.com/%s/sale", my_subdomain);

                        esp_mqtt_client_publish(mqttClient, topic, (char *)&payload, sizeof(payload), 1, 0);

                        ESP_LOGI(TAG, "CASH_SALE");
                        break;
                    }
                    }

                    break;
                }

                /* ---- READER ---- */
                case READER: {
                    switch (read_9(&checksum)) {

                    /* READER_DISABLE: Disable the cashless reader */
                    case READER_DISABLE: {
                        if (read_9(NULL) != checksum) continue;

                        machine_state = DISABLED_STATE;

                        xEventGroupClearBits(xLedEventGroup, BIT_EVT_MDB);
                        xEventGroupSetBits(xLedEventGroup, BIT_EVT_TRIGGER);
                        break;
                    }

                    /* READER_ENABLE: Enable the cashless reader */
                    case READER_ENABLE: {
                        if (read_9(NULL) != checksum) continue;

                        machine_state = ENABLED_STATE;

                        xEventGroupSetBits(xLedEventGroup, BIT_EVT_MDB | BIT_EVT_TRIGGER);

                        /*
                         * Kiosk mode: auto-start a session with unlimited funds.
                         * This keeps the machine always ready for VEND_REQUEST.
                         * The actual payment approval comes from the Yappy handler.
                         */
                        if (yappy_kiosk_mode) {
                            uint16_t unlimited = 0xFFFF;
                            xQueueOverwrite(mdbSessionQueue, &unlimited);
                            ESP_LOGI(TAG, "READER_ENABLE -> Kiosk auto-session");
                        }
                        break;
                    }

                    /* READER_CANCEL: Cancel pending reader operation */
                    case READER_CANCEL: {
                        if (read_9(NULL) != checksum) continue;

                        mdb_payload[0] = 0x08;  /* Cancelled */
                        available_tx = 1;

                        ESP_LOGI(TAG, "READER_CANCEL");
                        break;
                    }
                    }

                    break;
                }

                /* ---- EXPANSION ---- */
                case EXPANSION: {
                    switch (read_9(&checksum)) {

                    /*
                     * REQUEST_ID: VMC requests the 30-byte peripheral
                     * identification string.  The VMC sends its own
                     * 29-byte ID first (which we read and discard).
                     */
                    case REQUEST_ID: {
                        /* Read and discard 29 bytes of VMC identification */
                        for (uint8_t x = 0; x < 29; x++) read_9(&checksum);

                        if (read_9(NULL) != checksum) continue;

                        /* Build our 30-byte Peripheral ID response */
                        mdb_payload[0] = 0x09;                          /* Peripheral ID */
                        memcpy(&mdb_payload[1],  "VMF", 3);             /* Manufacturer code */
                        memcpy(&mdb_payload[4],  "            ", 12);   /* Serial number */
                        memcpy(&mdb_payload[16], "            ", 12);   /* Model number */
                        memcpy(&mdb_payload[28], "03", 2);              /* Software version */

                        available_tx = 30;

                        ESP_LOGI(TAG, "REQUEST_ID");
                        break;
                    }
                    }

                    break;
                }
                }

                /* Transmit the prepared response (if any) */
                write_payload_9((uint8_t *)&mdb_payload, available_tx);

            } else {
                /* Address does not match our peripheral - ignore */
            }
        }
    }
}
