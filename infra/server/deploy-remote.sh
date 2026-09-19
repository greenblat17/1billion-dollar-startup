#!/usr/bin/env bash
set -euo pipefail

APP=/opt/speaking-coach

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
  echo "Missing $APP/tls.crt or $APP/tls.key. Put them on the VPS before deploy." >&2
  exit 1
fi

if [ -f "$APP/deploy-postgres.sh" ]; then
  bash "$APP/deploy-postgres.sh"
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
