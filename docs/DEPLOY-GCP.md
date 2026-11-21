# ☁️ Deploy for free on Google Cloud (e2-micro) + Cloudflare Tunnel

The result is an always-on HealPay you can open from any network over HTTPS.

> Free-tier rules change. Check <https://cloud.google.com/free> before you start, and **set a budget alert**.

## What's free (as of late 2026)

- One non-preemptible **e2-micro** VM per month in **`us-west1`, `us-central1` or `us-east1`** only.
- 30 GB-months of standard persistent disk.
- 1 GB/month of outbound data transfer from North America (excluding China and Australia).

The e2-micro has about **1 GB of RAM**, so we add swap and cap memory (`docker-compose.gcp.yml`).

## 1. Create the VM

1. Create a Google Cloud project and a billing account. A card is required for verification.
2. **Billing → Budgets & alerts**: create a budget of e.g. ₹100/month with email alerts.
3. **Compute Engine → VM instances → Create instance**:
   - Region **`us-central1`** (or `us-west1` / `us-east1`)
   - Machine type **e2-micro**
   - Boot disk **Debian 12**, **Standard persistent disk**, 30 GB
   - Firewall: you do **not** need to open HTTP/HTTPS if you use Cloudflare Tunnel
4. Click **SSH** to open a terminal on the VM.

> After a few days, check **Billing → Reports** to confirm the VM shows as free. In particular, check
> whether an external IP address is being charged on your account.

## 2. Install Docker and swap

```bash
git clone https://github.com/<you>/<repo>.git healpay
cd healpay
bash deploy/gcp/setup-vm.sh
exit        # log out and SSH in again so docker works without sudo
```

## 3. Configure and start

```bash
cd healpay
cp .env.example .env
nano .env
#   DB_PASSWORD=<strong>
#   ADMIN_PASSWORD=<strong>   VIEWER_PASSWORD=<strong>
#   For real AI:  SPRING_PROFILES_ACTIVE=postgres,claude   and   ANTHROPIC_API_KEY=sk-ant-...

docker compose -f docker-compose.yml -f docker-compose.gcp.yml up -d --build
docker compose logs -f app       # wait for "Started HealPayApplication"
curl -s localhost:8080/actuator/health
```

The first build takes 5–10 minutes on an e2-micro.
To skip building on the VM, build and push the image from your laptop to a registry and change `build: .` to `image: …`.

The GCP override binds the app to `127.0.0.1:8080` only. It's reachable from the internet only through the tunnel.

## 4. Expose it with Cloudflare Tunnel

Install `cloudflared` on the VM:

```bash
curl -L -o cloudflared.deb https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb
sudo dpkg -i cloudflared.deb
```

**Option A: quick tunnel (no account, temporary URL).** Good for a demo:

```bash
cloudflared tunnel --url http://localhost:8080
# prints https://<random-words>.trycloudflare.com
```

Run it inside `tmux` or `screen` so it survives closing SSH. The URL changes each time you restart it.

**Option B: named tunnel (permanent URL, needs a domain on Cloudflare DNS):**

```bash
cloudflared tunnel login
cloudflared tunnel create healpay
cloudflared tunnel route dns healpay healpay.yourdomain.com
cp deploy/cloudflared/config.yml.example ~/.cloudflared/config.yml   # fill in tunnel id + user
sudo cloudflared service install
```

## 5. Alternatives

| Need | Option |
|---|---|
| Only you need access | **Tailscale** on the VM and your devices, then open `http://<vm-tailscale-name>:8080` (change the port binding) |
| Your own domain without Cloudflare | Open ports 80/443, install **Nginx + Let's Encrypt (certbot)**, and proxy to `127.0.0.1:8080`. Add `proxy_buffering off;` for `/api/events/stream`. |
| Run on your laptop, share temporarily | `mvn spring-boot:run`, then `cloudflared tunnel --url http://localhost:8080` (or ngrok) |

## 6. Operations cheat-sheet

```bash
docker compose ps
docker compose logs -f app
git pull && docker compose -f docker-compose.yml -f docker-compose.gcp.yml up -d --build   # update
docker compose down            # stop (data kept in the pgdata volume)
free -h                        # memory + swap
```

## 7. Security checklist before sharing the URL

- [ ] Changed `ADMIN_PASSWORD` and `VIEWER_PASSWORD` from the defaults
- [ ] `.env` is not committed (it's in `.gitignore`)
- [ ] App bound to localhost (`docker-compose.gcp.yml`) and exposed only via the tunnel
- [ ] Budget alert configured in Google Cloud
- [ ] Share the **viewer** login with judges; keep **admin** for yourself
