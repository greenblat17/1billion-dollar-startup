#!/usr/bin/env bash
set -euo pipefail

APP=/opt/telegram-service
IMAGE=telegram-service:local
NAME=telegram-service
JAR=telegram-service-all.jar

if [ ! -s "$APP/.env" ]; then
  echo "Missing or empty $APP/.env" >&2
  exit 1
fi
chmod 600 "$APP/.env"

if ! grep -qE '^SERVER_PORT=[0-9]+' "$APP/.env"; then
  echo "SERVER_PORT missing or invalid in $APP/.env" >&2
  exit 1
fi

if [ ! -f "$APP/tls.crt" ] || [ ! -f "$APP/tls.key" ]; then
  if [ -f /opt/speaking-coach/tls.crt ] && [ -f /opt/speaking-coach/tls.key ]; then
    cp -a /opt/speaking-coach/tls.crt /opt/speaking-coach/tls.key "$APP/"
  fi
fi

if [ ! -f "$APP/tls.crt" ] || [ ! -f "$APP/tls.key" ]; then
  echo "Missing $APP/tls.crt or $APP/tls.key. Put them on the VPS before deploy." >&2
  exit 1
fi

cd "$APP"
BUILD=$(mktemp -d)
cp "$APP/$JAR" "$APP/Dockerfile.runtime" "$BUILD/"
docker build -f Dockerfile.runtime -t "$IMAGE" "$BUILD"
rm -rf "$BUILD"
docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" --restart unless-stopped \
  --network host \
  --env-file "$APP/.env" \
  -v "$APP/tls.crt:$APP/tls.crt:ro" \
  -v "$APP/tls.key:$APP/tls.key:ro" \
  "$IMAGE"
