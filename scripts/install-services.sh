#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
env_file=/etc/devspace/openai-mcp-tunnel.env
devspace_env=/etc/devspace/devspace.env
devspace_install_prefix=${DEVSPACE_INSTALL_PREFIX:-$HOME/.npm-global}

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

install_npm_if_missing() {
  if command -v npm >/dev/null 2>&1; then
    return 0
  fi

  command -v apt-get >/dev/null 2>&1 || {
    printf '%s\n' "npm is not installed and apt-get is unavailable. Install npm and rerun." >&2
    return 1
  }

  sudo apt-get update -qq
  sudo apt-get install -y -qq npm
}

install_acl_if_missing() {
  if command -v setfacl >/dev/null 2>&1; then
    return 0
  fi

  command -v apt-get >/dev/null 2>&1 || {
    printf '%s\n' "setfacl is not installed and apt-get is unavailable. Install acl and rerun." >&2
    return 1
  }

  sudo apt-get update -qq
  sudo apt-get install -y -qq acl
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
install_npm_if_missing
install_acl_if_missing
install_devspace_if_missing

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
  sudo install -o root -g devspace -m 0640 /dev/null "$env_file"
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
