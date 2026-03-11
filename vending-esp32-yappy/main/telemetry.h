/*
 * VMflow.xyz - Cashless Vending Machine Firmware
 *
 * telemetry.h - DEX / DDCMP telemetry collection
 *
 * This module implements two telemetry collection protocols used to
 * read audit data from vending machines:
 *
 * 1. DEX (Data Exchange) - Based on the EVA-DTS standard.  Uses a
 *    DDCMP-like handshake followed by DEX block transfers.  The
 *    physical layer is standard UART at 9600 baud on UART1.
 *
 * 2. DDCMP (Digital Data Communication Message Protocol) - A
 *    point-to-point protocol used by some VMCs for audit data.
 *    Runs at 2400 baud on the same UART1 port.
 *
 * Both protocols write their received data into the dexRingbuf ring
 * buffer, which is periodically published to MQTT by the telemetry
 * timer callback.
 *
 * Physical interface:
 *   PIN_DEX_TX (GPIO9) - UART1 TX
 *   PIN_DEX_RX (GPIO8) - UART1 RX
 *   Baud rate: 9600 (DEX) / 2400 (DDCMP)
 */

#ifndef TELEMETRY_H
#define TELEMETRY_H

#include <stdint.h>

/**
 * @brief Calculate CRC-16 (Modbus-style, polynomial 0xA001).
 *
 * Processes one byte and updates the running CRC.  Used by both
 * DEX and DDCMP protocols for block integrity verification.
 *
 * @param pCrc   [in/out] Running CRC-16 accumulator.
 * @param uData  Pointer to the byte to process.
 *
 * @return The same uData pointer (allows chaining with uart_write_bytes).
 */
char *calc_crc_16(uint16_t *pCrc, char *uData);

/**
 * @brief Read telemetry data using the DEX protocol.
 *
 * Performs the complete DEX handshake and data transfer:
 *   1. First handshake (ENQ / DLE-0 / DLE-SOH / comm ID / operation
 *      request / DLE-ETX / CRC / DLE-1 / EOT)
 *   2. Second handshake (ENQ / DLE-0 / DLE-SOH / response / DLE-ETX
 *      / CRC / DLE-1 / EOT)
 *   3. Data transfer loop (DLE-STX / data blocks / DLE-ETB or DLE-ETX)
 *
 * Received audit data bytes are written to the dexRingbuf ring buffer.
 * Sets UART1 baud rate to 9600 before starting.
 *
 * @note This function blocks for the duration of the transfer.
 *       Called from the telemetry timer callback context.
 */
void readTelemetryDEX(void);

/**
 * @brief Read telemetry data using the DDCMP protocol.
 *
 * Performs the DDCMP session:
 *   1. STARTUP exchange (0x05 0x06 / 0x05 0x07)
 *   2. WHO_ARE_YOU command
 *   3. READ_DATA / Audit Collection request
 *   4. Data reception loop with ACK handshaking
 *   5. FINIS termination
 *
 * Received audit data bytes are written to the dexRingbuf ring buffer.
 * Sets UART1 baud rate to 2400 before starting.
 *
 * @note This function blocks for the duration of the transfer.
 *       Called from the telemetry timer callback context.
 */
void readTelemetryDDCMP(void);

/**
 * @brief Timer callback: collect and publish telemetry data.
 *
 * Called every 12 hours by an esp_timer periodic timer.  Attempts
 * DDCMP first, then DEX.  After collection, reads all data from
 * dexRingbuf and publishes it to the MQTT DEX topic:
 *   domain.panamavendingmachines.com/{subdomain}/dex
 *
 * @param arg  Unused timer callback argument.
 */
void requestTelemetryData(void *arg);

#endif /* TELEMETRY_H */
