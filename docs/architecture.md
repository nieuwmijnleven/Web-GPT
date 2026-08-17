# Private DevSpace connection architecture

The deployment has two separate paths.

```text
MCP data plane
ChatGPT Business custom MCP app
  -> OpenAI Secure MCP Tunnel endpoint
  -> tunnel-client on this VPS (outbound HTTPS only)
  -> http://127.0.0.1:9292/mcp (OAuth-aware gateway)
  -> http://127.0.0.1:9191/mcp
  -> DevSpace running as devspace
  -> /home/ivenewjeans25/forum-for-democracy

OAuth browser and discovery plane
ChatGPT / the user's browser
  -> https://auth.forumfordemocracy.net
  -> nginx on this VPS
  -> http://127.0.0.1:9292
  -> http://127.0.0.1:9191
```

The public `/mcp` transport remains blocked by nginx. `https://auth.forumfordemocracy.net/mcp` is the canonical OAuth resource identifier, not a publicly exposed MCP endpoint.

## Gateway behavior

DevSpace keeps its internal OAuth identity at `http://127.0.0.1:9191`. The loopback gateway rewrites protected-resource metadata, authorization-server metadata, redirect locations, and the `WWW-Authenticate` resource-metadata URL to the configured public HTTPS origin. For `/authorize`, `/token`, and `/revoke`, it translates the public `resource` value back to DevSpace's loopback resource before proxying the request.

This split is required because the Secure MCP Tunnel carries MCP JSON-RPC but does not generically tunnel the user's browser to the authorization server. Public OAuth metadata must therefore contain only reachable HTTPS URLs, while DevSpace continues validating tokens against its internal resource value.

## Installed state

- OS: Ubuntu 24.04, Linux x86_64, systemd active.
- DevSpace: `@waishnav/devspace` 1.0.6, Node v24.19.0, installed under the existing npm global tree.
- DevSpace service account: dedicated system user `devspace`; it has ACL access only to the configured workspace tree and read/execute access to the installed DevSpace package.
- MCP bind: `127.0.0.1:9191/mcp`; `DEVSPACE_PUBLIC_BASE_URL` remains the loopback URL used by DevSpace internally.
- OAuth gateway bind: `127.0.0.1:9292`; `OAUTH_PUBLIC_BASE_URL` and `MCP_PUBLIC_RESOURCE_URL` are loaded from `/etc/devspace/openai-mcp-tunnel.env`.
- Public OAuth origin: `https://auth.forumfordemocracy.net`, reverse-proxied by nginx to the gateway. The public `/mcp` transport is denied.
- Tunnel client: official `openai/tunnel-client` Linux amd64 release v0.0.11 at `/usr/local/bin/tunnel-client`.
- Services: `devspace.service`, `devspace-oauth-gateway.service`, and `openai-mcp-tunnel.service` are ordered so the tunnel cannot start without the gateway.
- `DEVSPACE_TRUST_PROXY=1` is set in `devspace.service`; the shared tunnel environment file is not loaded into the DevSpace process, so the runtime key is not exposed to it.

## Configuration mapping

`OPENAI_MCP_TUNNEL_TARGET_URL` points tunnel-client at the gateway. `DEVSPACE_MCP_URL` is the fixed loopback upstream used by the gateway. `OAUTH_PUBLIC_BASE_URL` controls the public issuer and endpoint URLs, while `MCP_PUBLIC_RESOURCE_URL` controls the public resource identifier. The gateway must fail validation if either its local or public metadata leaks `127.0.0.1`, `localhost`, or `::1`.

The installed DevSpace supports `DEVSPACE_ALLOWED_ROOTS`, its owner-token file, and the fixed `/mcp` path. Request timeout and connection lifecycle settings belong to tunnel-client rather than being invented as unsupported DevSpace flags.

## Trust boundaries

DevSpace OAuth protects the loopback MCP endpoint. The gateway only forwards to the fixed DevSpace origin and does not accept a user-selected upstream. Nginx exposes only OAuth discovery and authorization endpoints. The tunnel client is the only intended remote MCP caller and initiates outbound HTTPS to OpenAI; no inbound tunnel port is required. The `devspace` account can execute shell commands within its OS permissions, so the allowed root and account ACL remain the primary containment controls.

## Official references used

- [OpenAI Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels)
- [Official tunnel-client release](https://github.com/openai/tunnel-client/releases/tag/v0.0.11)
- [OpenAI developer mode and MCP apps](https://help.openai.com/en/articles/12584461-developer-mode-and-mcp-apps-in-chatgpt)
- [DevSpace configuration reference](https://github.com/Waishnav/devspace/blob/main/docs/configuration.md)
- [DevSpace security model](https://github.com/Waishnav/devspace/blob/main/docs/security.md)
