# ChatGPT Business handoff

This VPS cannot access the user's Business workspace session, so this is a deterministic handoff rather than an end-to-end success claim.

## Required preflight

Before opening the ChatGPT app builder, run:

```bash
sudo scripts/check-oauth-gateway.sh
sudo scripts/check-tunnel.sh
```

Do not continue unless both pass. This proves that public metadata contains only reachable HTTPS URLs, `/readyz` is 200, the running tunnel configuration matches the environment file, OAuth discovery selected the public authorization server, and control-plane polling is active.

## Admin path

Use ChatGPT on the web as a Business workspace admin or owner.

1. Open **Workspace settings → Apps → Create**. Enable developer mode when required by the current UI.
2. Choose a custom MCP app and the **Tunnel** connection. Select the exact tunnel configured in `/etc/devspace/openai-mcp-tunnel.env`.
3. Use OAuth when prompted. ChatGPT and the user's browser reach `https://auth.forumfordemocracy.net`; nginx forwards OAuth requests to the loopback gateway. The gateway rewrites DevSpace's loopback discovery values to public HTTPS URLs and translates the public resource identifier back to DevSpace's internal resource for authorization and token requests.
4. Run **Scan Tools**. Confirm that all eight tools in `docs/devspace-capabilities.md` appear and no actions were silently omitted.
5. Create the draft app only after the scan shows the expected catalog.

While scanning, follow the service logs:

```bash
sudo journalctl -f \
  -u openai-mcp-tunnel.service \
  -u devspace-oauth-gateway.service \
  -u devspace.service
```

A normal scan should produce `initialize`, `notifications/initialized`, and `tools/list` activity on the main MCP path. If the preflight passes but none of those requests appear, record the exact scan time and redacted logs for a product-side tool-verification escalation.

## Validation conversation

With the draft app selected, use a disposable project below the configured allowed root and test in order:

- open the disposable workspace and list or read a file;
- write and edit a disposable file, then read it back;
- run `pwd` or another harmless command;
- restart `devspace.service`, wait for both checks to pass, and repeat a read;
- review confirmation prompts for `write`, `edit`, and `bash` before any real project action.

Record the scanned catalog, callable actions, blocked actions, confirmation behavior, error responses, and reconnection result. A successful scan alone is not proof that every tool works.

## Publish only after review

Only an admin or owner should publish after reviewing write and open-world warnings and action permissions. Re-scan or recreate the draft when the DevSpace tool catalog changes. Do not publish automatically from the VPS.

If a runtime key is exposed in chat, logs, shell history, or a ticket, revoke it and create a replacement before continuing.

Official references: [Developer mode and MCP apps](https://help.openai.com/en/articles/12584461-developer-mode-and-mcp-apps-in-chatgpt) and [Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels).
