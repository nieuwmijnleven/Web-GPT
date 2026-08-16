# ChatGPT Business handoff

This VPS cannot access the user’s Business workspace session, so this is a deterministic handoff rather than an end-to-end success claim.

## Admin path

Use ChatGPT on the web as a Business workspace admin/owner. The current official path is:

1. Open **Workspace settings → Apps → Create**. Business admins/owners enable developer mode while creating the custom app; the user setting is **Settings → Apps → Advanced settings → Developer mode** when that entry is shown.
2. Choose a custom MCP app and the **Tunnel** connection. Select the tunnel listed for the workspace, or enter the provisioned `OPENAI_TUNNEL_ID` when the UI offers that field.
3. Use OAuth when prompted. The loopback gateway exposes protected-resource and authorization-server metadata through the selected tunnel and proxies DCR, PKCE, access tokens, and refresh tokens to DevSpace. Approve the DevSpace owner-password page only for this intended client.
4. Click **Scan Tools**. Confirm that all eight tools in `docs/devspace-capabilities.md` appear, that annotations are retained, and that no actions were silently omitted. If the tunnel is absent, verify the workspace association and the app creator’s `Tunnels Read + Use` permission.
5. Click **Create**. The app should appear under **Workspace settings → Apps → Drafts** (or under **Settings → Apps → Enabled Apps** for the creator with a Dev label).

## Validation conversation

With the draft app selected, use a disposable project below the configured allowed root and test in order:

- open the disposable workspace and list/read a file;
- write and edit a disposable file, then read it back;
- run `pwd` or another harmless command;
- request a cancellation only for a known bounded command and record whether ChatGPT reports cancellation;
- restart `devspace.service`, wait for tunnel readiness, and repeat a read;
- review confirmation prompts for `write`, `edit`, and `bash` before any real project action.

Record the app’s scanned catalog, callable actions, blocked actions, confirmation behavior, error responses, and reconnection result. A successful scan alone is not proof that every tool works.

## Publish only after review

Only an admin/owner should publish from **Workspace settings → Apps → Drafts → Publish** after reviewing the write/open-world warnings and action permissions. Business apps use a reviewed/frozen tool snapshot; later DevSpace catalog changes are not automatically active. Re-scan/refresh and recreate or republish according to the current UI before relying on changes. Do not publish automatically from the VPS.

Official references: [Developer mode and MCP apps](https://help.openai.com/en/articles/12584461-developer-mode-and-mcp-apps-in-chatgpt) and [Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels).
