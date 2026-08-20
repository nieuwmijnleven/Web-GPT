#!/usr/bin/env bash
#
# oauth-proxy 최초 부트스트랩
#   사용법: ./bootstrap.sh [you@example.com]
#
# 흐름:
#   1. HTTP-only Nginx 시작 (HTTPS 설정 임시 비활성화)
#   2. Certbot HTTP-01 인증서 발급
#   3. HTTPS 설정 적용 (HTTP-only 설정 비활성화)
#   4. nginx -t 검증
#   5. Nginx reload
set -euo pipefail
cd "$(dirname "$0")"

ENV_FILE="${DEVSPACE_TUNNEL_ENV_FILE:-/etc/devspace/openai-mcp-tunnel.env}"
EMAIL="${1:-${LETSENCRYPT_EMAIL:-}}"
if [ -z "$EMAIL" ] && sudo test -r "$ENV_FILE"; then
  EMAIL="$(sudo grep -m1 '^LETSENCRYPT_EMAIL=' "$ENV_FILE" | cut -d= -f2- || true)"
fi
if [ -z "$EMAIL" ]; then
  EMAIL="$(git config --get user.email 2>/dev/null || true)"
fi
if [ -z "$EMAIL" ]; then
  echo "Let's Encrypt contact email is required. Pass it as the first argument or set LETSENCRYPT_EMAIL in $ENV_FILE." >&2
  exit 1
fi

PUBLIC_BASE="${OAUTH_PUBLIC_BASE_URL:-}"
if [ -z "$PUBLIC_BASE" ] && sudo test -r "$ENV_FILE"; then
  PUBLIC_BASE="$(sudo grep -m1 '^OAUTH_PUBLIC_BASE_URL=' "$ENV_FILE" | cut -d= -f2- || true)"
fi
if [[ "$PUBLIC_BASE" != https://* ]]; then
  echo "OAUTH_PUBLIC_BASE_URL must be configured as an https:// URL" >&2
  exit 1
fi
OAUTH_DOMAIN="${PUBLIC_BASE#https://}"
OAUTH_DOMAIN="${OAUTH_DOMAIN%%/*}"
OAUTH_DOMAIN="${OAUTH_DOMAIN%%:*}"
if [ -z "$OAUTH_DOMAIN" ]; then
  echo "Unable to derive OAuth domain from OAUTH_PUBLIC_BASE_URL" >&2
  exit 1
fi

HTTPS_CONF="nginx/conf.d/oauth.conf"
HTTP_CONF="nginx/conf.d/oauth.http.conf"
STASH="nginx/conf.d/.inactive"
CERT_NAME="devspace-oauth"
CERT="certbot/conf/live/${CERT_NAME}/fullchain.pem"

if [ -f "$CERT" ]; then
  # 이미 인증서 존재 → HTTPS 설정이 stash 상태면 복원만 수행
  if [ -f "$STASH" ] && [ ! -f "$HTTPS_CONF" ]; then
    mv "$STASH" "$HTTPS_CONF"
    docker compose exec -T nginx nginx -t
    docker compose exec -T nginx nginx -s reload
  fi
  echo "이미 인증서가 존재합니다. 갱신: docker compose run --rm certbot renew"
  exit 0
fi

# 1) HTTP-only Nginx 시작
mv "$HTTPS_CONF" "$STASH"
docker compose up -d nginx
sleep 1
docker compose exec -T nginx nginx -t

# 2) Certbot HTTP-01 인증서 발급
docker compose run --rm certbot certonly \
  --webroot \
  --webroot-path=/var/www/certbot \
  -d "$OAUTH_DOMAIN" \
  --cert-name "$CERT_NAME" \
  --email "$EMAIL" \
  --agree-tos \
  --no-eff-email \
  --debug-challenges

# 3) HTTPS 설정 적용
mv "$STASH" "$HTTPS_CONF"
mv "$HTTP_CONF" "$STASH"

# 4) 설정 검증
docker compose exec -T nginx nginx -t

# 5) reload
docker compose exec -T nginx nginx -s reload

echo "완료: $PUBLIC_BASE"
