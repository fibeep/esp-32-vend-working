# Architecture

## System Overview

The VMFlow vending backend is a server-side system that manages cashless vending machine transactions. It sits between Android mobile apps and ESP32 embedded devices, processing BLE and MQTT communications.

```
                              Hostinger VPS
                    ┌──────────────────────────────┐
                    │  ┌────────┐                  │
 Android App ──────────► Caddy  │ :80/:443         │
   (REST)           │  │(proxy) ├──► Next.js :3000 │──── Supabase Cloud
                    │  └────────┘   (API server)   │     (PostgreSQL +
                    │                    │          │      GoTrue Auth)
                    │               ┌────▼──────┐  │
 ESP32 Devices ────────► Mosquitto  │MQTT Bridge│  │
   (MQTT)           │  │  :1883    │(subscriber)├──┘
                    │  └───────────└────────────┘
                    └──────────────────────────────┘
```

## Components

### 1. Next.js 15 API Server

**Role:** HTTP API gateway for the Android app and web clients.

**Key decisions:**
- **Next.js App Router** was chosen because it provides a clean file-based routing system for API endpoints, built-in TypeScript support, and the standalone output mode reduces Docker image size.
- **No frontend** beyond a landing page. This is purely an API server. A separate frontend project can be added later.
- **Route handlers** (`route.ts`) are used instead of pages for all API logic.

**Authentication flow:**
1. Android app calls `/api/auth/login` to get a JWT
2. All subsequent requests include `Authorization: Bearer <token>`
3. The middleware helper (`verifyAuth`) validates the JWT via Supabase's `auth.getUser()`
4. Route handlers receive the authenticated user object

### 2. Mosquitto MQTT Broker

**Role:** Message broker for ESP32 device communication.

**Key decisions:**
- **Eclipse Mosquitto 2** is a lightweight, battle-tested MQTT broker
- Runs as a Docker container alongside the application
- Anonymous connections are allowed because device authentication happens at the payload level (XOR encryption with device-specific passkeys)
- Exposed on port 1883 for direct device connections

### 3. MQTT Bridge

**Role:** Long-running Node.js process that subscribes to all device topics and processes incoming messages.

**Key decisions:**
- **Separate service** rather than integrating into Next.js because MQTT subscriptions require a persistent connection, which does not fit the request/response model of HTTP routes
- Uses the **service-role Supabase key** to bypass Row Level Security (RLS) because it inserts records on behalf of any device owner
- Written in TypeScript with the same XOR crypto code as the Next.js app for consistency

**Topic routing:**
```
domain.panamavendingmachines.com/{subdomain}/sale       -> handleSale()
domain.panamavendingmachines.com/{subdomain}/status     -> handleStatus()
domain.panamavendingmachines.com/{subdomain}/paxcounter -> handlePaxcounter()
domain.panamavendingmachines.com/{subdomain}/dex        -> (logged, not processed)
```

### 4. Caddy Reverse Proxy

**Role:** TLS termination and reverse proxying.

**Key decisions:**
- **Automatic HTTPS** via Let's Encrypt, zero-config certificate management
- Simpler than nginx for this use case
- Adds security headers automatically

### 5. Supabase Cloud

**Role:** PostgreSQL database and authentication service.

**Key decisions:**
- **Cloud-hosted** rather than self-hosted to reduce operational burden
- **Row Level Security (RLS)** ensures users can only see their own devices and sales
- **GoTrue** provides JWT-based authentication compatible with the Android app
- The service-role key is only used by the MQTT bridge (server-to-server)

## Data Flow: BLE Credit Request (Critical Path)

This is the most important flow in the system. It processes a physical vend transaction:

```
1. User selects product on vending machine
2. VMC sends VEND_REQUEST to ESP32 via MDB
3. ESP32 creates XOR-encrypted payload (price + item + timestamp)
4. ESP32 sends payload to Android app via BLE notification (cmd 0x0A)
5. Android app POST /api/credit/request with base64 payload
6. Next.js decrypts payload, validates checksum + timestamp
7. Next.js inserts sale record in Supabase
8. Next.js changes cmd to 0x03 (APPROVE), re-encrypts
9. Next.js returns base64 payload to Android app
10. Android app writes payload to ESP32 BLE characteristic
11. ESP32 sends VEND_APPROVED to VMC
12. VMC dispenses product
```

## Data Flow: MQTT Credit Push

Remote credit delivery for users who are not physically at the machine:

```
1. User sends POST /api/credit/send with amount + subdomain
2. Next.js builds XOR-encrypted payload (cmd 0x20)
3. Next.js publishes to Mosquitto on topic {subdomain}.panamavendingmachines.com/credit
4. ESP32 receives the message, decrypts, starts a vend session
5. If device is online, a sale record is created
```

## Data Flow: Cash Sale (MQTT)

Physical cash transactions reported by the ESP32:

```
1. User inserts coins/bills into vending machine
2. VMC processes cash sale and sends CASH_SALE to ESP32 via MDB
3. ESP32 creates XOR-encrypted payload (cmd 0x05)
4. ESP32 publishes to domain.panamavendingmachines.com/{subdomain}/sale
5. MQTT Bridge receives the message
6. Bridge decrypts, validates, inserts sale record with channel "cash"
```

## XOR Encryption

The XOR encryption scheme provides lightweight payload integrity and replay protection. It is NOT cryptographically secure in the traditional sense, but it serves its purpose:

1. **Integrity**: The checksum catches transmission errors
2. **Replay protection**: The 8-second timestamp window prevents replay attacks
3. **Per-device keys**: Each ESP32 has a unique 18-byte passkey

**Trade-off**: XOR encryption is chosen because the ESP32 has limited computational resources and the payloads are small (19 bytes). The passkey must be kept secret (stored in NVS on the ESP32 and in the database).

## Security Model

| Layer | Mechanism |
|-------|-----------|
| Transport | TLS via Caddy (HTTPS) |
| API Auth | JWT tokens via Supabase GoTrue |
| Data isolation | Supabase Row Level Security (RLS) |
| Payload integrity | XOR checksum + timestamp validation |
| MQTT webhook | Shared secret (`X-Webhook-Secret`) |
| MQTT broker | Device-specific passkey in payloads |

## Database Design

The schema uses Supabase's built-in features:

- **`gen_random_uuid()`** for primary keys
- **`auth.uid()`** for automatic owner assignment via RLS defaults
- **Identity columns** for auto-incrementing subdomains
- **Partitioned tables** for time-series metrics (partitioned by year)
- **Enums** for constrained values (`embedded_status`, `sale_channel`, `metric_name`)

## Scaling Considerations

The current architecture is designed for a single VPS deployment supporting hundreds of devices. For larger scale:

1. **Database**: Supabase Cloud handles scaling automatically
2. **MQTT**: Mosquitto can handle thousands of concurrent connections
3. **API**: Next.js can be horizontally scaled behind a load balancer
4. **MQTT Bridge**: Can be scaled by partitioning topic subscriptions

## Technology Choices

| Component | Technology | Rationale |
|-----------|-----------|-----------|
| API Framework | Next.js 15 | File-based routing, TypeScript, standalone Docker output |
| Runtime | Node.js 20 | LTS, good MQTT library support |
| Database | PostgreSQL (Supabase) | RLS, real-time subscriptions, managed hosting |
| Auth | Supabase GoTrue | JWT-based, mobile SDK compatible |
| MQTT Broker | Eclipse Mosquitto 2 | Lightweight, reliable, Docker-native |
| Reverse Proxy | Caddy 2 | Auto-HTTPS, simple config |
| Testing | Vitest | Fast, ESM-native, good TypeScript support |
