#!/usr/bin/env bash
#
# oauth-proxy 최초 부트스트랩
#   사용법: ./bootstrap.sh you@example.com
#
# 흐름:
#   1. HTTP-only Nginx 시작 (HTTPS 설정 임시 비활성화)
#   2. Certbot HTTP-01 인증서 발급
#   3. HTTPS 설정 적용 (HTTP-only 설정 비활성화)
#   4. nginx -t 검증
#   5. Nginx reload
set -euo pipefail
cd "$(dirname "$0")"

EMAIL="${1:-}"
if [ -z "$EMAIL" ]; then
  echo "사용법: $0 you@example.com" >&2
  exit 1
fi

HTTPS_CONF="nginx/conf.d/auth.forumfordemocracy.net.conf"
HTTP_CONF="nginx/conf.d/auth.forumfordemocracy.net.http.conf"
STASH="nginx/conf.d/.inactive"
CERT="certbot/conf/live/auth.forumfordemocracy.net/fullchain.pem"

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
  -d auth.forumfordemocracy.net \
  --email "$EMAIL" \
  --agree-tos \
  --no-eff-email

# 3) HTTPS 설정 적용
mv "$STASH" "$HTTPS_CONF"
mv "$HTTP_CONF" "$STASH"

# 4) 설정 검증
docker compose exec -T nginx nginx -t

# 5) reload
docker compose exec -T nginx nginx -s reload

echo "완료: https://auth.forumfordemocracy.net"
