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
mkdir -p "$APP/logs"
mkdir -p "$APP/audit-audio"
cat > /etc/logrotate.d/speaking-coach <<'EOF'
/opt/speaking-coach/logs/app.log {
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
docker rm -f speaking-coach >/dev/null 2>&1 || true
# Older app.log versions included Telegram text. Clear legacy files once before
# the first audit-enabled container starts; new logs omit message content.
if [ ! -f "$APP/logs/.audit-retention-initialized" ]; then
  find "$APP/logs" -maxdepth 1 -type f -name 'app.log*' -delete
  touch "$APP/logs/.audit-retention-initialized"
fi
docker run -d --name speaking-coach --restart unless-stopped \
  --log-driver local --log-opt max-size=10m --log-opt max-file=3 \
  --network host \
  --env-file "$APP/.env" \
  -v "$APP/tls.crt:$APP/tls.crt:ro" \
  -v "$APP/tls.key:$APP/tls.key:ro" \
  -v "$APP/logs:/opt/speaking-coach/logs" \
  -v "$APP/audit-audio:/opt/speaking-coach/audit-audio" \
  speaking-coach:local

if [ -f "$APP/monitoring/deploy-remote.sh" ]; then
  bash "$APP/monitoring/deploy-remote.sh" || echo "Monitoring stack was not updated" >&2
fi
