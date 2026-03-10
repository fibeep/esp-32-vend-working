package xyz.vmflow.vending.bluetooth

import java.util.UUID

/**
 * Constants for BLE communication with the ESP32-S3 vending machine controller.
 *
 * These UUIDs and command bytes define the GATT service contract between the
 * Android app and the ESP32 firmware. The characteristic supports READ, WRITE,
 * and NOTIFY properties, enabling bidirectional communication.
 *
 * **UUID Note**: The UUIDs here are in standard (big-endian) format as expected
 * by the Android BLE stack. The ESP32 firmware stores them in reversed byte order
 * internally, but both sides resolve to the same logical service/characteristic.
 */
object BleConstants {

    // ── GATT Service & Characteristic ──────────────────────────────────────────

    /**
     * The primary BLE GATT service UUID exposed by the ESP32.
     * All vending communication occurs through this service.
     */
    val SERVICE_UUID: UUID = UUID.fromString("020012ac-4202-78b8-ed11-da4642c6bbb2")

    /**
     * The single characteristic UUID used for reads, writes, and notifications.
     * This characteristic carries all command and event payloads.
     */
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("020012ac-4202-78b8-ed11-de46769cafc9")

    // ── Write Commands (Android -> ESP32) ──────────────────────────────────────

    /** Set the device subdomain identifier. Payload: [0x00, subdomain_bytes..., 0x00] (22 bytes) */
    const val CMD_SET_SUBDOMAIN: Byte = 0x00

    /** Set the 18-byte XOR encryption passkey. Payload: [0x01, passkey_bytes..., 0x00] (22 bytes) */
    const val CMD_SET_PASSKEY: Byte = 0x01

    /** Begin a vending session with unlimited funds. Payload: [0x02] (1 byte) */
    const val CMD_START_SESSION: Byte = 0x02

    /** Approve a pending vend request. Payload: 19-byte XOR-encrypted approval */
    const val CMD_APPROVE_VEND: Byte = 0x03

    /** Close/cancel the active vending session. Payload: [0x04] (1 byte) */
    const val CMD_CLOSE_SESSION: Byte = 0x04

    /** Set WiFi SSID for the ESP32. Payload: [0x06, ssid_bytes..., 0x00] (up to 22 bytes) */
    const val CMD_SET_WIFI_SSID: Byte = 0x06

    /** Set WiFi password for the ESP32. Payload: [0x07, password_bytes..., 0x00] (up to 63 bytes) */
    const val CMD_SET_WIFI_PASS: Byte = 0x07

    // ── Notification Events (ESP32 -> Android) ─────────────────────────────────

    /** Vend request from the machine. Contains XOR-encrypted price + item number. */
    const val EVT_VEND_REQUEST: Byte = 0x0A

    /** Vend succeeded. The product was dispensed successfully. */
    const val EVT_VEND_SUCCESS: Byte = 0x0B

    /** Vend failed. The product could not be dispensed. */
    const val EVT_VEND_FAILURE: Byte = 0x0C

    /** Session complete. The vending session has ended on the machine side. */
    const val EVT_SESSION_COMPLETE: Byte = 0x0D

    /** PAX counter event. Contains foot traffic count from BLE scanning. */
    const val EVT_PAX_COUNTER: Byte = 0x22

    // ── Device Discovery ───────────────────────────────────────────────────────

    /** Suffix for all configured VMflow device BLE names (e.g., "123456.panamavendingmachines.com") */
    const val DEVICE_SUFFIX = ".panamavendingmachines.com"

    /** BLE name of an unconfigured (factory-new) device, excluded from vending scans */
    const val UNCONFIGURED_NAME = "0.panamavendingmachines.com"

    // ── Payload Constants ──────────────────────────────────────────────────────

    /** Total size of an encrypted BLE payload (command + data + checksum) */
    const val PAYLOAD_SIZE = 19

    /** Length of the XOR encryption passkey in characters */
    const val PASSKEY_LENGTH = 18

    /** Maximum size of a provisioning command payload (subdomain or passkey) */
    const val PROVISION_PAYLOAD_SIZE = 22

    /** Maximum size of a WiFi password payload */
    const val WIFI_PASS_PAYLOAD_SIZE = 63
}
