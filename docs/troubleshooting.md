# Troubleshooting by layer

1. **DevSpace process** — `systemctl status devspace.service`; inspect `journalctl -u devspace.service`. Confirm the installed unit contains `DEVSPACE_TRUST_PROXY=1`, and inspect configuration without printing owner tokens.
2. **DevSpace MCP protocol** — run `scripts/check-devspace-mcp.sh` with the service owner-token file and an allowed disposable root. This proves the direct `127.0.0.1:9191` path only; it does not prove tunnel readiness.
3. **Local authentication** — verify DevSpace's protected-resource and authorization-server metadata on `127.0.0.1:9191`. Loopback URLs are correct at this internal layer.
4. **OAuth gateway** — run `sudo scripts/check-oauth-gateway.sh`. The gateway and the public HTTPS origin must advertise `OAUTH_PUBLIC_BASE_URL` and `MCP_PUBLIC_RESOURCE_URL`, and neither metadata nor `WWW-Authenticate` may contain `127.0.0.1`, `localhost`, or `::1`. A gateway 502 means the fixed DevSpace upstream is unavailable.
5. **Tunnel client** — run `sudo scripts/check-tunnel.sh`. `/healthz` only proves liveness. `/readyz` must return HTTP 200; an auth-required explanation in its body is acceptable because OAuth-protected startup probes are expected. The check also verifies the running tunnel ID, MCP target, OAuth selection, and successful polling.
6. **Tunnel control plane** — inspect `/metrics` for `commands_poll_last_successful_timestamp_seconds` greater than zero. A successful local `doctor` without this metric does not prove that the runtime key can poll the selected tunnel.
7. **Organization/workspace association** — the tunnel must include both the Platform organization and the ChatGPT Business workspace. The app creator and runtime-key principal need the required Tunnels permissions.
8. **ChatGPT custom app** — only after both repository checks pass, enable developer mode, select the exact tunnel, approve OAuth, and run a fresh tool scan.
9. **Tool scan** — while scanning, follow `journalctl -f -u openai-mcp-tunnel.service -u devspace-oauth-gateway.service -u devspace.service`. A successful scan should cause `initialize`, `notifications/initialized`, and `tools/list` activity on the main MCP path.
10. **Tool invocation** — distinguish a ChatGPT policy confirmation from a tunnel transport error and from a DevSpace tool error. Record only redacted request identifiers and error summaries.

## Fast diagnostics

```bash
sudo scripts/check-oauth-gateway.sh
sudo scripts/check-tunnel.sh
curl --compressed -fsS http://127.0.0.1:8080/api/status
curl --compressed -fsS http://127.0.0.1:8080/api/oauth
curl --compressed -fsS http://127.0.0.1:8080/metrics \
  | grep '^commands_poll_last_successful_timestamp_seconds'
```

If the public metadata, readiness, and polling checks all pass but a ChatGPT scan sends no `initialize` or `tools/list`, preserve the scan time and redacted logs and escalate the remaining failure as a product-side tool-verification problem.

If a runtime key appears in chat, logs, command history, or a support ticket, revoke it and issue a replacement before further testing.
