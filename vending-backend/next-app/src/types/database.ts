/**
 * TypeScript type definitions for the vending machine database schema.
 *
 * These types mirror the Supabase PostgreSQL tables defined in the migrations.
 * Used throughout the Next.js API routes, dashboard pages, and MQTT bridge.
 */

/** Possible online/offline statuses for an ESP32 embedded device. */
export type EmbeddedStatus = "online" | "offline";

/** The channel through which a sale was recorded. */
export type SaleChannel = "ble" | "mqtt" | "cash" | "yappy";

/** Known metric names stored in the time-series metrics table. */
export type MetricName = "paxcounter";

/**
 * Represents a row in the `embedded` table.
 * Each row corresponds to a single ESP32-S3 device installed inside a vending machine.
 */
export interface EmbeddedDevice {
  id: string;
  subdomain: number;
  passkey: string;
  mac_address: string | null;
  status: EmbeddedStatus;
  status_at: string;
  owner_id: string | null;
  machine_id: string | null;
  created_at: string;
}

/**
 * Represents a row in the `sales` table.
 * Every vend transaction (BLE, MQTT remote, cash, or Yappy) is recorded here.
 */
export interface Sale {
  id: string;
  embedded_id: string;
  machine_id: string | null;
  product_id: string | null;
  item_price: number;
  item_number: number;
  channel: SaleChannel;
  owner_id: string | null;
  lat: number | null;
  lng: number | null;
  external_transaction_id: string | null;
  created_at: string;
}

/** Represents a row in the `machines` table. */
export interface Machine {
  id: string;
  serial_number: string | null;
  name: string | null;
  location: string | null;
  owner_id: string | null;
  refilled_at: string;
  created_at: string;
}

/** Represents a row in the `products` table. */
export interface Product {
  id: string;
  name: string;
  barcode: string | null;
  owner_id: string | null;
  created_at: string;
}

/** Represents a row in the `machine_coils` table (inventory). */
export interface MachineCoil {
  id: string;
  machine_id: string | null;
  product_id: string | null;
  owner_id: string | null;
  alias: string | null;
  item_number: number | null;
  capacity: number;
  current_stock: number;
  price: number;
  created_at: string;
}

/** Represents a row in the `metrics` table. */
export interface Metric {
  id: string;
  embedded_id: string;
  machine_id: string | null;
  name: MetricName;
  value: number;
  payload: Record<string, unknown> | null;
  created_at: string;
}

/** Request body for /api/credit/request */
export interface CreditRequestBody {
  payload: string;
  subdomain: string;
  lat?: number;
  lng?: number;
}

/** Request body for /api/credit/send */
export interface CreditSendBody {
  amount: number;
  subdomain: string;
}

/** Response from /api/credit/request */
export interface CreditRequestResponse {
  payload: string;
  sales_id: string;
}

/** Response from /api/credit/send */
export interface CreditSendResponse {
  status: EmbeddedStatus;
  sales_id: string | null;
}
