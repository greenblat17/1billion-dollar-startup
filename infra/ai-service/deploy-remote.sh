#!/usr/bin/env bash
set -euo pipefail

APP=/opt/ai-service
NETWORK=speaking-coach

if [ ! -s "$APP/.env" ]; then
  echo "Missing or empty $APP/.env" >&2
  exit 1
fi
chmod 600 "$APP/.env"

cd "$APP"
BUILD=$(mktemp -d)
cp "$APP/Dockerfile" "$APP/requirements.txt" "$BUILD/"
cp -R "$APP/app" "$BUILD/app"
docker build -t ai-service:local "$BUILD"
rm -rf "$BUILD"
docker network create "$NETWORK" >/dev/null 2>&1 || true
docker rm -f ai-service >/dev/null 2>&1 || true
docker run -d --name ai-service --restart unless-stopped \
  --network "$NETWORK" \
  -p 127.0.0.1:8090:8090 \
  --env-file "$APP/.env" \
  ai-service:local
