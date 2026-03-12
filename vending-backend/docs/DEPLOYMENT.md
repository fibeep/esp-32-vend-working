# Deployment Guide — Hostinger VPS

Deploy the VMFlow web dashboard and MQTT services on your Hostinger VPS (where Mosquitto is already running).

## Architecture

```
Internet
   │
   ├── HTTPS (443) ──► Nginx reverse proxy ──► Next.js (port 3000)
   │
   └── MQTT (1883) ──► Mosquitto broker ◄──► MQTT Bridge
                                           ◄──► ESP32 devices
```

All services run via Docker Compose on the same VPS.

---

## Prerequisites

- Hostinger VPS with Ubuntu 22.04+
- SSH access to the VPS
- Docker and Docker Compose installed
- A domain pointed to the VPS IP (e.g. `dashboard.panamavendingmachines.com`)
- Supabase project credentials

### Install Docker (if not already installed)

```bash
# Update packages
sudo apt update && sudo apt upgrade -y

# Install Docker
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER

# Install Docker Compose plugin
sudo apt install docker-compose-plugin -y

# Verify
docker --version
docker compose version
```

Log out and back in for the group change to take effect.

---

## Step 1: Clone the Repository

```bash
ssh user@your-vps-ip

# Clone the repo (or pull if already cloned)
git clone <your-repo-url> ~/vmflow
cd ~/vmflow/vending-backend
```

---

## Step 2: Configure Environment Variables

```bash
cp .env.example .env
nano .env
```

Fill in the values:

```env
# --- Supabase ---
SUPABASE_URL=https://luntgcliwnyvrmrqpdts.supabase.co
SUPABASE_ANON_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
SUPABASE_SERVICE_ROLE_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...

# Also set the NEXT_PUBLIC_ versions (needed at build time)
NEXT_PUBLIC_SUPABASE_URL=https://luntgcliwnyvrmrqpdts.supabase.co
NEXT_PUBLIC_SUPABASE_ANON_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...

# --- MQTT (Docker internal networking) ---
MQTT_HOST=mosquitto
MQTT_PORT=1883
MQTT_BROKER_URL=mqtt://mosquitto:1883

# --- Webhook secret (generate a random string) ---
MQTT_WEBHOOK_SECRET=<run: openssl rand -hex 32>

# --- Internal service URL ---
NEXT_APP_URL=http://next-app:3000

# --- Node ---
NODE_ENV=production
```

Generate the webhook secret:

```bash
echo "MQTT_WEBHOOK_SECRET=$(openssl rand -hex 32)" >> .env
```

---

## Step 3: Build and Start Services

```bash
cd ~/vmflow/vending-backend
docker compose up -d --build
```

This starts three containers:

| Service | Port | Description |
|---------|------|-------------|
| `next-app` | 3000 | Next.js dashboard + API |
| `mosquitto` | 1883 | MQTT broker |
| `mqtt-bridge` | — | Processes device MQTT messages |

Verify everything is running:

```bash
docker compose ps
```

Expected output:

```
NAME              STATUS
next-app          Up
mosquitto         Up
mqtt-bridge       Up
```

Check logs for errors:

```bash
# All services
docker compose logs -f

# Individual service
docker compose logs -f next-app
docker compose logs -f mosquitto
docker compose logs -f mqtt-bridge
```

Test the app locally on the VPS:

```bash
curl http://localhost:3000
```

---

## Step 4: Set Up Nginx Reverse Proxy with SSL

Hostinger VPS typically comes with hPanel. You can set up Nginx manually or through hPanel.

### Option A: hPanel (if available)

1. Go to **hPanel > VPS > Manage**
2. Point your domain DNS A record to the VPS IP
3. Set up a website/virtual host for your domain
4. Enable SSL (Let's Encrypt) through hPanel

### Option B: Manual Nginx Setup

Install Nginx and Certbot:

```bash
sudo apt install nginx certbot python3-certbot-nginx -y
```

Create the Nginx config:

```bash
sudo nano /etc/nginx/sites-available/vmflow-dashboard
```

Paste this config (replace `dashboard.panamavendingmachines.com` with your domain):

```nginx
server {
    listen 80;
    server_name dashboard.panamavendingmachines.com;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_cache_bypass $http_upgrade;
    }
}
```

Enable the site and get SSL:

```bash
# Enable site
sudo ln -s /etc/nginx/sites-available/vmflow-dashboard /etc/nginx/sites-enabled/

# Test config
sudo nginx -t

# Reload
sudo systemctl reload nginx

# Get SSL certificate
sudo certbot --nginx -d dashboard.panamavendingmachines.com
```

Certbot auto-renews. Verify with:

```bash
sudo certbot renew --dry-run
```

---

## Step 5: Configure Firewall

```bash
# Allow SSH, HTTP, HTTPS, and MQTT
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 1883/tcp
sudo ufw enable
sudo ufw status
```

> **Note:** Port 1883 must be open for ESP32 devices to connect to the MQTT broker. If you want to restrict MQTT access, you can use Mosquitto's `password_file` and `acl_file` options.

---

## Step 6: Configure DNS

In your domain registrar (or Hostinger's DNS management):

| Type | Name | Value | TTL |
|------|------|-------|-----|
| A | `dashboard` | `<your-vps-ip>` | 3600 |
| A | `mqtt` | `<your-vps-ip>` | 3600 |

The ESP32 devices connect to `mqtt.panamavendingmachines.com:1883` by default.

---

## Step 7: Verify the Deployment

```bash
# Check the dashboard loads
curl -I https://dashboard.panamavendingmachines.com

# Check the API responds
curl https://dashboard.panamavendingmachines.com/api/auth/login

# Check MQTT broker is reachable (from another machine)
# Install mosquitto-clients if needed: apt install mosquitto-clients
mosquitto_sub -h mqtt.panamavendingmachines.com -t "test/#" -v &
mosquitto_pub -h mqtt.panamavendingmachines.com -t "test/hello" -m "works"

# Check Docker container health
docker compose ps
docker compose logs --tail=50 next-app
```

---

## Updating the Dashboard

When you push code changes:

```bash
ssh user@your-vps-ip
cd ~/vmflow
git pull
cd vending-backend
docker compose up -d --build
```

The `--build` flag rebuilds only changed images. Downtime is typically under 30 seconds.

### Zero-downtime alternative

```bash
# Build new images first
docker compose build

# Then restart (shorter downtime)
docker compose up -d
```

---

## Troubleshooting

### Next.js container won't start

```bash
docker compose logs next-app
```

Common causes:
- Missing env vars: check `.env` has all `SUPABASE_*` and `NEXT_PUBLIC_SUPABASE_*` vars
- Port 3000 already in use: `sudo lsof -i :3000`

### MQTT bridge can't connect

```bash
docker compose logs mqtt-bridge
```

- Ensure `MQTT_HOST=mosquitto` (Docker service name, not `localhost`)
- Check Mosquitto is running: `docker compose logs mosquitto`

### Supabase errors

- Verify keys are correct in `.env`
- Check the Supabase project isn't paused at https://supabase.com/dashboard
- Check RLS policies: service role key bypasses RLS, anon key does not

### Nginx 502 Bad Gateway

- Next.js container may not be running: `docker compose ps`
- Check if Next.js is listening: `curl http://localhost:3000`
- Check Nginx config: `sudo nginx -t`

### SSL certificate issues

- Ensure ports 80 and 443 are open: `sudo ufw status`
- DNS must point to the VPS IP: `dig dashboard.panamavendingmachines.com`
- Renew manually: `sudo certbot renew`

### View container resource usage

```bash
docker stats
```

---

## Backup

### Database

The database is on Supabase Cloud — backups are handled automatically by Supabase (daily backups on Pro plan).

### MQTT data

Mosquitto data is in a Docker volume:

```bash
# List volumes
docker volume ls

# Backup mosquitto data
docker run --rm -v vmflow_mosquitto_data:/data -v $(pwd):/backup alpine tar czf /backup/mosquitto-backup.tar.gz /data
```

---

## Service Management

```bash
# Stop all services
docker compose down

# Restart a single service
docker compose restart next-app

# View live logs
docker compose logs -f

# Rebuild and restart a single service
docker compose up -d --build next-app

# Remove everything including volumes (DESTRUCTIVE)
docker compose down -v
```
