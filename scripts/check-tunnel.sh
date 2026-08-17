#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf '%s\n' "tunnel check failed: $*" >&2
  exit 1
}

env_file=${DEVSPACE_TUNNEL_ENV_FILE:-/etc/devspace/openai-mcp-tunnel.env}
profile_file=${TUNNEL_CLIENT_PROFILE_FILE:-/etc/devspace/tunnel-client/devspace.yaml}
if [[ -r "$env_file" ]]; then
  set -a
  # shellcheck disable=SC1090
  . "$env_file"
  set +a
fi

[[ -r "$profile_file" ]] || fail "profile is missing ($profile_file)"
[[ -n "${OPENAI_TUNNEL_ID:-}" ]] || fail "OPENAI_TUNNEL_ID is not configured"
[[ -n "${OPENAI_TUNNEL_RUNTIME_KEY:-}" ]] || fail "OPENAI_TUNNEL_RUNTIME_KEY is not configured"
[[ -n "${OAUTH_PUBLIC_BASE_URL:-}" ]] || fail "OAUTH_PUBLIC_BASE_URL is not configured"

mcp_target=${OPENAI_MCP_TUNNEL_TARGET_URL:-http://127.0.0.1:9292/mcp}
health_addr=${TUNNEL_HEALTH_LISTEN_ADDR:-127.0.0.1:8080}
health_base=${TUNNEL_HEALTH_URL:-http://$health_addr}
health_base=${health_base%/}
expected_auth_server=${OAUTH_PUBLIC_BASE_URL%/}/
poll_ready_timeout=${TUNNEL_POLL_READY_TIMEOUT_SECONDS:-45}

systemctl is-active --quiet openai-mcp-tunnel.service \
  || fail "openai-mcp-tunnel.service is not active"

printf '%s\n' "tunnel-client: $(tunnel-client --version 2>/dev/null | head -n 1)"
tunnel-client doctor \
  --profile-file "$profile_file" \
  --control-plane.tunnel-id "$OPENAI_TUNNEL_ID" \
  --control-plane.api-key env:OPENAI_TUNNEL_RUNTIME_KEY \
  --mcp.server-url "url=$mcp_target,channel=main" \
  --health.listen-addr 127.0.0.1:0 \
  --json

ready_file=$(mktemp)
trap 'rm -f "$ready_file"' EXIT
ready_code=000
ready_body=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
  if ready_code=$(curl --compressed --silent --show-error --output "$ready_file" --write-out '%{http_code}' --max-time 5 "$health_base/readyz"); then
    ready_body=$(cat "$ready_file")
    [[ "$ready_code" == 200 ]] && break
  else
    ready_code=000
    ready_body="request failed"
  fi
  sleep 1
done
[[ "$ready_code" == 200 ]] || fail "/readyz returned $ready_code: $ready_body"

status=$(curl --compressed --fail --silent --show-error --max-time 5 "$health_base/api/status")
oauth=$(curl --compressed --fail --silent --show-error --max-time 5 "$health_base/api/oauth")

STATUS_JSON="$status" \
OAUTH_JSON="$oauth" \
EXPECTED_TUNNEL_ID="$OPENAI_TUNNEL_ID" \
EXPECTED_MCP_TARGET="$mcp_target" \
EXPECTED_AUTH_SERVER="$expected_auth_server" \
node <<'NODE'
const fail = (message) => {
  console.error(`tunnel check failed: ${message}`);
  process.exit(1);
};

const parse = (name) => {
  try {
    return JSON.parse(process.env[name]);
  } catch (error) {
    fail(`${name} is not valid JSON: ${error.message}`);
  }
};

const normalize = (raw) => new URL(raw).href;
const status = parse("STATUS_JSON");
const oauth = parse("OAUTH_JSON");

if (status.control_plane_tunnel_id !== process.env.EXPECTED_TUNNEL_ID) {
  fail("running client tunnel ID does not match OPENAI_TUNNEL_ID");
}
if (normalize(status.mcp_server_url) !== normalize(process.env.EXPECTED_MCP_TARGET)) {
  fail("running client MCP target does not match OPENAI_MCP_TUNNEL_TARGET_URL");
}
if (status.metadata_error) {
  fail(`tunnel metadata lookup failed: ${status.metadata_error}`);
}
const main = Array.isArray(status.channels)
  ? status.channels.find((channel) => channel.name === "main")
  : null;
if (!main || main.enabled !== true) {
  fail("main MCP channel is not enabled");
}
if (main.probe_status && !["ok", "auth-required", "timeout"].includes(main.probe_status)) {
  fail(`main MCP probe is ${main.probe_status}: ${main.probe_error || main.reason || "unknown"}`);
}
if (oauth.pending) {
  fail("OAuth discovery is still pending");
}
if (oauth.error) {
  fail(`OAuth discovery failed: ${oauth.error}`);
}
if (!oauth.metadata || oauth.metadata.status_code !== 200) {
  fail("OAuth protected-resource metadata was not fetched successfully");
}
if (normalize(oauth.selected_authorization_server) !== normalize(process.env.EXPECTED_AUTH_SERVER)) {
  fail("OAuth discovery did not select the configured public authorization server");
}
NODE

polling=false
metrics=""
poll_deadline=$((SECONDS + poll_ready_timeout))
while (( SECONDS < poll_deadline )); do
  metrics=$(curl --compressed --fail --silent --show-error --max-time 5 "$health_base/metrics")
  if METRICS_TEXT="$metrics" node <<'NODE'
const line = (process.env.METRICS_TEXT || "")
  .split(/\r?\n/)
  .find((value) => /^commands_poll_last_successful_timestamp_seconds(?:\{|\s)/.test(value));
if (!line) process.exit(1);
const value = Number(line.trim().split(/\s+/).at(-1));
process.exit(Number.isFinite(value) && value > 0 ? 0 : 1);
NODE
  then
    polling=true
    break
  fi
  sleep 1
done

if [[ "$polling" != true ]]; then
  printf '%s\n' "tunnel: control-plane poll metrics:" >&2
  grep -E '^commands_poll_(cycles|errors|last_successful_timestamp_seconds)' <<<"$metrics" >&2 || true
  printf '%s\n' "tunnel: recent control-plane poll log entries:" >&2
  journalctl -u openai-mcp-tunnel.service -n 200 --no-pager 2>/dev/null \
    | grep -Ei 'poller started|poll failed|poll timed out|poller recovered' \
    | tail -n 20 >&2 || true
  fail "no successful control-plane poll is visible after ${poll_ready_timeout}s; inspect the status_code, error_code, and mitigation above"
fi

printf '%s\n' "tunnel: ready ($ready_body)"
printf '%s\n' "tunnel: running configuration matches the tunnel ID and MCP target"
printf '%s\n' "tunnel: OAuth discovery selected the public authorization server"
printf '%s\n' "tunnel: control-plane polling is active"
