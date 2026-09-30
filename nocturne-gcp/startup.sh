#!/usr/bin/env bash
# Compute Engine startup script for Nocturne. Runs as root on every boot, so every
# step checks before it acts. Logs: sudo journalctl -u google-startup-scripts
set -euo pipefail

DIR=/opt/nocturne
MD=http://metadata.google.internal/computeMetadata/v1
md() { curl -fsS -H 'Metadata-Flavor: Google' "$MD/$1"; }

BASE_DOMAIN=$(md instance/attributes/base-domain)
EXTERNAL_IP=$(md instance/network-interfaces/0/access-configs/0/external-ip)
echo "nocturne: domain=$BASE_DOMAIN ip=$EXTERNAL_IP"

# Swap gives a 2 GB e2-small some headroom; harmless on larger machines.
if [[ ! -f /swapfile ]]; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
swapon --show | grep -q /swapfile || swapon /swapfile

if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
  echo "nocturne: installing Docker"
  apt-get update -y
  apt-get install -y ca-certificates curl openssl dnsutils
  curl -fsSL https://get.docker.com | sh
fi
systemctl enable --now docker
command -v dig >/dev/null || apt-get install -y dnsutils

mkdir -p "$DIR"
cd "$DIR"

# Only fetched once. To move to a newer compose file later, see the README.
if [[ ! -f docker-compose.yaml ]]; then
  curl -fsSL -o docker-compose.yaml https://github.com/nightscout/nocturne/releases/latest/download/docker-compose.yaml
fi

if [[ ! -f .env ]]; then
  curl -fsSL -o .env.new https://github.com/nightscout/nocturne/releases/latest/download/default.env.example
  # Hex secrets: long, and nothing that needs quoting in .env or sed.
  for key in INSTANCE_KEY POSTGRES_APP_PASSWORD POSTGRES_MIGRATOR_PASSWORD POSTGRES_PASSWORD POSTGRES_WEB_PASSWORD; do
    sed -i "s|^${key}=\$|${key}=$(openssl rand -hex 32)|" .env.new
  done
  sed -i "s|^BASE_DOMAIN=.*|BASE_DOMAIN=${BASE_DOMAIN}|" .env.new
  for key in BASE_DOMAIN INSTANCE_KEY POSTGRES_APP_PASSWORD POSTGRES_MIGRATOR_PASSWORD POSTGRES_PASSWORD POSTGRES_WEB_PASSWORD; do
    grep -qE "^${key}=.+" .env.new || { echo "nocturne: $key not set; template changed? Fill $DIR/.env.new by hand and rename it to .env"; exit 1; }
  done
  chmod 600 .env.new
  mv .env.new .env
  echo "nocturne: generated $DIR/.env"
fi
# Keep BASE_DOMAIN in step with instance metadata if it was changed.
sed -i "s|^BASE_DOMAIN=.*|BASE_DOMAIN=${BASE_DOMAIN}|" .env

# Caddy asks Let's Encrypt for a certificate as soon as it starts; starting before DNS
# points here just burns failed attempts against its rate limits.
for i in $(seq 1 120); do
  resolved=$(dig +short A "$BASE_DOMAIN" @8.8.8.8 | tail -n1)
  [[ "$resolved" == "$EXTERNAL_IP" ]] && break
  echo "nocturne: waiting for $BASE_DOMAIN -> $EXTERNAL_IP (currently '${resolved:-nothing}'), $i/120"
  sleep 30
done

docker compose up -d
echo "nocturne: started. Open https://$BASE_DOMAIN/"
