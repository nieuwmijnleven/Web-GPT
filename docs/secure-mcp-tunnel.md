# OpenAI Secure MCP Tunnel

## Current state

The official `openai/tunnel-client` v0.0.11 Linux amd64 binary is installed at `/usr/local/bin/tunnel-client`; the release archive SHA-256 `29adfe5c1399dfb9fda9383f230c324355912f50dc36e2e416b1f1322317b3c4` was verified before extraction. `config/openai-mcp-tunnel.yaml` is the profile installed at `/etc/devspace/tunnel-client/devspace.yaml`. The wrapper `scripts/run-openai-mcp-tunnel` translates project variables to the supported v0.0.11 flags and forwards directly to `DEVSPACE_MCP_URL`.

No `OPENAI_TUNNEL_ID` or restricted `OPENAI_TUNNEL_RUNTIME_KEY` was present on the VPS. Consequently no tunnel was created, reused, or claimed ready, and `openai-mcp-tunnel.service` is installed but stopped/disabled. No administrative key was placed in the runtime service.

## Supported translation

The installed client supports the following relevant forms:

```text
--control-plane.tunnel-id "$OPENAI_TUNNEL_ID"
--control-plane.api-key env:OPENAI_TUNNEL_RUNTIME_KEY
--mcp.server-url "url=$DEVSPACE_MCP_URL,channel=main"
--health.listen-addr 127.0.0.1:8080
--log.level info --log.format json --log.file stdout
```

The client exposes loopback `/healthz`, `/readyz`, `/metrics`, and `/ui`. The profile keeps the admin UI loopback-only. Version 0.0.11 does not expose separate public flags for an upstream request timeout, keepalive interval, or reconnect backoff; the client’s built-in connection lifecycle is used rather than inventing unsupported settings. `--mcp.connection-max-ttl`, poll timeout, and max concurrent request flags are available if a later operational requirement needs them.

## Provisioning steps

1. In [Platform tunnel settings](https://platform.openai.com/settings/organization/tunnels), list existing tunnels before creating anything. Reuse a tunnel already associated with the target Platform organization and ChatGPT Business workspace when its purpose matches DevSpace.
2. If no match exists, create one named for this service, associate both the Platform organization and ChatGPT workspace, and record its ID. Tunnel CRUD requires `Tunnels Read + Manage`; runtime use and ChatGPT selection require `Tunnels Read + Use`.
3. Create a separate restricted runtime API key from [Runtime API keys](https://platform.openai.com/settings/organization/api-keys). Keep the admin key for administration only.
4. Edit `/etc/devspace/openai-mcp-tunnel.env` with `sudoedit`, using one assignment per line and no shell expansions:

```text
DEVSPACE_MCP_URL=http://127.0.0.1:9191/mcp
OPENAI_TUNNEL_ID=<the returned tunnel ID>
OPENAI_TUNNEL_RUNTIME_KEY=<the restricted runtime key>
TUNNEL_LOG_LEVEL=info
```

The angle-bracketed values above are field descriptions for the local handoff; they must be replaced before starting the unit and must never be committed. The file is mode 0640 and readable only by root and the `devspace` group.

5. Run `sudo systemctl enable --now openai-mcp-tunnel.service`, then run `scripts/check-tunnel.sh`. Confirm both `/healthz` and `/readyz` on `127.0.0.1:8080`; readiness is not inferred from a process merely existing.

## Direct-connection decision

Direct DevSpace forwarding remains the selected architecture. Local protocol/auth/catalog/write/command/reconnect tests passed, but the decisive remote checks—tunnel polling, tunnel-visible catalog, ChatGPT scan, and ChatGPT-originated invocation—cannot be performed without the missing OpenAI values. No gateway is created or justified.

OAuth has one additional remote compatibility check. DevSpace currently advertises its authorization server at loopback (`http://127.0.0.1:9191/`). OpenAI documents that MCP OAuth discovery can travel through the tunnel, but a private authorization server must still be reachable by the component performing OAuth ([Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels)). Run the Business workspace scan before publishing; if the authorization endpoint is unreachable, preserve the current authentication boundary, record the exact evidence, and only then evaluate a transparent gateway or another supported auth arrangement.
