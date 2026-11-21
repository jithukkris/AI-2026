#!/usr/bin/env bash
# One-time setup for a fresh Google Cloud e2-micro VM (Debian 12 / Ubuntu 22.04+).
# Usage (on the VM):
#   curl -fsSL https://raw.githubusercontent.com/<you>/<repo>/main/deploy/gcp/setup-vm.sh | bash
#   # or copy the file over and run:  bash setup-vm.sh
set -euo pipefail

echo "==> Adding 2 GB swap (e2-micro has only ~1 GB RAM)"
if ! swapon --show | grep -q /swapfile; then
  sudo fallocate -l 2G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
  echo 'vm.swappiness=20' | sudo tee /etc/sysctl.d/99-swap.conf
  sudo sysctl -p /etc/sysctl.d/99-swap.conf
fi

echo "==> Installing Docker Engine + Compose plugin"
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sudo sh
  sudo usermod -aG docker "$USER"
fi

echo "==> Installing git"
sudo apt-get update -y && sudo apt-get install -y git

cat <<'EOF'

Done. Next steps:
  1. Log out and back in (so your user can run docker without sudo).
  2. git clone <your repo url> healpay && cd healpay
  3. cp .env.example .env && nano .env        # set passwords (and ANTHROPIC_API_KEY if using Claude)
  4. docker compose -f docker-compose.yml -f docker-compose.gcp.yml up -d --build
  5. Expose it with Cloudflare Tunnel (see docs/DEPLOY-GCP.md).

Tip: building the image on an e2-micro is slow (5-10 min). It only happens once.
EOF
