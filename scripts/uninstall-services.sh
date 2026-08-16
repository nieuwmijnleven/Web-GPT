#!/usr/bin/env bash
set -euo pipefail

sudo systemctl disable --now openai-mcp-tunnel.service 2>/dev/null || true
sudo systemctl disable --now devspace.service 2>/dev/null || true
sudo rm -f /etc/systemd/system/openai-mcp-tunnel.service /etc/systemd/system/devspace.service /usr/local/libexec/run-openai-mcp-tunnel
sudo systemctl daemon-reload
printf '%s\n' "Service units removed. /var/lib/devspace, /etc/devspace, workspace data, and tunnel-client binary were retained for rollback and review."
