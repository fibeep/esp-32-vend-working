/*
 * VMflow.xyz - Cashless Vending Machine Firmware
 *
 * telemetry.c - DEX / DDCMP telemetry collection
 *
 * Implements two vending machine audit-data protocols:
 *
 * DEX (Data Exchange):
 *   Based on the EVA-DTS (European Vending Association - Data Transfer
 *   Standard).  Uses a two-phase handshake followed by block-mode data
 *   transfer.  The communication ID is a fixed 10-character string.
 *   Runs at 9600 baud on UART1.
 *
 * DDCMP (Digital Data Communication Message Protocol):
 *   A byte-oriented protocol with STARTUP, DATA, and ACK messages.
 *   Each message has a 6-byte header with CRC-16 integrity.  Data
 *   payloads carry audit information in a proprietary format.
 *   Runs at 2400 baud on UART1.
 *
 * Both protocols write received audit data into dexRingbuf (8 KB ring
 * buffer).  The requestTelemetryData() timer callback reads the buffer
 * and publishes it to MQTT.
 */

#include <esp_log.h>
#include <driver/uart.h>
#include <string.h>
#include <stdio.h>

#include "config.h"
#include "telemetry.h"

/* ======================================================================
 * calc_crc_16 - CRC-16/MODBUS calculation (one byte at a time)
 * ======================================================================
 *
 * Standard CRC-16 with polynomial 0xA001 (bit-reversed 0x8005).
 * Processes 8 bits of the input byte, LSB first.
 *
 * Usage pattern (chained with uart_write_bytes):
 *   uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "R"), 1);
 *
 * The return value is the same pointer passed in, enabling chaining.
 */
char *calc_crc_16(uint16_t *pCrc, char *uData)
{
    uint8_t data = *uData;

    for (uint8_t iBit = 0; iBit < 8; iBit++, data >>= 1) {

        if ((data ^ *pCrc) & 0x01) {
            *pCrc >>= 1;
            *pCrc ^= 0xA001;
        } else {
            *pCrc >>= 1;
        }
    }

    return uData;
}

/* ======================================================================
 * readTelemetryDEX - DEX protocol data collection
 * ======================================================================
 *
 * Complete DEX session:
 *
 * FIRST HANDSHAKE (we initiate):
 *   -> ENQ (0x05)
 *   <- DLE '0' (0x10 0x30)
 *   -> DLE SOH (0x10 0x01)
 *   -> Communication ID (10 chars: "1234567890")
 *   -> Operation Request  ("R")
 *   -> Revision & Level   ("R00L06")
 *   -> DLE ETX (0x10 0x03) + CRC-16
 *   <- DLE '1' (0x10 0x31)
 *   -> EOT (0x04)
 *
 * SECOND HANDSHAKE (VMC initiates):
 *   <- ENQ
 *   -> DLE '0'
 *   <- DLE SOH + response code + comm ID + revision + DLE ETX + CRC
 *   -> DLE '1'
 *   <- EOT
 *
 * DATA TRANSFER:
 *   <- ENQ
 *   -> DLE '0' / '1' (alternating)
 *   <- DLE STX + data bytes + DLE ETB/ETX + CRC
 *   Repeat until DLE ETX (end of data)
 *
 * Each received data byte is pushed into dexRingbuf.
 */
void readTelemetryDEX(void)
{
    uart_set_baudrate(UART_NUM_1, 9600);

    uint8_t data[32];

    /* ---- First Handshake ---- */

    /* ENQ -> */
    uart_write_bytes(UART_NUM_1, "\x05", 1);

    /* DLE 0 <- */
    uart_read_bytes(UART_NUM_1, &data, 2, pdMS_TO_TICKS(100));
    if (data[0] != 0x10 || data[1] != '0') return;

    /* DLE SOH -> */
    uart_write_bytes(UART_NUM_1, "\x10\x01", 2);

    uint16_t crc = 0x0000;

    /* Communication ID (10 characters) */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "1"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "2"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "3"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "4"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "5"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "6"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "7"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "8"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "9"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "0"), 1);

    /* Operation Request */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "R"), 1);

    /* Revision & Level */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "R"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "0"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "0"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "L"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "0"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "6"), 1);

    /* DLE ETX + CRC */
    uart_write_bytes(UART_NUM_1, "\x10", 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x03"), 1);

    data[0] = crc % 256;
    data[1] = crc / 256;
    uart_write_bytes(UART_NUM_1, &data, 2);

    /* DLE '1' <- */
    uart_read_bytes(UART_NUM_1, &data, 2, pdMS_TO_TICKS(100));
    if (data[0] != 0x10 || data[1] != '1') return;

    /* EOT -> */
    uart_write_bytes(UART_NUM_1, "\x04", 1);

    /* ---- Second Handshake (VMC initiates) ---- */

    /* ENQ <- */
    uart_read_bytes(UART_NUM_1, &data, 1, pdMS_TO_TICKS(100));
    if (data[0] != 0x05) return;

    /* DLE '0' -> */
    uart_write_bytes(UART_NUM_1, "\x10\x30", 2);

    /* DLE SOH <- */
    uart_read_bytes(UART_NUM_1, &data, 2, pdMS_TO_TICKS(100));
    if (data[0] != 0x10 || data[1] != 0x01) return;

    /* Response Code <- (2 bytes) */
    uart_read_bytes(UART_NUM_1, &data, 2, pdMS_TO_TICKS(100));
    /* Communication ID <- (10 bytes) */
    uart_read_bytes(UART_NUM_1, &data, 10, pdMS_TO_TICKS(100));
    /* Revision & Level <- (6 bytes) */
    uart_read_bytes(UART_NUM_1, &data, 6, pdMS_TO_TICKS(100));

    /* DLE ETX <- */
    uart_read_bytes(UART_NUM_1, &data, 2, pdMS_TO_TICKS(100));
    if (data[0] != 0x10 || data[1] != 0x03) return;

    /* CRC <- */
    uart_read_bytes(UART_NUM_1, &data, 2, pdMS_TO_TICKS(100));

    /* DLE '1' -> */
    uart_write_bytes(UART_NUM_1, "\x10\x31", 2);

    /* EOT <- */
    uart_read_bytes(UART_NUM_1, &data, 1, pdMS_TO_TICKS(100));
    if (data[0] != 0x04) return;

    /* ---- Data Transfer ---- */

    /* ENQ <- */
    uart_read_bytes(UART_NUM_1, &data, 1, pdMS_TO_TICKS(100));
    if (data[0] != 0x05) return;

    uint8_t block = 0x00;
    for (;;) {
        /* Send DLE + alternating block number ('0' or '1') */
        data[0] = 0x10;
        data[1] = ('0' + (block++ & 1));
        uart_write_bytes(UART_NUM_1, &data, 2);

        /* DLE STX <- */
        uart_read_bytes(UART_NUM_1, &data, 2, pdMS_TO_TICKS(200));
        if (data[0] != 0x10 || data[1] != 0x02) return;

        /* Read data bytes until DLE escape */
        for (;;) {
            uart_read_bytes(UART_NUM_1, &data, 1, pdMS_TO_TICKS(200));

            if (data[0] == 0x10) {  /* DLE escape */
                uart_read_bytes(UART_NUM_1, &data, 1, pdMS_TO_TICKS(200));

                if (data[0] == 0x17) {  /* ETB - end of block, more to follow */
                    /* CRC <- */
                    uart_read_bytes(UART_NUM_1, &data, 2, pdMS_TO_TICKS(200));
                    break;

                } else if (data[0] == 0x03) {  /* ETX - end of transmission */
                    /* CRC <- */
                    uart_read_bytes(UART_NUM_1, &data, 2, pdMS_TO_TICKS(200));

                    /* Final DLE + block number -> */
                    data[0] = 0x10;
                    data[1] = ('0' + (block++ & 0x01));
                    uart_write_bytes(UART_NUM_1, &data, 2);

                    /* EOT <- */
                    uart_read_bytes(UART_NUM_1, &data, 1, pdMS_TO_TICKS(200));

                    return;  /* Transfer complete */
                }
            }

            /* Store received data byte in ring buffer */
            xRingbufferSend(dexRingbuf, &data[0], 1, 0);
        }
    }
}

/* ======================================================================
 * readTelemetryDDCMP - DDCMP protocol data collection
 * ======================================================================
 *
 * Complete DDCMP session:
 *
 * 1. STARTUP exchange:
 *    -> STARTUP msg (05 06 40 00 00 01 + CRC)
 *    <- STARTUP ACK  (05 07 ...)
 *
 * 2. WHO_ARE_YOU command:
 *    -> DATA_HEADER (81 10 40 00 xx 01 + CRC)
 *    -> WHO_ARE_YOU payload (77 E0 00 + security + passcode + date + time + user)
 *    <- ACK (05 01 ...)
 *    <- DATA_HEADER + response
 *
 * 3. READ_DATA request:
 *    -> ACK
 *    -> DATA_HEADER (81 09 40 rr xx 01 + CRC)
 *    -> READ_DATA payload (77 E2 00 02 01 00 00 00 00 + CRC)
 *    <- ACK
 *    <- DATA_HEADER + audit data
 *
 * 4. Data reception loop:
 *    <- DATA_HEADER + data blocks
 *    -> ACK for each block
 *    Continue until last_package flag (bit 7 of header byte 2)
 *    -> FINIS command to end session
 *
 * Audit data bytes (excluding protocol headers and CRC) are stored
 * in dexRingbuf.
 */
void readTelemetryDDCMP(void)
{
    uart_set_baudrate(UART_NUM_1, 2400);

    uint8_t buffer_rx[1024];
    uint8_t seq_rr_ddcmp;
    uint8_t seq_xx_ddcmp = 0;
    uint32_t n_bytes_message;
    uint16_t crc = 0x0000;
    uint8_t last_package;

    uint8_t crc_[2];

    /* ---- STARTUP ---- */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x05"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x06"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x40"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x00"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x00"), 1);  /* mbd */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x01"), 1);  /* sadd */
    crc_[0] = crc % 256;
    crc_[1] = crc / 256;
    uart_write_bytes(UART_NUM_1, &crc_, 2);

    if (uart_read_bytes(UART_NUM_1, &buffer_rx, 8, pdMS_TO_TICKS(200)) != 8)
        return;

    if ((buffer_rx[0] != 0x05) || (buffer_rx[1] != 0x07)) {
        return;
    }  /* STARTUP ACK */

    /* ---- WHO_ARE_YOU ---- */
    crc = 0x0000;

    /* Data message header */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x81"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x10"), 1);  /* nn (payload length) */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x40"), 1);  /* mm */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x00"), 1);  /* rr */
    ++seq_xx_ddcmp;
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, (char *)&seq_xx_ddcmp), 1);  /* xx */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x01"), 1);  /* sadd */
    crc_[0] = crc % 256;
    crc_[1] = crc / 256;
    uart_write_bytes(UART_NUM_1, &crc_, 2);

    crc = 0x0000;

    /* WHO_ARE_YOU payload */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x77"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\xe0"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x00"), 1);

    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x00"), 1);  /* security code */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x00"), 1);

    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x00"), 1);  /* pass code */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x00"), 1);

    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x01"), 1);  /* date dd */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x01"), 1);  /* mm */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x70"), 1);  /* yy */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x00"), 1);  /* time hh */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x00"), 1);  /* mm */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x00"), 1);  /* ss */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x00"), 1);  /* u2 */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x00"), 1);  /* u1 */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x0c"), 1);  /* 0x0c = Route Person */

    crc_[0] = crc % 256;
    crc_[1] = crc / 256;
    uart_write_bytes(UART_NUM_1, &crc_, 2);

    if (uart_read_bytes(UART_NUM_1, &buffer_rx, 8, pdMS_TO_TICKS(200)) != 8)
        return;

    if ((buffer_rx[0] != 0x05) || (buffer_rx[1] != 0x01)) {
        return;
    }  /* ACK */

    if (uart_read_bytes(UART_NUM_1, &buffer_rx, 8, pdMS_TO_TICKS(200)) != 8)
        return;

    if (buffer_rx[0] != 0x81) {
        return;
    }  /* DATA HEADER */

    seq_rr_ddcmp = buffer_rx[4];

    n_bytes_message = ((buffer_rx[2] & 0x3f) * 256) + buffer_rx[1];
    n_bytes_message += 2;  /* CRC-16 */

    if (uart_read_bytes(UART_NUM_1, &buffer_rx, n_bytes_message, pdMS_TO_TICKS(200)) != n_bytes_message)
        return;

    /* ---- ACK the WHO_ARE_YOU response ---- */
    crc = 0x0000;

    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x05"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x01"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x40"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, (char *)&seq_rr_ddcmp), 1);  /* rr */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x00"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x01"), 1);  /* sadd */
    crc_[0] = crc % 256;
    crc_[1] = crc / 256;
    uart_write_bytes(UART_NUM_1, &crc_, 2);

    /* ---- READ_DATA request ---- */
    crc = 0x0000;

    /* Data message header */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x81"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x09"), 1);  /* nn */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x40"), 1);  /* mm */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, (char *)&seq_rr_ddcmp), 1);  /* rr */
    ++seq_xx_ddcmp;
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, (char *)&seq_xx_ddcmp), 1);  /* xx */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x01"), 1);  /* sadd */
    crc_[0] = crc % 256;
    crc_[1] = crc / 256;
    uart_write_bytes(UART_NUM_1, &crc_, 2);

    crc = 0x0000;

    /* READ_DATA / Audit Collection payload */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x77"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\xE2"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x00"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x02"), 1);  /* Read without reset */
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x01"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x00"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x00"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x00"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x00"), 1);
    crc_[0] = crc % 256;
    crc_[1] = crc / 256;
    uart_write_bytes(UART_NUM_1, &crc_, 2);

    if (uart_read_bytes(UART_NUM_1, &buffer_rx, 8, pdMS_TO_TICKS(200)) != 8)
        return;

    if ((buffer_rx[0] != 0x05) || (buffer_rx[1] != 0x01)) {
        return;
    }  /* ACK */

    if (uart_read_bytes(UART_NUM_1, &buffer_rx, 8, pdMS_TO_TICKS(200)) != 8)
        return;

    if (buffer_rx[0] != 0x81) {
        return;
    }  /* DATA HEADER */

    seq_rr_ddcmp = buffer_rx[4];

    n_bytes_message = ((buffer_rx[2] & 0x3f) * 256) + buffer_rx[1];
    n_bytes_message += 2;  /* CRC-16 */

    if (uart_read_bytes(UART_NUM_1, &buffer_rx, n_bytes_message, pdMS_TO_TICKS(200)) != n_bytes_message)
        return;

    if (buffer_rx[2] != 0x01) {
        return;
    }  /* Not rejected */

    /* ---- ACK ---- */
    crc = 0x0000;

    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x05"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x01"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x40"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, (char *)&seq_rr_ddcmp), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x00"), 1);
    uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x01"), 1);
    crc_[0] = crc % 256;
    crc_[1] = crc / 256;
    uart_write_bytes(UART_NUM_1, &crc_, 2);

    /* ---- Data Reception Loop ---- */
    do {
        if (uart_read_bytes(UART_NUM_1, &buffer_rx, 8, pdMS_TO_TICKS(200)) != 8)
            break;

        if (buffer_rx[0] != 0x81) {
            break;
        }  /* DATA HEADER */

        seq_rr_ddcmp = buffer_rx[4];
        last_package = buffer_rx[2] & 0x80;  /* Bit 7 = last package flag */

        n_bytes_message = ((buffer_rx[2] & 0x3f) * 256) + buffer_rx[1];
        n_bytes_message += 2;  /* CRC-16 */

        if (uart_read_bytes(UART_NUM_1, &buffer_rx, n_bytes_message, pdMS_TO_TICKS(200)) != n_bytes_message)
            break;

        /*
         * Extract audit data from the payload.  The format is:
         *   99 nn [audit data...] crc crc
         * So audit data is at positions 2 through (n_bytes_message - 3).
         */
        for (int x = 2; x < n_bytes_message - 2; x++)
            xRingbufferSend(dexRingbuf, &buffer_rx[x], 1, 0);

        /* ACK this data block */
        crc = 0x0000;

        uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x05"), 1);
        uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x01"), 1);
        uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x40"), 1);
        uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, (char *)&seq_rr_ddcmp), 1);
        uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x00"), 1);
        uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x01"), 1);
        crc_[0] = crc % 256;
        crc_[1] = crc / 256;
        uart_write_bytes(UART_NUM_1, &crc_, 2);

        if (last_package) {
            /* Send FINIS to terminate the session */
            crc = 0x0000;

            uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x81"), 1);
            uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x02"), 1);  /* nn */
            uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x40"), 1);  /* mm */
            uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, (char *)&seq_rr_ddcmp), 1);  /* rr */
            uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x03"), 1);  /* xx */
            uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x01"), 1);  /* sadd */
            crc_[0] = crc % 256;
            crc_[1] = crc / 256;
            uart_write_bytes(UART_NUM_1, &crc_, 2);

            uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x77"), 1);
            uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\xFF"), 1);
            uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\x67"), 1);
            uart_write_bytes(UART_NUM_1, calc_crc_16(&crc, "\xB0"), 1);  /* FINIS */

            if (uart_read_bytes(UART_NUM_1, &buffer_rx, 8, pdMS_TO_TICKS(200)) != 8)
                break;

            if ((buffer_rx[0] != 0x05) || (buffer_rx[1] != 0x01)) {
                break;
            }  /* ACK */

            break;
        }

        vTaskDelay(pdMS_TO_TICKS(10));

    } while (1);
}

/* ======================================================================
 * requestTelemetryData - Timer callback: collect and publish telemetry
 * ======================================================================
 *
 * Called every 12 hours by the periodic esp_timer created in main.c.
 * Attempts DDCMP first (some machines support it), then DEX.  After
 * collection, reads all accumulated data from dexRingbuf and publishes
 * it as a single MQTT message to the DEX topic.
 */
void requestTelemetryData(void *arg)
{
    /* Try both protocols - machines typically support one or the other */
    readTelemetryDDCMP();
    readTelemetryDEX();

    /* Read all collected data from ring buffer */
    size_t dex_size;
    uint8_t *dex = (uint8_t *)xRingbufferReceive(dexRingbuf, &dex_size, 0);

    /* Publish to MQTT */
    char topic[64];
    snprintf(topic, sizeof(topic), "domain.panamavendingmachines.com/%s/dex", my_subdomain);

    esp_mqtt_client_publish(mqttClient, topic, (char *)dex, dex_size, 0, 0);
    printf("%.*s", dex_size, (char *)dex);

    /* Return the ring buffer item */
    vRingbufferReturnItem(dexRingbuf, (void *)dex);
}
