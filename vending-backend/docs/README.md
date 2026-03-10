# VMFlow Vending Backend

Backend API server for the VMFlow cashless vending machine system. This application provides REST API endpoints for the Android mobile app, processes MQTT messages from ESP32 devices, and manages all vending transactions.

## Prerequisites

- **Node.js 20+** (for local development)
- **Docker** and **Docker Compose** (for deployment)
- **Supabase Cloud account** (for PostgreSQL database and authentication)

## Quick Start (Local Development)

### 1. Clone and Configure

```bash
cd vending-backend
cp .env.example .env
```

Edit `.env` with your Supabase credentials:

```env
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_ANON_KEY=eyJ...
SUPABASE_SERVICE_ROLE_KEY=eyJ...
MQTT_BROKER_URL=mqtt://localhost:1883
MQTT_WEBHOOK_SECRET=your-random-secret
```

### 2. Install Dependencies

```bash
cd next-app && npm install
cd ../mqtt-bridge && npm install
```

### 3. Run the Next.js API Server

```bash
cd next-app
npm run dev
```

The API will be available at `http://localhost:3000`.

### 4. Run the MQTT Bridge (optional, requires Mosquitto)

```bash
cd mqtt-bridge
npm run dev
```

### 5. Run Tests

```bash
# Next.js app tests
cd next-app && npm test

# MQTT bridge tests
cd mqtt-bridge && npm test
```

## Production Deployment (Hostinger VPS)

### 1. Provision the VPS

- Ubuntu 22.04+ recommended
- Install Docker and Docker Compose
- Point your domain DNS to the VPS IP address

### 2. Clone the Repository

```bash
git clone <repo-url>
cd vending-backend
```

### 3. Configure Environment

```bash
cp .env.example .env
nano .env
```

Set all required variables:

| Variable | Description |
|----------|-------------|
| `SUPABASE_URL` | Your Supabase project URL |
| `SUPABASE_ANON_KEY` | Supabase anonymous (public) key |
| `SUPABASE_SERVICE_ROLE_KEY` | Supabase service role key (secret) |
| `MQTT_HOST` | MQTT broker hostname (use `mosquitto` for Docker) |
| `MQTT_PORT` | MQTT broker port (default `1883`) |
| `MQTT_BROKER_URL` | Full MQTT URL for Next.js (use `mqtt://mosquitto:1883` for Docker) |
| `MQTT_WEBHOOK_SECRET` | Shared secret for MQTT bridge webhook auth |
| `DOMAIN` | Your domain for Caddy auto-HTTPS (e.g. `api.panamavendingmachines.com`) |

### 4. Update the Caddyfile

Edit `Caddyfile` and replace `{$DOMAIN:localhost}` with your actual domain if not using the environment variable:

```
api.panamavendingmachines.com {
    reverse_proxy next-app:3000
    encode gzip
}
```

### 5. Build and Start

```bash
docker compose up -d --build
```

### 6. Verify

```bash
# Check all services are running
docker compose ps

# Check logs
docker compose logs -f

# Test the API
curl https://api.panamavendingmachines.com/
```

### 7. SSL Certificates

Caddy automatically obtains and renews Let's Encrypt certificates. Ensure:
- Port 80 and 443 are open in your firewall
- Your domain DNS A record points to the VPS IP

## Database Setup

The application uses Supabase Cloud for PostgreSQL. The schema is managed via Supabase migrations. Key tables:

- `embedded` - ESP32 device registry
- `sales` - Transaction records
- `metrics` - Time-series data (paxcounter)
- `machines` - Physical vending machine registry

See the migration file at `docker/supabase/migrations/20260308191643_remote_schema.sql` for the full schema.

## Project Structure

```
vending-backend/
├── docker-compose.yml          # Docker Compose orchestration
├── Caddyfile                   # Caddy reverse proxy config
├── .env.example                # Environment variable template
├── next-app/                   # Next.js 15 API server
│   ├── src/app/api/            # API route handlers
│   ├── src/lib/                # Shared libraries (crypto, MQTT, Supabase)
│   ├── src/types/              # TypeScript type definitions
│   └── src/__tests__/          # Test suites
├── mqtt-bridge/                # MQTT subscriber service
│   ├── src/handlers/           # Message handlers (sale, status, pax)
│   ├── src/crypto/             # XOR decryption module
│   └── src/__tests__/          # Test suites
├── mosquitto/                  # Mosquitto broker config
└── docs/                       # Documentation
```

## Updating

```bash
git pull
docker compose up -d --build
```

## Troubleshooting

### MQTT Bridge not connecting
- Verify Mosquitto is running: `docker compose logs mosquitto`
- Check the bridge logs: `docker compose logs mqtt-bridge`
- Ensure `MQTT_HOST` is set to `mosquitto` (Docker service name)

### Supabase connection errors
- Verify `SUPABASE_URL` and keys are correct
- Check that the Supabase project is not paused
- Verify RLS policies allow the operations

### Caddy certificate errors
- Ensure ports 80 and 443 are open
- Verify DNS A record points to the server
- Check Caddy logs: `docker compose logs caddy`
