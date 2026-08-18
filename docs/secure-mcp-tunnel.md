# OpenAI Secure MCP Tunnel

## Current state

The official `openai/tunnel-client` v0.0.11 Linux amd64 binary is installed at `/usr/local/bin/tunnel-client`. `config/openai-mcp-tunnel.yaml` is installed at `/etc/devspace/tunnel-client/devspace.yaml`, and `scripts/run-openai-mcp-tunnel` targets the loopback OAuth gateway.

The tunnel ID, restricted runtime key, public OAuth origin, and public resource identifier are stored outside Git in `/etc/devspace/openai-mcp-tunnel.env`. No administrative key is placed in the runtime service, and the DevSpace service does not read this file.

## Required environment file

Use one literal assignment per line and no shell expansions:

```text
OAUTH_PUBLIC_BASE_URL=https://auth.example.com
MCP_PUBLIC_RESOURCE_URL=https://auth.example.com/mcp
DEVSPACE_MCP_URL=http://127.0.0.1:9191/mcp
OAUTH_GATEWAY_LISTEN_ADDR=127.0.0.1:9292
OPENAI_MCP_TUNNEL_TARGET_URL=http://127.0.0.1:9292/mcp
OPENAI_TUNNEL_ID=<tunnel ID>
OPENAI_TUNNEL_RUNTIME_KEY=<restricted runtime key>
TUNNEL_HEALTH_LISTEN_ADDR=127.0.0.1:8080
TUNNEL_LOG_LEVEL=info
```

The file must remain mode `0640`, owned by `root:devspace`. `DEVSPACE_TRUST_PROXY=1` is set directly in `systemd/devspace.service`; do not copy the tunnel runtime key into that unit.

## Supported tunnel-client translation

```text
--control-plane.tunnel-id "$OPENAI_TUNNEL_ID"
--control-plane.api-key env:OPENAI_TUNNEL_RUNTIME_KEY
--mcp.server-url "url=$OPENAI_MCP_TUNNEL_TARGET_URL,channel=main"
--health.listen-addr 127.0.0.1:8080
--log.level info --log.format json --log.file stdout
```

The client exposes loopback `/healthz`, `/readyz`, `/metrics`, `/api/status`, `/api/oauth`, and `/ui`. The admin UI remains loopback-only.

## Provisioning and startup

1. Create or reuse a tunnel associated with the intended Platform organization and ChatGPT Business workspace.
2. Give the runtime-key principal Tunnels **Read + Use**. Use a separate admin key only for tunnel management.
3. Populate `/etc/devspace/openai-mcp-tunnel.env` with the values above.
4. Run `./start-mcp.sh`, or run `scripts/install-services.sh` followed by the two check scripts.
5. Do not start a ChatGPT tool scan until both checks pass.

## Validation gates

`scripts/check-oauth-gateway.sh` verifies all of the following:

- the gateway process actually loaded the public base and resource values;
- both loopback-gateway and public well-known documents advertise the same public HTTPS URLs;
- PKCE S256 and DCR metadata are present;
- no protected-resource document, authorization-server document, or `WWW-Authenticate` challenge leaks a loopback URL.

`sudo scripts/check-tunnel.sh` verifies all of the following:

- `tunnel-client doctor` passes;
- the running service returns HTTP 200 from `/readyz`;
- the running tunnel ID and MCP target match the environment file;
- OAuth discovery selected the public authorization server;
- the control-plane poll-success metric is greater than zero.

A `/healthz` response alone is not enough. An OAuth-protected MCP startup probe may produce a readiness body such as `ready (mcp initialize requires auth: ...)`, but the HTTP status must still be 200.

## OAuth gateway behavior

The gateway rewrites DevSpace's loopback discovery values to the public OAuth origin for ChatGPT and the browser. It translates the public `resource` parameter back to DevSpace's internal resource for authorization and token operations. The Secure MCP Tunnel carries MCP traffic, while nginx exposes only the OAuth browser/discovery endpoints; public `/mcp` remains blocked.

If a runtime key is exposed in chat, logs, shell history, or a ticket, revoke it and create a replacement before relying on the tunnel again.
