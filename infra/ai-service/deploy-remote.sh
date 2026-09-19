#!/usr/bin/env bash
set -euo pipefail

APP=/opt/ai-service
NETWORK=speaking-coach
# shellcheck source=ai-service-port.sh
. "$APP/ai-service-port.sh"

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
PUBLISH_PORT="$(ai_service_port_from_env "$APP/.env")"
docker run -d --name ai-service --restart unless-stopped \
  --network "$NETWORK" \
  -p "${PUBLISH_PORT}:8090" \
  --env-file "$APP/.env" \
  ai-service:local

chmod 755 "$APP/restrict-8090.sh"
install -m 644 "$APP/restrict-8090.service" /etc/systemd/system/restrict-8090.service
systemctl daemon-reload
systemctl enable --now restrict-8090.service
bash "$APP/restrict-8090.sh"
