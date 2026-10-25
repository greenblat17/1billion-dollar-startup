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
if ! docker build -t ai-service:next "$BUILD" 2>&1 | tee "$BUILD/build.log"; then
  if ! grep -q '429 Too Many Requests' "$BUILD/build.log"; then
    echo "AI image build failed for a reason other than Docker Hub rate limiting" >&2
    exit 1
  fi
  if ! docker image inspect ai-service:local >/dev/null 2>&1 ||
     ! docker run --rm --entrypoint cat ai-service:local /app/requirements.txt | cmp -s - "$BUILD/requirements.txt"; then
    echo "Cannot reuse the previous AI image: image missing or dependencies changed" >&2
    exit 1
  fi
  printf 'FROM ai-service:local\nRUN rm -rf /app/app\nCOPY app /app/app\n' > "$BUILD/Dockerfile.cached"
  docker build -f "$BUILD/Dockerfile.cached" -t ai-service:next "$BUILD"
fi
docker image tag ai-service:next ai-service:local
rm -rf "$BUILD"
docker network create "$NETWORK" >/dev/null 2>&1 || true
docker rm -f ai-service >/dev/null 2>&1 || true
mkdir -p "$APP/logs"
cat > /etc/logrotate.d/ai-service <<'EOF'
/opt/ai-service/logs/app.log {
    daily
    rotate 7
    compress
    delaycompress
    copytruncate
    dateext
    missingok
    notifempty
}
EOF
PUBLISH_PORT="$(ai_service_port_from_env "$APP/.env")"
docker run -d --name ai-service --restart unless-stopped \
  --log-driver local --log-opt max-size=10m --log-opt max-file=3 \
  --network "$NETWORK" \
  -p "${PUBLISH_PORT}:8090" \
  --env-file "$APP/.env" \
  -e LOG_FILE=/var/log/ai-service/app.log \
  -v "$APP/logs:/var/log/ai-service" \
  ai-service:local

# Freeze this one-time campaign cohort on its first deployment. Later deploys
# leave the Redis snapshot unchanged, so new users are never added to it.
docker exec ai-service python -m app.legacy_onboarding_campaign snapshot

chmod 755 "$APP/restrict-8090.sh"
install -m 644 "$APP/restrict-8090.service" /etc/systemd/system/restrict-8090.service
systemctl daemon-reload
systemctl enable --now restrict-8090.service
bash "$APP/restrict-8090.sh"
if [ -f "$APP/deploy-alloy.sh" ]; then
  bash "$APP/deploy-alloy.sh" || echo "AI log shipping was not updated" >&2
fi
