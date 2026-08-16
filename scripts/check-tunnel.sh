#!/usr/bin/env bash
set -euo pipefail

profile_file=${TUNNEL_CLIENT_PROFILE_FILE:-/etc/devspace/tunnel-client/devspace.yaml}
if [[ -r /etc/devspace/openai-mcp-tunnel.env ]]; then
  set -a
  # shellcheck disable=SC1091
  . /etc/devspace/openai-mcp-tunnel.env
  set +a
fi
if [[ ! -r "$profile_file" ]]; then
  printf '%s\n' "profile: missing ($profile_file)"
  exit 2
fi
if [[ -z "${OPENAI_TUNNEL_ID:-}" || -z "${OPENAI_TUNNEL_RUNTIME_KEY:-}" ]]; then
  printf '%s\n' "tunnel: blocked (OPENAI_TUNNEL_ID and OPENAI_TUNNEL_RUNTIME_KEY are not configured)"
  exit 2
fi

printf '%s\n' "tunnel-client: $(tunnel-client --version 2>/dev/null | head -n 1)"
tunnel-client doctor --profile-file "$profile_file" --control-plane.tunnel-id "$OPENAI_TUNNEL_ID" --control-plane.api-key env:OPENAI_TUNNEL_RUNTIME_KEY --mcp.server-url "url=${OPENAI_MCP_TUNNEL_TARGET_URL:-http://127.0.0.1:9292/mcp},channel=main" --health.listen-addr 127.0.0.1:0 --json
