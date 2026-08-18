# Operations runbook

## Status checks

```bash
sudo systemctl --no-pager status devspace.service
sudo systemctl --no-pager status devspace-oauth-gateway.service
sudo systemctl --no-pager status openai-mcp-tunnel.service
curl --compressed -fsS http://127.0.0.1:9191/healthz
curl --compressed -fsS http://127.0.0.1:9292/healthz
curl --compressed -fsS http://127.0.0.1:8080/healthz
curl --compressed -fsS http://127.0.0.1:8080/readyz
sudo scripts/check-oauth-gateway.sh
sudo scripts/check-tunnel.sh
```

`/healthz` confirms only that a process is live. Tunnel readiness requires HTTP 200 from `/readyz`. With DevSpace OAuth enabled, the body may explain that unauthenticated `initialize` requires auth; this is ready behavior, not a reason to accept HTTP 503. `check-tunnel.sh` additionally verifies the running tunnel ID, target URL, selected public authorization server, and a successful control-plane poll.

The direct DevSpace diagnostic remains useful but covers only the internal server:

```bash
DEVSPACE_AUTH_FILE=/path/to/.devspace/auth.json \
  DEVSPACE_TEST_ROOT=/srv/devspace-workspaces/project \
  scripts/check-devspace-mcp.sh
```

## Start, stop, and restart

Use the repository entrypoint to install updated units and scripts, restart the services, reload nginx, and run both checks:

```bash
./start-mcp.sh
```

For individual operations:

```bash
sudo systemctl restart devspace.service
sudo systemctl restart devspace-oauth-gateway.service
sudo systemctl restart openai-mcp-tunnel.service
sudo systemctl stop openai-mcp-tunnel.service
```

The gateway requires DevSpace; the tunnel unit requires both. Keep all three running while scanning or invoking the ChatGPT app.

## Configuration changes

Use `sudoedit /etc/devspace/devspace.env` for `DEVSPACE_ALLOWED_ROOTS` and `DEVSPACE_EXECUTABLE`. Use `sudoedit /etc/devspace/openai-mcp-tunnel.env` for OAuth and tunnel settings. Preserve mode `0640` on both files, and never put credentials in a unit, command history, Git file, chat, or ticket. After changing either file, run `./start-mcp.sh` and require both checks to pass before scanning tools.

`OAUTH_PUBLIC_BASE_URL` must use HTTPS. `MCP_PUBLIC_RESOURCE_URL` is the public resource identifier even though nginx blocks the public `/mcp` transport. DevSpace continues using its loopback resource internally.

If a runtime key is exposed, revoke it and create a replacement; restarting the old key is not remediation.

## Logs and retention

```bash
sudo journalctl -u devspace.service -f
sudo journalctl -u devspace-oauth-gateway.service -f
sudo journalctl -u openai-mcp-tunnel.service -f
```

DevSpace and tunnel-client use structured journald output. Raw HTTP logging remains disabled. Do not expose the tunnel-client admin UI remotely and do not copy bearer tokens into support bundles.

## Upgrade and rollback

Before an upgrade, record `devspace --version`, `tunnel-client --version`, service unit checksums, and the DevSpace configuration backup. Upgrade only after local diagnostics pass against the candidate version. Roll back by stopping the units, restoring the prior package or binary and configuration backup, running `systemctl daemon-reload`, restarting all three services, and re-running both gateway and tunnel checks before reconnecting ChatGPT.

`scripts/uninstall-services.sh` removes only the installed unit files and wrapper; it intentionally retains `/var/lib/devspace`, `/etc/devspace`, the workspace tree, and `/usr/local/bin/tunnel-client` for recovery.
