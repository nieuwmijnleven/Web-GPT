# Troubleshooting by layer

1. **DevSpace process** — `systemctl status devspace.service`; inspect `journalctl -u devspace.service`. Check Node/package ACLs and `/var/lib/devspace/.devspace/config.json` without printing auth values.
2. **DevSpace MCP protocol** — run `scripts/check-devspace-mcp.sh` with the service owner-token file and an allowed disposable root. A 401 indicates OAuth; `-32601` for prompts is an expected unsupported capability.
3. **Local authentication** — verify protected-resource and authorization-server metadata on `127.0.0.1:9191`; do not disable OAuth to make a test pass.
4. **Tunnel client** — run `scripts/check-tunnel.sh`, then `tunnel-client doctor --profile-file /etc/devspace/tunnel-client/devspace.yaml --explain`. Check `/healthz`, `/readyz`, and `/ui` on loopback.
5. **Tunnel control plane** — confirm outbound HTTPS to `api.openai.com:443`, runtime-key validity, tunnel ID, and the exact control-plane error/correlation ID. Runtime operation needs `Tunnels Read + Use`; CRUD needs `Tunnels Read + Manage`.
6. **Organization/workspace association** — the tunnel must include both the Platform organization and the ChatGPT Business workspace. A missing workspace mapping is distinct from a local MCP failure; use the Platform tunnel settings/account team path for an enterprise manual association review.
7. **ChatGPT custom app** — confirm Business admin developer mode, Tunnel selection, OAuth approval, and a fresh tool scan. A tunnel not listed usually means missing workspace association or `Tunnels Read + Use`.
8. **Tool scan** — compare the ChatGPT scan with `docs/devspace-capabilities.md`; refresh/review actions when the server catalog changes. A frozen Business snapshot can differ from the live DevSpace catalog.
9. **Tool invocation** — distinguish a ChatGPT policy block/confirmation from a tunnel transport error and from a DevSpace tool error. Record the tool name, redacted request ID, HTTP status, and JSON-RPC/tool error without recording credentials or full source output.

When association or authorization fails, preserve the exact error and correlation ID, redact the workspace ID except for a short prefix, keep the local tunnel profile prepared, and do not expose DevSpace publicly as a workaround.
