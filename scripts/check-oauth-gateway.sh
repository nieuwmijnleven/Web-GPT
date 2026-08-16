#!/usr/bin/env bash
set -euo pipefail

base_url=${OAUTH_GATEWAY_URL:-http://127.0.0.1:9292}
base_url=${base_url%/}

health=$(curl -fsS "$base_url/healthz")
protected=$(curl -fsS "$base_url/.well-known/oauth-protected-resource/mcp")
authorization=$(curl -fsS "$base_url/.well-known/oauth-authorization-server")

grep -q '"ok":true' <<<"$health"
grep -q '"authorization_servers"' <<<"$protected"
grep -q '"authorization_endpoint"' <<<"$authorization"

challenge=$(curl -sS -D - -o /dev/null "$base_url/mcp")
grep -qi '401' <<<"$challenge"
grep -qi 'oauth-protected-resource' <<<"$challenge"

printf '%s\n' "gateway: healthy"
printf '%s\n' "gateway: OAuth metadata available"
printf '%s\n' "gateway: MCP challenge rewritten"
