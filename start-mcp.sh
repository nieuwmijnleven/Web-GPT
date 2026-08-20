#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
env_file=${DEVSPACE_TUNNEL_ENV_FILE:-/etc/devspace/openai-mcp-tunnel.env}

"$repo_dir/scripts/install-services.sh"

if ! sudo test -r "$env_file"; then
  printf '%s\n' "Missing or unreadable tunnel environment file after install: $env_file" >&2
  exit 1
fi

read_assignment() {
  local name=$1
  sudo awk -F= -v name="$name" '$1 == name { sub(/^[^=]*=/, ""); print; exit }' "$env_file"
}

oauth_public_base_url=$(read_assignment OAUTH_PUBLIC_BASE_URL)
tunnel_id=$(read_assignment OPENAI_TUNNEL_ID)
tunnel_runtime_key=$(read_assignment OPENAI_TUNNEL_RUNTIME_KEY)

if [[ "$oauth_public_base_url" != https://* || -z "$tunnel_id" || -z "$tunnel_runtime_key" ]]; then
  printf '%s\n' "Base services installed. Configure OAUTH_PUBLIC_BASE_URL, OPENAI_TUNNEL_ID, and OPENAI_TUNNEL_RUNTIME_KEY in $env_file, then rerun ./start-mcp.sh."
  exit 0
fi

if [[ -f "$repo_dir/oauth-proxy/docker-compose.yml" ]]; then
  if [[ ! -f "$repo_dir/oauth-proxy/certbot/conf/live/devspace-oauth/fullchain.pem" ]]; then
    "$repo_dir/oauth-proxy/bootstrap.sh"
  else
    sudo docker compose --file "$repo_dir/oauth-proxy/docker-compose.yml" up --detach
  fi
fi

sudo systemctl restart devspace-oauth-gateway.service openai-mcp-tunnel.service
sudo bash -x "$repo_dir/scripts/check-oauth-gateway.sh"
sudo bash -x "$repo_dir/scripts/check-tunnel.sh"
