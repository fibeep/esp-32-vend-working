/**
 * TypeScript type definitions for the vending machine database schema.
 *
 * These types mirror the Supabase PostgreSQL tables defined in the migration
 * file (20260308191643_remote_schema.sql). They are used throughout the
 * Next.js API routes and the MQTT bridge to ensure type safety when
 * reading from or writing to the database.
 */

/** Possible online/offline statuses for an ESP32 embedded device. */
export type EmbeddedStatus = "online" | "offline";

/** The channel through which a sale was recorded. */
export type SaleChannel = "ble" | "mqtt" | "cash";

/** Known metric names stored in the time-series metrics table. */
export type MetricName = "paxcounter";

/**
 * Represents a row in the `embedded` table.
 * Each row corresponds to a single ESP32-S3 device installed inside a vending machine.
 */
export interface EmbeddedDevice {
  /** Primary key (UUID). */
  id: string;
  /** Auto-generated numeric subdomain used for MQTT topic routing and BLE device naming. */
  subdomain: number;
  /** 18-character ASCII passkey used for XOR payload encryption/decryption. */
  passkey: string;
  /** BLE MAC address of the ESP32 (e.g. "AA:BB:CC:DD:EE:FF"). */
  mac_address: string | null;
  /** Current connectivity status, updated via MQTT LWT messages. */
  status: EmbeddedStatus;
  /** Timestamp of the last status change. */
  status_at: string;
  /** Foreign key to auth.users -- the owner of this device. */
  owner_id: string | null;
  /** Foreign key to the machines table (nullable). */
  machine_id: string | null;
  /** Row creation timestamp. */
  created_at: string;
}

/**
 * Represents a row in the `sales` table.
 * Every vend transaction (BLE, MQTT remote, or cash) is recorded here.
 */
export interface Sale {
  /** Primary key (UUID). */
  id: string;
  /** Foreign key to the embedded device that processed the sale. */
  embedded_id: string;
  /** Foreign key to the vending machine (nullable). */
  machine_id: string | null;
  /** Foreign key to the product catalog (nullable). */
  product_id: string | null;
  /** Display price after applying the scale factor (rawPrice / 100). */
  item_price: number;
  /** Vend slot/item number on the vending machine. */
  item_number: number;
  /** How the sale was initiated: BLE app, MQTT remote, or physical cash. */
  channel: SaleChannel;
  /** Foreign key to auth.users -- the owner who recorded this sale. */
  owner_id: string | null;
  /** GPS latitude from the Android app (BLE transactions only). */
  lat: number | null;
  /** GPS longitude from the Android app (BLE transactions only). */
  lng: number | null;
  /** Row creation timestamp. */
  created_at: string;
}

/**
 * Represents a row in the `metrics` table (partitioned by year).
 * Currently used exclusively for paxcounter (foot traffic) data from BLE scans.
 */
export interface Metric {
  /** Primary key (UUID). */
  id: string;
  /** Foreign key to the embedded device that reported the metric. */
  embedded_id: string;
  /** Foreign key to the machine (nullable). */
  machine_id: string | null;
  /** The type of metric being recorded. */
  name: MetricName;
  /** The numeric metric value (e.g. number of BLE devices detected). */
  value: number;
  /** Optional JSON payload for extended data. */
  payload: Record<string, unknown> | null;
  /** Row creation timestamp. */
  created_at: string;
}

/**
 * Represents a row in the `machines` table.
 * A physical vending machine that may have an embedded device attached.
 */
export interface Machine {
  /** Primary key (UUID). */
  id: string;
  /** Machine serial number. */
  serial_number: string | null;
  /** Human-readable name for the machine. */
  name: string | null;
  /** Foreign key to auth.users -- the owner. */
  owner_id: string | null;
  /** Last time the machine was refilled with product. */
  refilled_at: string;
  /** Row creation timestamp. */
  created_at: string;
}

/**
 * Shape of the request body for the `/api/credit/request` endpoint.
 * Sent by the Android app after receiving a VEND_REQUEST BLE notification.
 */
export interface CreditRequestBody {
  /** Base64-encoded 19-byte XOR-encrypted payload from the ESP32. */
  payload: string;
  /** Device subdomain identifier (e.g. "123456"). */
  subdomain: string;
  /** Optional GPS latitude from the Android device. */
  lat?: number;
  /** Optional GPS longitude from the Android device. */
  lng?: number;
}

/**
 * Shape of the request body for the `/api/credit/send` endpoint.
 * Sent when a user wants to remotely push credit to a vending machine via MQTT.
 */
export interface CreditSendBody {
  /** Dollar amount to credit (e.g. 1.50 for $1.50). */
  amount: number;
  /** Device subdomain identifier (e.g. "123456"). */
  subdomain: string;
}

/**
 * Shape of the response from the `/api/credit/request` endpoint.
 */
export interface CreditRequestResponse {
  /** Base64-encoded 19-byte XOR-encrypted approval payload to send back to ESP32 via BLE. */
  payload: string;
  /** UUID of the newly created sale record. */
  sales_id: string;
}

/**
 * Shape of the response from the `/api/credit/send` endpoint.
 */
export interface CreditSendResponse {
  /** Current device status at the time of the request. */
  status: EmbeddedStatus;
  /** UUID of the created sale record, or null if device was offline. */
  sales_id: string | null;
}
