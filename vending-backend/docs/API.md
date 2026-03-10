# API Documentation

Base URL: `https://api.panamavendingmachines.com` (or `http://localhost:3000` for development)

All endpoints except auth require a valid JWT in the `Authorization` header.

## Authentication

### POST /api/auth/login

Authenticate with email and password.

**Request:**
```json
{
  "email": "user@example.com",
  "password": "your-password"
}
```

**Response (200):**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIs...",
  "refresh_token": "v1.MjE0NzI1...",
  "user": {
    "id": "uuid",
    "email": "user@example.com"
  }
}
```

**Errors:** `400` missing fields, `401` invalid credentials

---

### POST /api/auth/register

Create a new user account.

**Request:**
```json
{
  "email": "user@example.com",
  "password": "your-password",
  "full_name": "John Doe"
}
```

**Response (201):**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIs...",
  "user": {
    "id": "uuid",
    "email": "user@example.com"
  }
}
```

**Errors:** `400` missing fields or signup error

---

### POST /api/auth/refresh

Refresh an expired access token.

**Request:**
```json
{
  "refresh_token": "v1.MjE0NzI1..."
}
```

**Response (200):**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIs...",
  "refresh_token": "v1.new-refresh-token..."
}
```

**Errors:** `400` invalid or missing refresh token

---

## Devices

All device endpoints require `Authorization: Bearer <token>`.

### GET /api/devices

List all ESP32 devices owned by the authenticated user.

**Response (200):**
```json
[
  {
    "id": "uuid",
    "subdomain": 123456,
    "passkey": "a1b2c3d4e5f6g7h8ij",
    "mac_address": "AA:BB:CC:DD:EE:FF",
    "status": "online",
    "status_at": "2026-03-09T12:00:00Z",
    "created_at": "2026-03-08T10:00:00Z",
    "machine_id": null
  }
]
```

---

### POST /api/devices

Register a new ESP32 device. The subdomain and passkey are auto-generated.

**Request:**
```json
{
  "mac_address": "AA:BB:CC:DD:EE:FF"
}
```

**Response (201):**
```json
{
  "id": "uuid",
  "subdomain": 123457,
  "passkey": "auto-generated-18-chars",
  "mac_address": "AA:BB:CC:DD:EE:FF",
  "status": "offline",
  "created_at": "2026-03-09T12:00:00Z"
}
```

---

### GET /api/devices/[id]

Get a single device by UUID.

**Response (200):** Full device object
**Errors:** `404` device not found

---

### PUT /api/devices/[id]

Update device fields.

**Request:**
```json
{
  "mac_address": "11:22:33:44:55:66",
  "machine_id": "machine-uuid"
}
```

**Response (200):** Updated device object
**Errors:** `400` no valid fields, `404` device not found

---

### DELETE /api/devices/[id]

Delete a device.

**Response (200):**
```json
{ "success": true }
```

**Errors:** `404` device not found

---

## Sales

### GET /api/sales

List sales with pagination and filtering.

**Query Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `limit` | number | 50 | Page size (max 100) |
| `offset` | number | 0 | Pagination offset |
| `order` | string | `created_at.desc` | Sort column.direction |
| `channel` | string | - | Filter: `ble`, `mqtt`, or `cash` |
| `subdomain` | string | - | Filter by device subdomain |

**Response (200):**
```json
{
  "data": [
    {
      "id": "uuid",
      "channel": "ble",
      "item_number": 7,
      "item_price": 2.50,
      "created_at": "2026-03-09T12:00:00Z",
      "embedded_id": "device-uuid",
      "lat": 40.71,
      "lng": -74.00,
      "embedded": {
        "subdomain": 123456,
        "status": "online"
      }
    }
  ],
  "pagination": {
    "total": 150,
    "limit": 50,
    "offset": 0
  }
}
```

---

## Credit (Critical Path)

### POST /api/credit/request

**The most important endpoint.** Processes BLE vend requests from the Android app.

This is called when a user selects a product on the vending machine. The ESP32
sends a VEND_REQUEST BLE notification to the Android app, which forwards the
XOR-encrypted payload to this endpoint for validation and approval.

**Request:**
```json
{
  "payload": "base64-encoded-19-bytes",
  "subdomain": "123456",
  "lat": 40.71,
  "lng": -74.00
}
```

**Response (200):**
```json
{
  "payload": "base64-encoded-19-bytes-with-approve-cmd",
  "sales_id": "sale-uuid"
}
```

The response payload has:
- `cmd` byte changed to `0x03` (APPROVE_VEND)
- Checksum recalculated
- Re-encrypted with the device passkey

The Android app writes this payload back to the ESP32 via BLE to approve the vend.

**Errors:**
- `400` - Invalid payload (wrong size, checksum failure, expired timestamp)
- `401` - Missing/invalid JWT
- `404` - Device not found
- `500` - Database error

---

### POST /api/credit/send

Push credit to a vending machine remotely via MQTT.

**Request:**
```json
{
  "amount": 5.00,
  "subdomain": "123456"
}
```

**Response (200):**
```json
{
  "status": "online",
  "sales_id": "sale-uuid"
}
```

If the device is offline, `sales_id` will be `null` (the credit message is still
published to MQTT and will be delivered when the device reconnects if retained).

**Errors:**
- `400` - Missing fields or invalid amount
- `401` - Missing/invalid JWT
- `404` - Device not found
- `500` - MQTT or database error

---

## Metrics

### GET /api/metrics

Get paxcounter (foot traffic) metrics for a device.

**Query Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `subdomain` | string | Yes | Device subdomain |
| `limit` | number | No | Page size (default 100, max 1000) |
| `offset` | number | No | Pagination offset |
| `from` | ISO string | No | Start date filter |
| `to` | ISO string | No | End date filter |

**Response (200):**
```json
{
  "data": [
    {
      "id": "uuid",
      "name": "paxcounter",
      "value": 42,
      "created_at": "2026-03-09T12:00:00Z",
      "embedded_id": "device-uuid"
    }
  ],
  "pagination": {
    "total": 500,
    "limit": 100,
    "offset": 0
  }
}
```

---

## Machines

### GET /api/machines

List all vending machines owned by the authenticated user.

**Response (200):**
```json
[
  {
    "id": "uuid",
    "name": "Office Lobby",
    "serial_number": "VM-2024-001",
    "refilled_at": "2026-03-09T08:00:00Z",
    "created_at": "2026-03-08T10:00:00Z"
  }
]
```

---

### POST /api/machines

Register a new vending machine.

**Request:**
```json
{
  "name": "Break Room",
  "serial_number": "VM-2024-002"
}
```

**Response (201):** Created machine object

---

## Webhooks

### POST /api/webhooks/mqtt

Internal endpoint called by the MQTT bridge. Authenticated via `X-Webhook-Secret` header.

**Request:**
```json
{
  "event": "sale",
  "subdomain": "123456",
  "data": {
    "embedded_id": "device-uuid",
    "item_number": 3,
    "item_price": 1.50,
    "owner_id": "user-uuid"
  }
}
```

**Events:** `sale`, `status`, `paxcounter`

---

## Error Response Format

All error responses follow this format:

```json
{
  "error": "Human-readable error message"
}
```

Common HTTP status codes:
- `400` - Bad request (missing/invalid fields)
- `401` - Unauthorized (missing/invalid JWT)
- `404` - Resource not found
- `500` - Internal server error
- `503` - Service unavailable
