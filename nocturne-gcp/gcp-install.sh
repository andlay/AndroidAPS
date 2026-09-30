#!/usr/bin/env bash
# Nocturne on Google Cloud (Compute Engine). Run from Google Cloud Shell:
#
#   BASE_DOMAIN=nocturne.example.com bash gcp-install.sh
#
# Creates a static external IP, a firewall rule for 80/443, a daily disk snapshot
# schedule and a Debian VM. The VM's startup script installs Docker, downloads the
# Nocturne release bundle (docker-compose.yaml + default.env.example), generates
# INSTANCE_KEY and the database passwords, waits for DNS to point at the VM and then
# runs `docker compose up -d`. HTTPS is handled by the Caddy container in the bundle.
#
# Re-running is safe: every resource is looked up by name before it is created.
#
# Optional environment:
#   PROJECT        GCP project (default: the active gcloud project)
#   REGION / ZONE  default us-central1 / us-central1-a
#   MACHINE_TYPE   default e2-medium (4 GB, Nocturne's recommended size).
#                  e2-small (2 GB) is Nocturne's stated minimum; e2-micro (1 GB) is too small.
#   DISK_GB        boot disk size, default 20
#   NAME           resource name prefix, default nocturne
#   SNAPSHOTS=0    skip the daily snapshot schedule
set -euo pipefail

NAME="${NAME:-nocturne}"
PROJECT="${PROJECT:-$(gcloud config get-value project 2>/dev/null)}"
REGION="${REGION:-us-central1}"
ZONE="${ZONE:-${REGION}-a}"
MACHINE_TYPE="${MACHINE_TYPE:-e2-medium}"
DISK_GB="${DISK_GB:-20}"
SNAPSHOTS="${SNAPSHOTS:-1}"
TAG="${NAME}-web"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log()  { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
info() { printf '    %s\n' "$*"; }
die()  { printf '\n\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

command -v gcloud >/dev/null || die "gcloud is not available. Run this from Google Cloud Shell."
[[ -n "$PROJECT" ]] || die "no project set. Run 'gcloud config set project <id>' or set PROJECT."
[[ -f "$HERE/startup.sh" ]] || die "startup.sh must sit next to this script"
case "$MACHINE_TYPE" in
  e2-micro|f1-micro|g1-small) die "$MACHINE_TYPE has less than the 2 GB Nocturne needs. Use e2-small or e2-medium." ;;
esac

if [[ -z "${BASE_DOMAIN:-}" ]]; then
  read -rp "Domain Nocturne should answer on (e.g. nocturne.example.com): " BASE_DOMAIN
fi
BASE_DOMAIN="${BASE_DOMAIN,,}"
[[ "$BASE_DOMAIN" =~ ^([a-z0-9-]+\.)+[a-z]{2,}$ ]] || die "BASE_DOMAIN must be a domain with at least two labels, not an IP"

G=(gcloud --project "$PROJECT" --quiet)

log "Enabling the Compute Engine API in $PROJECT"
"${G[@]}" services enable compute.googleapis.com

log "Reserving a static external IP ($NAME-ip in $REGION)"
if ! "${G[@]}" compute addresses describe "$NAME-ip" --region "$REGION" >/dev/null 2>&1; then
  "${G[@]}" compute addresses create "$NAME-ip" --region "$REGION" --network-tier PREMIUM
fi
IP=$("${G[@]}" compute addresses describe "$NAME-ip" --region "$REGION" --format='value(address)')
info "static IP: $IP"

log "Opening ports 80 and 443 (firewall rule $NAME-allow-web)"
if ! "${G[@]}" compute firewall-rules describe "$NAME-allow-web" >/dev/null 2>&1; then
  "${G[@]}" compute firewall-rules create "$NAME-allow-web" \
    --network default --direction INGRESS --action ALLOW \
    --rules tcp:80,tcp:443,udp:443 --source-ranges 0.0.0.0/0 --target-tags "$TAG"
fi

if [[ "$SNAPSHOTS" == "1" ]]; then
  log "Creating a daily snapshot schedule ($NAME-daily, kept 14 days)"
  if ! "${G[@]}" compute resource-policies describe "$NAME-daily" --region "$REGION" >/dev/null 2>&1; then
    "${G[@]}" compute resource-policies create snapshot-schedule "$NAME-daily" \
      --region "$REGION" --daily-schedule --start-time 03:00 \
      --max-retention-days 14 --on-source-disk-delete keep-auto-snapshots
  fi
fi

log "Creating the VM ($NAME, $MACHINE_TYPE, Debian 12, ${DISK_GB} GB)"
if "${G[@]}" compute instances describe "$NAME" --zone "$ZONE" >/dev/null 2>&1; then
  info "already exists; updating its startup script and domain"
  "${G[@]}" compute instances add-metadata "$NAME" --zone "$ZONE" \
    --metadata "base-domain=$BASE_DOMAIN" --metadata-from-file "startup-script=$HERE/startup.sh"
else
  "${G[@]}" compute instances create "$NAME" --zone "$ZONE" \
    --machine-type "$MACHINE_TYPE" \
    --image-family debian-12 --image-project debian-cloud \
    --boot-disk-size "${DISK_GB}GB" --boot-disk-type pd-balanced \
    --address "$IP" --tags "$TAG" \
    --metadata "base-domain=$BASE_DOMAIN" \
    --metadata-from-file "startup-script=$HERE/startup.sh"
fi

if [[ "$SNAPSHOTS" == "1" ]]; then
  "${G[@]}" compute disks add-resource-policies "$NAME" --zone "$ZONE" \
    --resource-policies "$NAME-daily" 2>/dev/null || true
fi

cat <<EOF

$(printf '\033[1;32m')Done.$(printf '\033[0m') Now create these DNS records at your DNS provider:

    $BASE_DOMAIN.      A    $IP
    *.$BASE_DOMAIN.    A    $IP

The VM waits (up to an hour) for $BASE_DOMAIN to resolve to $IP before starting
Nocturne, so Let's Encrypt isn't asked for a certificate too early.

Watch progress:
    gcloud compute ssh $NAME --zone $ZONE --project $PROJECT -- sudo journalctl -u google-startup-scripts -f

Then open https://$BASE_DOMAIN/ and run the setup wizard. Save the recovery codes.

The generated secrets are in /opt/nocturne/.env on the VM. Back that file up somewhere
safe: the database cannot be re-keyed from new passwords later.
EOF
