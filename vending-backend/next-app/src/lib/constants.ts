/**
 * Application-wide constants for the vending backend.
 *
 * Centralised here so that magic numbers and repeated strings are defined
 * in one place and can be referenced from both the Next.js API routes
 * and test suites.
 */

/** Total byte length of an XOR-encrypted payload (command + 17 data bytes + checksum). */
export const PAYLOAD_LENGTH = 19;

/** Number of bytes in the XOR passkey (also the number of encrypted data bytes). */
export const PASSKEY_LENGTH = 18;

/** Maximum allowed difference (in seconds) between the payload timestamp and the server clock. */
export const TIMESTAMP_WINDOW_SECONDS = 8;

/** Protocol version byte value (always 0x01 in v1 payloads). */
export const PROTOCOL_VERSION = 0x01;

/**
 * BLE command byte values.
 * These match the constants defined in the ESP32 firmware and the PROTOCOLS.md document.
 */
export const CMD = {
  /** Sent by the Android app to configure the ESP32 subdomain. */
  SET_SUBDOMAIN: 0x00,
  /** Sent by the Android app to configure the XOR passkey. */
  SET_PASSKEY: 0x01,
  /** Sent by the Android app to start a vending session. */
  START_SESSION: 0x02,
  /** Sent by the backend to approve a pending vend (BLE flow). */
  APPROVE_VEND: 0x03,
  /** Sent by the Android app to close/cancel a session. */
  CLOSE_SESSION: 0x04,
  /** Cash sale event reported by the ESP32 over MQTT. */
  CASH_SALE: 0x05,
  /** Configure WiFi SSID. */
  SET_WIFI_SSID: 0x06,
  /** Configure WiFi password. */
  SET_WIFI_PASS: 0x07,
  /** BLE notification: VMC requested a vend (price + item number). */
  VEND_REQUEST: 0x0a,
  /** BLE notification: vend succeeded. */
  VEND_SUCCESS: 0x0b,
  /** BLE notification: vend failed. */
  VEND_FAILURE: 0x0c,
  /** BLE notification: session ended. */
  SESSION_COMPLETE: 0x0d,
  /** MQTT credit push command (backend -> ESP32). */
  CREDIT_PUSH: 0x20,
  /** BLE/MQTT paxcounter (foot traffic count). */
  PAX_COUNTER: 0x22,
} as const;

/**
 * Default scale factor parameters.
 * Used to convert between raw MDB price units and display currency values.
 *
 * Formula: displayPrice = rawPrice * scaleFactor * 10^(-decimalPlaces)
 *          rawPrice     = displayPrice / scaleFactor / 10^(-decimalPlaces)
 */
export const SCALE_FACTOR = {
  /** Multiplier (default 1 for US vending). */
  factor: 1,
  /** Number of decimal places (default 2 for cents). */
  decimals: 2,
} as const;

/**
 * MQTT topic patterns used by the system.
 * The `{subdomain}` placeholder is replaced with the actual device subdomain at runtime.
 */
export const MQTT_TOPICS = {
  /** Pattern for incoming device status messages. */
  STATUS: "domain.panamavendingmachines.com/{subdomain}/status",
  /** Pattern for incoming cash sale messages. */
  SALE: "domain.panamavendingmachines.com/{subdomain}/sale",
  /** Pattern for incoming paxcounter messages. */
  PAXCOUNTER: "domain.panamavendingmachines.com/{subdomain}/paxcounter",
  /** Pattern for outgoing credit messages to a specific device. */
  CREDIT: "{subdomain}.panamavendingmachines.com/credit",
  /** Wildcard subscription used by the MQTT bridge to capture all device messages. */
  BRIDGE_SUBSCRIBE: "domain.panamavendingmachines.com/+/#",
} as const;
