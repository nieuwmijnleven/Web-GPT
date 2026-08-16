# Private DevSpace connection architecture

The implemented design is:

```text
ChatGPT Business custom MCP app
  -> OpenAI Secure MCP Tunnel endpoint
  -> tunnel-client on this VPS (outbound HTTPS only)
  -> http://127.0.0.1:9292/mcp (OAuth-aware gateway)
  -> http://127.0.0.1:9191/mcp
  -> DevSpace running as devspace
  -> /home/ivenewjeans25/forum-for-democracy
```

The gateway is loopback-only; it is not a public reverse proxy or a second MCP listener. It returns OAuth metadata with the OpenAI tunnel URL, proxies authorization/token/registration requests to DevSpace after translating the public resource URL to DevSpace's loopback resource, and forwards the resulting Bearer token to `/mcp`. It also rewrites DevSpace's loopback `WWW-Authenticate` metadata URL so the OAuth client can continue discovery through the tunnel.

## Installed state

- OS: Ubuntu 24.04, Linux x86_64, systemd active.
- DevSpace: `@waishnav/devspace` 1.0.6, Node v24.19.0, installed under the existing npm global tree.
- npm registry metadata for 1.0.6 reports integrity `sha512-lLwUip5Wv1mwpEmAbpms7bourW5g0a0US1PDHCD2CITgCK6DnMTh5++6z8ODIEY+T30oxoTQlxdH4T+VkWlbNA==`; the installed package resolves to 1.0.6.
- DevSpace service account: dedicated system user `devspace`; it has ACL access only to the configured workspace tree and read/execute access to the installed DevSpace package.
- MCP bind: `127.0.0.1:9191/mcp`; the service public base URL remains `http://127.0.0.1:9191` for DevSpace's internal OAuth resource checks.
- OAuth gateway bind: `127.0.0.1:9292/mcp`; its public base is derived from the configured OpenAI tunnel ID unless `OAUTH_GATEWAY_PUBLIC_BASE_URL` is set.
- Tunnel client: official `openai/tunnel-client` Linux amd64 release v0.0.11. The release archive SHA-256 `29adfe5c1399dfb9fda9383f230c324355912f50dc36e2e416b1f1322317b3c4` was verified before extraction; the installed binary is `/usr/local/bin/tunnel-client`.
- Services: `devspace.service`, `devspace-oauth-gateway.service`, and `openai-mcp-tunnel.service` are installed and ordered so the tunnel cannot start without the gateway.

The original user configuration was backed up before service-account migration as `/home/ivenewjeans25/.devspace/config.json.bak.20260816T051600Z`. The owner token was copied without being displayed.

## Configuration mapping

The requested project names are mapped only where the installed DevSpace supports them: `DEVSPACE_HOST` → `HOST`, `DEVSPACE_PORT` → `PORT`, `DEVSPACE_ALLOWED_ROOTS` → `DEVSPACE_ALLOWED_ROOTS`, `DEVSPACE_PUBLIC_BASE_URL` → `DEVSPACE_PUBLIC_BASE_URL`, and `DEVSPACE_AUTH_TOKEN` → `DEVSPACE_OAUTH_OWNER_TOKEN`/the owner token in `auth.json`. The MCP path is fixed by the installed server at `/mcp`; `DEVSPACE_AUTH_HEADER` is not a supported DevSpace setting because this version uses OAuth bearer tokens. `OPENAI_MCP_TUNNEL_TARGET_URL` points tunnel-client at the gateway, while the gateway uses `DEVSPACE_MCP_URL` for the loopback DevSpace target. No `DEVSPACE_REQUEST_TIMEOUT` or `DEVSPACE_SESSION_TIMEOUT` setting exists in the installed configuration parser: the HTTP client timeout is tunnel-client-owned, and DevSpace retains idle MCP sessions for 24 hours. Unsupported project names were not invented as flags.

## Trust boundaries

DevSpace OAuth protects the loopback MCP endpoint. The loopback gateway is the only tunnel target and forwards only to the fixed DevSpace origin; it does not accept a user-selected upstream or write request bodies to disk. The tunnel client is the only intended remote caller and initiates outbound HTTPS to OpenAI; no inbound tunnel port is required. The `devspace` account can execute arbitrary shell commands within its OS permissions, so the allowed root and account ACL are the principal containment controls. ChatGPT confirmation and workspace app policy are additional controls, not a substitute for least privilege.

## Official references used

- [OpenAI Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels)
- [Official tunnel-client release](https://github.com/openai/tunnel-client/releases/tag/v0.0.11)
- [OpenAI developer mode and MCP apps](https://help.openai.com/en/articles/12584461-developer-mode-and-mcp-apps-in-chatgpt)
- [DevSpace configuration reference](https://github.com/Waishnav/devspace/blob/main/docs/configuration.md)
- [DevSpace security model](https://github.com/Waishnav/devspace/blob/main/docs/security.md)
