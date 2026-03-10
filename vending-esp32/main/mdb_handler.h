/*
 * VMflow.xyz - Cashless Vending Machine Firmware
 *
 * mdb_handler.h - MDB (Multi-Drop Bus) protocol handler
 *
 * This module implements the MDB cashless peripheral (slave) protocol
 * using GPIO bit-banging at 9600 baud with a 9th mode bit.  The ESP32
 * UART peripheral is NOT used for MDB because it does not natively
 * support 9-bit data frames; instead, each bit is manually sampled
 * and driven on PIN_MDB_RX (GPIO4) and PIN_MDB_TX (GPIO5).
 *
 * The MDB task runs on Core 1 at priority 1 in an infinite loop,
 * blocking on the start-bit of each incoming 9-bit word from the VMC.
 */

#ifndef MDB_HANDLER_H
#define MDB_HANDLER_H

#include <stdint.h>

/**
 * @brief Read one 9-bit word from the MDB bus via bit-banging.
 *
 * Waits for the start bit (low), then samples 9 data bits at 9600 baud
 * (104 us per bit).  The first sample is taken at the centre of bit 0
 * (52 us after the start bit edge).
 *
 * @param checksum  [in/out] Running checksum accumulator.  The lower
 *                  8 bits of the received word are added.  Pass NULL
 *                  when reading the checksum byte itself (final byte
 *                  of each MDB message).
 *
 * @return 9-bit value (bits 0-7 = data, bit 8 = mode bit).
 */
uint16_t read_9(uint8_t *checksum);

/**
 * @brief Write one 9-bit word to the MDB bus via bit-banging.
 *
 * Sends a start bit (low), 9 data bits LSB-first, and a stop bit (high)
 * at 9600 baud (104 us per bit).
 *
 * @param nth9  9-bit value to transmit (bits 0-7 = data, bit 8 = mode).
 */
void write_9(uint16_t nth9);

/**
 * @brief Transmit a multi-byte payload on the MDB bus with checksum.
 *
 * Sends each byte of the payload as a 9-bit word (mode bit = 0), then
 * sends the checksum byte with mode bit = 1 (BIT_MODE_SET | checksum).
 *
 * @param mdb_payload  Payload bytes to transmit.
 * @param length       Number of bytes in the payload (0 = send nothing).
 */
void write_payload_9(uint8_t *mdb_payload, uint8_t length);

/**
 * @brief FreeRTOS task: MDB cashless protocol main loop.
 *
 * This is the core of the MDB slave implementation.  It runs an infinite
 * loop that:
 *   1. Reads 9-bit words from the MDB bus
 *   2. Detects address-match for our cashless peripheral address
 *   3. Decodes commands (RESET, SETUP, POLL, VEND, READER, EXPANSION)
 *   4. Processes POLL by checking one-shot flags and the session queue
 *   5. Sends responses via write_payload_9()
 *   6. Manages the state machine (INACTIVE -> DISABLED -> ENABLED ->
 *      IDLE -> VEND)
 *   7. Implements a 60-second session timeout
 *
 * The task communicates with other modules through:
 *   - Global flags (vend_approved_todo, session_cancel_todo, etc.)
 *   - mdbSessionQueue (receives fundsAvailable from BLE/MQTT)
 *   - xLedEventGroup (updates LED status on state changes)
 *   - BLE notifications via ble_notify_send()
 *   - MQTT publishes for CASH_SALE events
 *
 * @param pvParameters  Unused (NULL).
 *
 * @note Pinned to Core 1 at priority 1. Stack size: 4096 bytes.
 * @note This task never returns.
 */
void vTaskMdbEvent(void *pvParameters);

#endif /* MDB_HANDLER_H */
