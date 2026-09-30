# Nocturne on Google Cloud

These scripts deploy [Nocturne](https://getnocturne.dev) on a Compute Engine VM using the
official release bundle. You don't need to clone or build anything. They're modeled on
Nocturne's own Oracle Cloud installer, because the official docs don't have a GCP guide yet
("Guides for GCP, Azure, Heroku ... coming soon").

## What the official docs say (and where the usual advice is wrong)

- **HTTPS is built in.** The bundle runs six containers: PostgreSQL, API, web, a YARP gateway,
  **Caddy** and Watchtower. Caddy gets Let's Encrypt certificates for `BASE_DOMAIN`, and gets
  per-tenant `*.BASE_DOMAIN` and `*.share.BASE_DOMAIN` certificates on demand. You don't need
  another reverse proxy. If you already run one, use `docker-compose.byo-proxy.yaml`.
- **The env template is `default.env.example`**, not `.env.example`. Download it straight to `.env`.
- **Memory: 2 GB minimum, 4 GB recommended.** The stack uses 1–1.5 GB at rest.
  `e2-micro` (1 GB, GCP's free tier) is too small, `e2-small` (2 GB) is the floor, and
  `e2-medium` (4 GB) is the default here.
- **Disk: 10 GB minimum, 20 GB recommended.** The images take about 2.5 GB, and one CGM adds less than 1 GB a year.
- **DNS:** an `A` record for the domain, plus a wildcard `*.domain` record so tenant subdomains
  and share links resolve. One DNS wildcard also covers `x.share.domain`.
- **Updates:** Watchtower pulls new images daily. `NOCTURNE_API_IMAGE`/`NOCTURNE_WEB_IMAGE`
  default to `:latest`; pin them (e.g. `:0.2.7`) in `.env` if you want to choose when to upgrade.
- **Database passwords are read only on first start.** Changing them in `.env` later does
  not re-key the database, so back up `.env`.

## Cost (approximate, us-central1, on-demand)

| Item | Monthly |
|---|---|
| e2-medium (2 vCPU, 4 GB) | ~$25 |
| or e2-small (2 vCPU, 2 GB) | ~$12 |
| 20 GB balanced disk | ~$2 |
| Static IPv4 in use | ~$3.6 |
| Daily snapshots (incremental, 14 days) | <$1 |

**Free alternative:** Nocturne's official
[Oracle Cloud installer](https://getnocturne.dev/docs/installation/oracle-cloud) runs it on
Oracle's Always Free tier (1 Ampere core, 6 GB) with one command.

## Install

1. **Pick the domain.** A subdomain like `nocturne.example.com` keeps your apex free.
   `BASE_DOMAIN` must have at least two labels and can't be an IP address.
2. **Open [Cloud Shell](https://shell.cloud.google.com)** in the project you want to use, then:

   ```bash
   git clone -b claude/nocturne-gcp-setup-hvavqj --depth 1 https://github.com/andlay/AndroidAPS.git
   cd AndroidAPS/nocturne-gcp
   BASE_DOMAIN=nocturne.example.com bash gcp-install.sh
   # or: MACHINE_TYPE=e2-small BASE_DOMAIN=... bash gcp-install.sh
   ```

   Alternatively, copy `gcp-install.sh` and `startup.sh` into Cloud Shell by hand.

   The script:
   - enables the Compute API
   - reserves a static IP
   - creates a firewall rule for tcp 80/443 and udp 443 (HTTP/3)
   - sets up a daily snapshot schedule
   - creates a Debian 12 VM whose startup script installs Docker, downloads the release
     bundle and generates `INSTANCE_KEY` and the four PostgreSQL passwords.

3. **Create the two DNS records** that the script prints (`@` and `*` → the static IP).
   The VM waits up to an hour for DNS to point at it before it runs `docker compose up -d`.
   This keeps Caddy from hitting Let's Encrypt too early.
4. **Watch it come up:**

   ```bash
   gcloud compute ssh nocturne --zone us-central1-a -- sudo journalctl -u google-startup-scripts -f
   # then
   curl https://nocturne.example.com/api/v1/status.json
   ```

5. **Open `https://nocturne.example.com/`.** The setup wizard creates the first tenant and
   registers a passkey, then shows recovery codes. **Save the recovery codes.**
6. **Back up the secrets** from the VM:
   `gcloud compute ssh nocturne -- sudo cat /opt/nocturne/.env` and store them in a password manager.

## Day-2

```bash
gcloud compute ssh nocturne
cd /opt/nocturne
sudo docker compose ps
sudo docker compose logs -f nocturne-api
sudo docker compose pull && sudo docker compose up -d    # manual update
```

Watchtower updates images, not `docker-compose.yaml`. To pick up a newer compose file, run
`sudo curl -fsSLO https://github.com/nightscout/nocturne/releases/latest/download/docker-compose.yaml && sudo docker compose up -d`.
This recreates the containers. Your data volume is kept.

## Moving from Nightscout

- Keep the old Nightscout running until Nocturne's history and live readings look right.
- Import through the **Nightscout API** mode on the migration page. Avoid **MongoDB (Advanced)**
  until you've confirmed the fix for the "completes with full counts but imports no
  glucose/boluses/carbs" bug is in the release you're running.
- Nocturne aims for 1:1 Nightscout API compatibility. In AAPS, change the NSClient **Nightscout URL**
  and **API secret** (NSClient v1) or **access token** (NSClient v3) to point at Nocturne,
  and do the same in xDrip/followers.
- Nocturne is pre-1.0. Don't make it your only alerting path until you've tested it.
