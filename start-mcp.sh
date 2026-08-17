#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
env_file=${DEVSPACE_TUNNEL_ENV_FILE:-/etc/devspace/openai-mcp-tunnel.env}

if ! sudo test -r "$env_file"; then
  printf '%s\n' "Missing or unreadable tunnel environment file: $env_file" >&2
  exit 1
fi

"$repo_dir/scripts/install-services.sh"

if [[ -f "$repo_dir/oauth-proxy/docker-compose.yml" ]]; then
  docker compose --file "$repo_dir/oauth-proxy/docker-compose.yml" up --detach
fi

sudo bash -x "$repo_dir/scripts/check-oauth-gateway.sh"
sudo bash -x "$repo_dir/scripts/check-tunnel.sh"
