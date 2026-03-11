# MDB Protocol Implementation

## Overview

This firmware implements an MDB (Multi-Drop Bus) Level 1 Cashless Device peripheral. The ESP32-S3 acts as a slave on the MDB bus, responding to commands from the VMC (Vending Machine Controller).

## Physical Layer

The MDB bus uses 9-bit serial communication at 9600 baud. Because the ESP32 UART peripheral does not natively support 9-bit frames, the firmware uses GPIO bit-banging:

- **RX Pin**: GPIO4 (PIN_MDB_RX) - Input, reads data from VMC
- **TX Pin**: GPIO5 (PIN_MDB_TX) - Output, sends responses to VMC
- **Baud Rate**: 9600 bps
- **Bit Timing**: 104 microseconds per bit (1/9600)
- **Frame Format**: 1 start bit + 9 data bits + 1 stop bit = 11 bits

### The 9th Bit (Mode Bit)

The 9th bit distinguishes between address/command bytes and data bytes:
- **Mode = 1**: Address byte (first byte of a new command from VMC)
- **Mode = 0**: Data byte (subsequent bytes in the message)

### Bit-Banging Implementation

**read_9()**: Waits for start bit (falling edge on RX), then samples 9 bits at 104 us intervals. First sample is taken 52 us into bit 0 (centre of the bit cell) for reliable sampling.

**write_9()**: Drives TX low for start bit, then outputs 9 bits LSB-first at 104 us intervals, followed by a high stop bit.

**write_payload_9()**: Sends a multi-byte response. Each byte is sent as a 9-bit word with mode=0. The final checksum byte is sent with mode=1 (BIT_MODE_SET).

## Addressing

The cashless device address is configured at build time:
- Cashless Device #1: address 0x10 (16 decimal)
- Cashless Device #2: address 0x60 (96 decimal)

When a mode-bit-set byte arrives, the firmware checks if the upper 5 bits match our address (BIT_ADD_SET mask). The lower 3 bits contain the command (BIT_CMD_SET mask).

## Commands

### RESET (0x00)
- Direction: VMC -> Peripheral
- Payload: None (just address byte + checksum)
- Action: Sets machine_state to INACTIVE_STATE
- Response: ACK (on next POLL: "Just Reset" 0x00)

### SETUP (0x01)
Sub-commands:

**CONFIG_DATA (0x00)**:
- VMC sends: Feature Level, Columns, Rows, Display Info
- Response: Reader Config (feature level, currency code, scale factor, decimal places, max response time, misc options)
- Transitions to: DISABLED_STATE

**MAX_MIN_PRICES (0x01)**:
- VMC sends: Max Price (2 bytes), Min Price (2 bytes)
- Response: ACK only

### POLL (0x02)
Most frequent command. The peripheral reports pending events:

| Response | Code | When |
|----------|------|------|
| Just Reset | 0x00 | After RESET, until acknowledged |
| Begin Session | 0x03 + funds(2B) | Credit received (BLE/MQTT) |
| Session Cancel | 0x04 | User cancelled or timeout |
| Vend Approved | 0x05 + price(2B) | Payment confirmed |
| Vend Denied | 0x06 | Insufficient funds or cancel |
| End Session | 0x07 | SESSION_COMPLETE from VMC |
| Cancelled | 0x08 | READER_CANCEL response |
| Out of Sequence | 0x0B | Invalid command for state |

If no events are pending, the peripheral returns ACK only.

### VEND (0x03)
Sub-commands:

**VEND_REQUEST (0x00)**: User selected a product
- VMC sends: Price (2B big-endian) + Item Number (2B big-endian)
- Action: Transition to VEND_STATE, send BLE notification 0x0A
- If funds available and sufficient: auto-approve
- If funds = 0xFFFF (unlimited): wait for BLE approval

**VEND_CANCEL (0x01)**: VMC cancels the vend
- Action: Sets vend_denied_todo

**VEND_SUCCESS (0x02)**: Product dispensed
- VMC sends: Item Number (2B)
- Action: Back to IDLE_STATE, BLE notification 0x0B

**VEND_FAILURE (0x03)**: Dispensing failed
- Action: Back to IDLE_STATE, BLE notification 0x0C

**SESSION_COMPLETE (0x04)**: Session ending
- Action: Sets session_end_todo, BLE notification 0x0D

**CASH_SALE (0x05)**: Cash transaction occurred
- VMC sends: Price (2B) + Item Number (2B)
- Action: Publish to MQTT sale topic (cmd 0x21)

### READER (0x04)
Sub-commands:

**READER_DISABLE (0x00)**: Back to DISABLED_STATE
**READER_ENABLE (0x01)**: Forward to ENABLED_STATE
**READER_CANCEL (0x02)**: Cancel pending operation, respond with 0x08

### EXPANSION (0x07)
Sub-commands:

**REQUEST_ID (0x00)**: VMC requests peripheral identification
- VMC sends: 29-byte VMC ID (read and discarded)
- Response: 30-byte Peripheral ID
  - Byte 0: 0x09 (Peripheral ID response code)
  - Bytes 1-3: Manufacturer Code ("VMF")
  - Bytes 4-15: Serial Number (spaces)
  - Bytes 16-27: Model Number (spaces)
  - Bytes 28-29: Software Version ("03")

## State Machine

```
RESET --> INACTIVE_STATE
              |
         SETUP/CONFIG_DATA
              |
              v
       DISABLED_STATE  <---- READER_DISABLE
              |
         READER_ENABLE
              |
              v
        ENABLED_STATE  <---- SESSION_COMPLETE / Timeout (60s)
              |
         Begin Session (credit via BLE or MQTT)
              |
              v
         IDLE_STATE
              |
         VEND_REQUEST
              |
              v
         VEND_STATE  -----> VEND_SUCCESS/FAILURE --> IDLE_STATE
```

## Session Timeout

If the machine remains in IDLE_STATE or VEND_STATE for more than 60 seconds without activity, the firmware automatically sets session_cancel_todo to cancel the session on the next POLL.

## Checksum Validation

Every MDB message ends with a checksum byte. The checksum is the sum of all preceding bytes (lower 8 bits). The firmware validates incoming checksums and discards messages with mismatches (via `continue` in the main loop).
