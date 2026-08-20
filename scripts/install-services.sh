#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
env_file=/etc/devspace/openai-mcp-tunnel.env
devspace_env=/etc/devspace/devspace.env
devspace_install_prefix=${DEVSPACE_INSTALL_PREFIX:-$HOME/.npm-global}
node_version=${NODE_VERSION:-24.19.0}
tunnel_client_version=${TUNNEL_CLIENT_VERSION:-0.0.11}

if ((EUID == 0)); then
  printf '%s\n' "Run this script as the login user, not with sudo; it will request sudo only for system-level setup." >&2
  exit 1
fi

resolve_devspace_executable() {
  local executable=${DEVSPACE_EXECUTABLE:-}
  local npm_prefix

  if [[ -z "$executable" ]]; then
    executable=$(command -v devspace 2>/dev/null || true)
  fi
  if [[ -z "$executable" && -x "$devspace_install_prefix/bin/devspace" ]]; then
    executable="$devspace_install_prefix/bin/devspace"
  fi
  if [[ -z "$executable" ]] && command -v npm >/dev/null 2>&1; then
    npm_prefix=$(npm prefix -g 2>/dev/null || true)
    if [[ -n "$npm_prefix" && -x "$npm_prefix/bin/devspace" ]]; then
      executable="$npm_prefix/bin/devspace"
    fi
  fi

  [[ -n "$executable" && -x "$executable" ]] || {
    printf '%s\n' "Unable to locate the DevSpace executable. Set DEVSPACE_EXECUTABLE and rerun." >&2
    return 1
  }
  printf '%s\n' "$executable"
}

ensure_devspace_account() {
  if ! getent group devspace >/dev/null 2>&1; then
    sudo groupadd --system devspace
  fi
  if ! id -u devspace >/dev/null 2>&1; then
    sudo useradd --system --gid devspace --home-dir /var/lib/devspace --create-home --shell /usr/sbin/nologin devspace
  fi
  sudo install -d -o devspace -g devspace -m 0700 /var/lib/devspace
}

install_system_dependencies() {
  local missing=()

  command -v apt-get >/dev/null 2>&1 || {
    printf '%s\n' "apt-get is required for automatic VPS provisioning." >&2
    return 1
  }

  [[ -r /etc/ssl/certs/ca-certificates.crt ]] || missing+=(ca-certificates)
  command -v curl >/dev/null 2>&1 || missing+=(curl)
  command -v xz >/dev/null 2>&1 || missing+=(xz-utils)
  command -v unzip >/dev/null 2>&1 || missing+=(unzip)
  command -v setfacl >/dev/null 2>&1 || missing+=(acl)
  command -v docker >/dev/null 2>&1 || missing+=(docker.io)
  if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
    missing+=(docker-compose-v2)
  fi

  if ((${#missing[@]} > 0)); then
    sudo apt-get update -qq
    sudo apt-get install -y -qq "${missing[@]}"
  fi
}

node_runtime_ready() {
  command -v node >/dev/null 2>&1 \
    && command -v npm >/dev/null 2>&1 \
    && node -e 'const [major, minor] = process.versions.node.split(".").map(Number); process.exit(((major === 22 && minor >= 19) || (major > 22 && major < 27)) ? 0 : 1)'
}

install_node_if_needed() {
  local arch
  local archive
  local base_url
  local tmp

  if node_runtime_ready; then
    return 0
  fi

  case "$(uname -m)" in
    x86_64|amd64) arch=x64 ;;
    aarch64|arm64) arch=arm64 ;;
    *)
      printf '%s\n' "Unsupported architecture for Node.js automatic install: $(uname -m)" >&2
      return 1
      ;;
  esac

  archive="node-v${node_version}-linux-${arch}.tar.xz"
  base_url="https://nodejs.org/dist/v${node_version}"
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' RETURN
  curl --compressed --fail --silent --show-error --location "$base_url/$archive" --output "$tmp/$archive"
  curl --compressed --fail --silent --show-error --location "$base_url/SHASUMS256.txt" --output "$tmp/SHASUMS256.txt"
  (cd "$tmp" && grep -F "  $archive" SHASUMS256.txt | sha256sum -c -)
  sudo tar -xJf "$tmp/$archive" -C /usr/local/lib
  sudo ln -sfn "/usr/local/lib/node-v${node_version}-linux-${arch}/bin/node" /usr/local/bin/node
  sudo ln -sfn "/usr/local/lib/node-v${node_version}-linux-${arch}/bin/npm" /usr/local/bin/npm
  sudo ln -sfn "/usr/local/lib/node-v${node_version}-linux-${arch}/bin/npx" /usr/local/bin/npx
  trap - RETURN
  rm -rf "$tmp"

  node_runtime_ready || {
    printf '%s\n' "Installed Node.js does not satisfy DevSpace requirement >=22.19 <27." >&2
    return 1
  }
}

install_tunnel_client_if_missing() {
  local arch
  local archive
  local base_url
  local tmp

  if command -v tunnel-client >/dev/null 2>&1; then
    return 0
  fi

  case "$(uname -m)" in
    x86_64|amd64) arch=amd64 ;;
    aarch64|arm64) arch=arm64 ;;
    *)
      printf '%s\n' "Unsupported architecture for tunnel-client automatic install: $(uname -m)" >&2
      return 1
      ;;
  esac

  archive="tunnel-client-v${tunnel_client_version}-linux-${arch}.zip"
  base_url="https://persistent.oaistatic.com/tunnel-client/v${tunnel_client_version}"
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' RETURN
  curl --compressed --fail --silent --show-error --location "$base_url/$archive" --output "$tmp/$archive"
  curl --compressed --fail --silent --show-error --location "$base_url/SHA256SUMS.txt" --output "$tmp/SHA256SUMS.txt"
  (cd "$tmp" && grep -F "  $archive" SHA256SUMS.txt | sha256sum -c -)
  unzip -q "$tmp/$archive" -d "$tmp/extracted"
  sudo install -o root -g root -m 0755 "$tmp/extracted/tunnel-client" /usr/local/bin/tunnel-client
  trap - RETURN
  rm -rf "$tmp"
}

install_devspace_if_missing() {
  if resolve_devspace_executable >/dev/null 2>&1; then
    return 0
  fi

  mkdir -p "$devspace_install_prefix"
  npm install -g --prefix "$devspace_install_prefix" --no-audit --no-fund @waishnav/devspace@1.0.6
  resolve_devspace_executable >/dev/null
}

install_devspace_environment() {
  local allowed_roots=${DEVSPACE_ALLOWED_ROOTS:-$repo_dir}
  local executable
  local tmp

  if [[ -e "$devspace_env" ]]; then
    sudo chown root:devspace "$devspace_env"
    sudo chmod 0640 "$devspace_env"
  else
    executable=$(resolve_devspace_executable)
    tmp=$(mktemp)
    trap 'rm -f "$tmp"' RETURN
    printf 'DEVSPACE_ALLOWED_ROOTS=%s\nDEVSPACE_EXECUTABLE=%s\n' "$allowed_roots" "$executable" >"$tmp"
    sudo install -o root -g devspace -m 0640 "$tmp" "$devspace_env"
    trap - RETURN
    rm -f "$tmp"
    printf '%s\n' "Created $devspace_env; adjust DEVSPACE_ALLOWED_ROOTS there to expose additional project roots."
  fi

  sudo grep -Eq '^DEVSPACE_ALLOWED_ROOTS=[^[:space:]#]+$' "$devspace_env" || {
    printf '%s\n' "$devspace_env must define DEVSPACE_ALLOWED_ROOTS" >&2
    return 1
  }
  sudo grep -Eq '^DEVSPACE_EXECUTABLE=[^[:space:]#]+$' "$devspace_env" || {
    printf '%s\n' "$devspace_env must define DEVSPACE_EXECUTABLE" >&2
    return 1
  }
}

grant_devspace_access() {
  local allowed_roots
  local root
  local roots=()

  sudo setfacl -m u:devspace:--x "$HOME"
  if [[ -d "$devspace_install_prefix" ]]; then
    sudo setfacl -RP -m u:devspace:rX "$devspace_install_prefix"
    sudo find "$devspace_install_prefix" -type d -exec setfacl -m d:u:devspace:r-x {} +
  fi

  allowed_roots=$(sudo awk -F= '$1 == "DEVSPACE_ALLOWED_ROOTS" { sub(/^[^=]*=/, ""); print; exit }' "$devspace_env")
  IFS=',' read -r -a roots <<<"$allowed_roots"
  for root in "${roots[@]}"; do
    [[ -d "$root" ]] || {
      printf '%s\n' "DEVSPACE_ALLOWED_ROOTS entry does not exist: $root" >&2
      return 1
    }
    sudo setfacl -RP -m u:devspace:rwX "$root"
    sudo find "$root" -type d -exec setfacl -m d:u:devspace:rwx {} +
  done
}

has_assignment() {
  local name=$1
  sudo grep -Eq "^[[:space:]]*${name}=[^[:space:]#]+[[:space:]]*$" "$env_file"
}

has_https_assignment() {
  local name=$1
  sudo grep -Eq "^[[:space:]]*${name}=https://[^[:space:]#]+[[:space:]]*$" "$env_file"
}

wait_http() {
  local label=$1
  local url=$2
  local attempts=${3:-30}
  local i

  for ((i = 1; i <= attempts; i++)); do
    if curl --fail --silent --show-error --max-time 2 "$url" >/dev/null 2>&1; then
      printf '%s\n' "$label: ready"
      return 0
    fi
    sleep 1
  done

  printf '%s\n' "$label: not ready after ${attempts}s ($url)" >&2
  return 1
}

ensure_devspace_account
install_system_dependencies
install_node_if_needed
install_devspace_if_missing
install_tunnel_client_if_missing
sudo systemctl enable --now docker.service

sudo install -d -o root -g root -m 0755 /etc/devspace/tunnel-client /usr/local/libexec
sudo install -o root -g root -m 0644 "$repo_dir/systemd/devspace.service" /etc/systemd/system/devspace.service
sudo install -o root -g root -m 0644 "$repo_dir/systemd/devspace-oauth-gateway.service" /etc/systemd/system/devspace-oauth-gateway.service
sudo install -o root -g root -m 0644 "$repo_dir/systemd/openai-mcp-tunnel.service" /etc/systemd/system/openai-mcp-tunnel.service
sudo install -o root -g root -m 0644 "$repo_dir/config/openai-mcp-tunnel.yaml" /etc/devspace/tunnel-client/devspace.yaml
sudo install -o root -g root -m 0755 "$repo_dir/scripts/run-openai-mcp-tunnel" /usr/local/libexec/run-openai-mcp-tunnel
sudo install -o root -g root -m 0755 "$repo_dir/scripts/run-devspace" /usr/local/libexec/run-devspace
sudo install -o root -g root -m 0755 "$repo_dir/scripts/devspace-oauth-gateway.mjs" /usr/local/libexec/devspace-oauth-gateway
install_devspace_environment
grant_devspace_access
if [[ ! -e "$env_file" ]]; then
  tunnel_env_tmp=$(mktemp)
  trap 'rm -f "$tunnel_env_tmp"' EXIT
  cat >"$tunnel_env_tmp" <<'EOF'
# Public OAuth origin and canonical MCP resource identifier.
OAUTH_PUBLIC_BASE_URL=
MCP_PUBLIC_RESOURCE_URL=
LETSENCRYPT_EMAIL=

# Private DevSpace MCP endpoint and loopback OAuth gateway.
DEVSPACE_MCP_URL=http://127.0.0.1:9191/mcp
OAUTH_GATEWAY_LISTEN_ADDR=127.0.0.1:9292
OPENAI_MCP_TUNNEL_TARGET_URL=http://127.0.0.1:9292/mcp

# OpenAI Secure MCP Tunnel credentials and metadata.
OPENAI_TUNNEL_ID=
OPENAI_TUNNEL_RUNTIME_KEY=
OPENAI_ORGANIZATION_ID=
CHATGPT_WORKSPACE_ID=
OPENAI_TUNNEL_PROFILE=devspace

# Tunnel client runtime settings.
TUNNEL_LOG_LEVEL=info
TUNNEL_HEALTH_LISTEN_ADDR=127.0.0.1:8080
EOF
  sudo install -o root -g devspace -m 0640 "$tunnel_env_tmp" "$env_file"
  rm -f "$tunnel_env_tmp"
  trap - EXIT
else
  sudo chown root:devspace "$env_file"
  sudo chmod 0640 "$env_file"
fi

sudo systemctl daemon-reload
sudo systemctl enable devspace.service
sudo systemctl restart devspace.service
wait_http "DevSpace" "http://127.0.0.1:9191/healthz"

if has_https_assignment OAUTH_PUBLIC_BASE_URL; then
  sudo systemctl enable devspace-oauth-gateway.service
  sudo systemctl restart devspace-oauth-gateway.service
  wait_http "OAuth gateway" "http://127.0.0.1:9292/healthz"
else
  printf '%s\n' "DevSpace enabled; OAuth gateway not started because OAUTH_PUBLIC_BASE_URL is absent or not HTTPS."
fi

if has_https_assignment OAUTH_PUBLIC_BASE_URL \
  && has_assignment OPENAI_TUNNEL_ID \
  && has_assignment OPENAI_TUNNEL_RUNTIME_KEY; then
  sudo systemctl enable openai-mcp-tunnel.service
  sudo systemctl restart openai-mcp-tunnel.service
else
  printf '%s\n' "DevSpace enabled; tunnel unit not started because public OAuth or runtime credentials are incomplete."
fi

sudo systemctl --no-pager --full status devspace.service
