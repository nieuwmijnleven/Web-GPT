# OpenAI Secure MCP Tunnel

## Current state

The official `openai/tunnel-client` v0.0.11 Linux amd64 binary is installed at `/usr/local/bin/tunnel-client`; the release archive SHA-256 `29adfe5c1399dfb9fda9383f230c324355912f50dc36e2e416b1f1322317b3c4` was verified before extraction. `config/openai-mcp-tunnel.yaml` is the profile installed at `/etc/devspace/tunnel-client/devspace.yaml`. The wrapper `scripts/run-openai-mcp-tunnel` translates project variables to the supported v0.0.11 flags and targets the loopback OAuth gateway.

The tunnel ID and restricted runtime key are stored outside Git in `/etc/devspace/openai-mcp-tunnel.env`; no administrative key is placed in the runtime service.

## Supported translation

The installed client supports the following relevant forms:

```text
--control-plane.tunnel-id "$OPENAI_TUNNEL_ID"
--control-plane.api-key env:OPENAI_TUNNEL_RUNTIME_KEY
--mcp.server-url "url=$OPENAI_MCP_TUNNEL_TARGET_URL,channel=main"
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

5. Run `sudo systemctl enable --now devspace-oauth-gateway.service openai-mcp-tunnel.service`, then run `scripts/check-oauth-gateway.sh` and `sudo scripts/check-tunnel.sh`. Confirm the gateway metadata locally and `/healthz` on `127.0.0.1:8080`; `/readyz` can report the expected unauthenticated MCP probe while OAuth is enabled.

## OAuth gateway decision

The tunnel target is the loopback OAuth-aware gateway at `127.0.0.1:9292`; DevSpace remains at `127.0.0.1:9191` and is never exposed directly. Local protocol/auth/catalog/write/command/reconnect tests continue to target DevSpace directly, while the gateway-specific checks cover metadata translation, OAuth endpoint proxying, 401 challenge rewriting, and MCP Bearer forwarding.

The gateway advertises the tunnel endpoint as the OAuth issuer/resource, translates the public `resource` parameter back to DevSpace's loopback resource for authorization and token requests, and proxies the resulting token to DevSpace. OpenAI documents that OAuth discovery can travel through a tunnel while the authorization server is not automatically tunneled; this gateway explicitly provides that missing same-tunnel path ([Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels)). Run the Business workspace scan before publishing and inspect the gateway/tunnel logs if discovery or token exchange fails.
