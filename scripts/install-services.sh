#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
sudo install -d -o root -g root -m 0755 /etc/devspace/tunnel-client /usr/local/libexec
sudo install -o root -g root -m 0644 "$repo_dir/systemd/devspace.service" /etc/systemd/system/devspace.service
sudo install -o root -g root -m 0644 "$repo_dir/systemd/devspace-oauth-gateway.service" /etc/systemd/system/devspace-oauth-gateway.service
sudo install -o root -g root -m 0644 "$repo_dir/systemd/openai-mcp-tunnel.service" /etc/systemd/system/openai-mcp-tunnel.service
sudo install -o root -g root -m 0644 "$repo_dir/config/openai-mcp-tunnel.yaml" /etc/devspace/tunnel-client/devspace.yaml
sudo install -o root -g root -m 0755 "$repo_dir/scripts/run-openai-mcp-tunnel" /usr/local/libexec/run-openai-mcp-tunnel
sudo install -o root -g root -m 0755 "$repo_dir/scripts/devspace-oauth-gateway.mjs" /usr/local/libexec/devspace-oauth-gateway
if [[ ! -e /etc/devspace/openai-mcp-tunnel.env ]]; then
  sudo install -o root -g devspace -m 0640 /dev/null /etc/devspace/openai-mcp-tunnel.env
else
  sudo chown root:devspace /etc/devspace/openai-mcp-tunnel.env
  sudo chmod 0640 /etc/devspace/openai-mcp-tunnel.env
fi
sudo systemctl daemon-reload
sudo systemctl enable --now devspace.service
if sudo grep -Eq '^[[:space:]]*(OPENAI_TUNNEL_ID|OAUTH_GATEWAY_PUBLIC_BASE_URL)=[^[:space:]]' /etc/devspace/openai-mcp-tunnel.env; then
  sudo systemctl enable --now devspace-oauth-gateway.service
else
  printf '%s\n' "DevSpace enabled; OAuth gateway installed but not started because its public base is not configured."
fi
if sudo grep -Eq '^[[:space:]]*OPENAI_TUNNEL_ID=[^[:space:]]' /etc/devspace/openai-mcp-tunnel.env \
  && sudo grep -Eq '^[[:space:]]*OPENAI_TUNNEL_RUNTIME_KEY=[^[:space:]]' /etc/devspace/openai-mcp-tunnel.env; then
  sudo systemctl enable --now openai-mcp-tunnel.service
else
  printf '%s\n' "DevSpace enabled; tunnel unit installed but not started because its runtime credentials are absent."
fi
sudo systemctl --no-pager --full status devspace.service
