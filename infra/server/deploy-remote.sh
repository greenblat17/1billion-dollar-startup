#!/usr/bin/env bash
set -euo pipefail

APP=/opt/speaking-coach
: "${TELEGRAM_BOT_TOKEN:?}"
: "${TELEGRAM_WEBHOOK_SECRET:?}"
: "${TELEGRAM_WEBHOOK_URL:?}"
: "${AI_SERVICE_BASE_URL:?}"

umask 077
{
  printf 'TELEGRAM_BOT_TOKEN=%s\n' "$TELEGRAM_BOT_TOKEN"
  printf 'TELEGRAM_WEBHOOK_SECRET=%s\n' "$TELEGRAM_WEBHOOK_SECRET"
  printf 'TELEGRAM_WEBHOOK_URL=%s\n' "$TELEGRAM_WEBHOOK_URL"
  printf 'AI_SERVICE_BASE_URL=%s\n' "$AI_SERVICE_BASE_URL"
  printf 'SERVER_PORT=443\n'
  printf 'TLS_CERT_PATH=%s/tls.crt\n' "$APP"
  printf 'TLS_KEY_PATH=%s/tls.key\n' "$APP"
} > "$APP/.env"
chmod 600 "$APP/.env"

if [ ! -f "$APP/tls.crt" ] || [ ! -f "$APP/tls.key" ]; then
  echo "Missing $APP/tls.crt or $APP/tls.key. Put them on the VPS before deploy." >&2
  exit 1
fi

cd "$APP"
BUILD=$(mktemp -d)
cp "$APP/server-all.jar" "$APP/Dockerfile.runtime" "$BUILD/"
docker build -f Dockerfile.runtime -t speaking-coach:local "$BUILD"
rm -rf "$BUILD"
docker rm -f speaking-coach >/dev/null 2>&1 || true
docker run -d --name speaking-coach --restart unless-stopped \
  --network host \
  --env-file "$APP/.env" \
  -v "$APP/tls.crt:$APP/tls.crt:ro" \
  -v "$APP/tls.key:$APP/tls.key:ro" \
  speaking-coach:local
