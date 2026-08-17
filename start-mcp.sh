#/bin/bash

export OAUTH_PUBLIC_BASE_URL=https://auth.forumfordemocracy.net
export MCP_PUBLIC_RESOURCE_URL=https://auth.forumfordemocracy.net/mcp

export DEVSPACE_MCP_URL=http://127.0.0.1:9191/mcp
export OAUTH_GATEWAY_LISTEN_ADDR=127.0.0.1:9292

export OPENAI_MCP_TUNNEL_TARGET_URL=http://127.0.0.1:9292/mcp

sudo systemctl stop openai-mcp-tunnel.service
sudo systemctl stop devspace-oauth-gateway.service
sudo systemctl stop devspace.service

cd ~/shorts-monitor/oauth-proxy
docker compose down

sudo systemctl start devspace.service
sudo systemctl start devspace-oauth-gateway.service

cd ~/shorts-monitor/oauth-proxy
docker compose up -d

sudo systemctl start openai-mcp-tunnel.service
