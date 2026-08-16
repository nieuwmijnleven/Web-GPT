# Operations runbook

## Status checks

```bash
sudo systemctl --no-pager status devspace.service
curl -fsS http://127.0.0.1:9191/healthz
DEVSPACE_AUTH_FILE=/home/ivenewjeans25/.devspace/auth.json \
  DEVSPACE_TEST_ROOT=/home/ivenewjeans25/forum-for-democracy \
  scripts/check-devspace-mcp.sh
sudo journalctl -u devspace.service -n 100 --no-pager
sudo systemctl --no-pager status openai-mcp-tunnel.service
curl -fsS http://127.0.0.1:8080/healthz
curl -fsS http://127.0.0.1:8080/readyz
```

`readyz` is the meaningful tunnel check: it must show control-plane connectivity, local MCP reachability, authentication/discovery, and catalog readiness. A running process alone is not readiness.

## Start, stop, and restart

```bash
sudo systemctl restart devspace.service
sudo systemctl restart openai-mcp-tunnel.service
sudo systemctl stop openai-mcp-tunnel.service
```

The tunnel unit requires DevSpace and restarts after a transient failure. Keep it running while scanning or invoking the ChatGPT app.

## Configuration changes

Use `sudoedit /etc/devspace/openai-mcp-tunnel.env`, preserve mode 0640, and never put credentials in a unit, command history, Git file, or ticket. After changing a tunnel ID or runtime key:

```bash
sudo systemctl restart openai-mcp-tunnel.service
scripts/check-tunnel.sh
```

Rotate the DevSpace owner token by creating a reviewed backup, updating the service account’s auth file mode 0600, and restarting DevSpace; then reauthorize the ChatGPT app.

## Logs and retention

```bash
sudo journalctl -u devspace.service -f
sudo journalctl -u openai-mcp-tunnel.service -f
```

DevSpace uses JSON journald records for requests, session lifecycle, and tool success/failure. Shell command previews and raw HTTP logging remain disabled. The tunnel client uses JSON logs and its loopback admin UI; do not expose `/ui` remotely.

## Upgrade and rollback

Before an upgrade, record `devspace --version`, `tunnel-client --version`, service unit checksums, and the DevSpace config backup. Upgrade only after local diagnostics pass against the candidate version. Roll back by stopping the units, restoring the prior package/binary and config backup, running `systemctl daemon-reload`, restarting DevSpace, and re-running the local diagnostic before reconnecting ChatGPT.

`scripts/uninstall-services.sh` removes only the installed unit files and wrapper; it intentionally retains `/var/lib/devspace`, `/etc/devspace`, the workspace tree, and `/usr/local/bin/tunnel-client` for recovery.
