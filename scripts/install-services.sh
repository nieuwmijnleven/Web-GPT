#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
env_file=/etc/devspace/openai-mcp-tunnel.env

has_assignment() {
  local name=$1
  sudo grep -Eq "^[[:space:]]*${name}=[^[:space:]#]+[[:space:]]*$" "$env_file"
}

has_https_assignment() {
  local name=$1
  sudo grep -Eq "^[[:space:]]*${name}=https://[^[:space:]#]+[[:space:]]*$" "$env_file"
}

sudo install -d -o root -g root -m 0755 /etc/devspace/tunnel-client /usr/local/libexec
sudo install -o root -g root -m 0644 "$repo_dir/systemd/devspace.service" /etc/systemd/system/devspace.service
sudo install -o root -g root -m 0644 "$repo_dir/systemd/devspace-oauth-gateway.service" /etc/systemd/system/devspace-oauth-gateway.service
sudo install -o root -g root -m 0644 "$repo_dir/systemd/openai-mcp-tunnel.service" /etc/systemd/system/openai-mcp-tunnel.service
sudo install -o root -g root -m 0644 "$repo_dir/config/openai-mcp-tunnel.yaml" /etc/devspace/tunnel-client/devspace.yaml
sudo install -o root -g root -m 0755 "$repo_dir/scripts/run-openai-mcp-tunnel" /usr/local/libexec/run-openai-mcp-tunnel
sudo install -o root -g root -m 0755 "$repo_dir/scripts/devspace-oauth-gateway.mjs" /usr/local/libexec/devspace-oauth-gateway
if [[ ! -e "$env_file" ]]; then
  sudo install -o root -g devspace -m 0640 /dev/null "$env_file"
else
  sudo chown root:devspace "$env_file"
  sudo chmod 0640 "$env_file"
fi

sudo systemctl daemon-reload
sudo systemctl enable devspace.service
sudo systemctl restart devspace.service

if has_https_assignment OAUTH_PUBLIC_BASE_URL; then
  sudo systemctl enable devspace-oauth-gateway.service
  sudo systemctl restart devspace-oauth-gateway.service
else
  printf '%s\n' "DevSpace enabled; OAuth gateway not started because OAUTH_PUBLIC_BASE_URL is absent or not HTTPS."
fi

if has_https_assignment OAUTH_PUBLIC_BASE_URL \
  && has_assignment OPENAI_TUNNEL_ID \
  && has_assignment OPENAI_TUNNEL_RUNTIME_KEY; then
  sudo systemctl enable openai-mcp-tunnel.service
  sudo systemctl restart openai-mcp-tunnel.service
else
  printf '%s\n' "DevSpace enabled; tunnel unit not started because public OAuth or runtime credentials are incomplete."
fi

sudo systemctl --no-pager --full status devspace.service
