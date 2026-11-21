#!/usr/bin/env bash
set -euo pipefail

APP=/opt/speaking-coach
stage=configuration
reason_code=command_failed

log_deploy() {
  local timestamp
  timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ) || timestamp=unknown
  printf 'operation_event timestamp=%s service=backend operation=deploy stage=%s outcome=%s reason_code=%s exit_status=%s message="%s"\n' \
    "$timestamp" "$stage" "$1" "$2" "$3" "$4" >&2 || true
}

log_result() {
  local status="$1"
  if [ "$status" -eq 0 ]; then
    log_deploy completed none "$status" 'The backend deploy script completed.'
  else
    log_deploy failed "$reason_code" "$status" 'The backend deploy script stopped.'
  fi
}
trap 'log_result "$?"' EXIT

if [ ! -s "$APP/.env" ]; then
  reason_code=env_missing
  echo "Missing or empty $APP/.env" >&2
  exit 1
fi
chmod 600 "$APP/.env"

if ! grep -qE '^SERVER_PORT=[0-9]+' "$APP/.env"; then
  reason_code=server_port_invalid
  echo "SERVER_PORT missing or invalid in $APP/.env" >&2
  exit 1
fi

if [ ! -f "$APP/tls.crt" ] || [ ! -f "$APP/tls.key" ]; then
  reason_code=tls_files_missing
  echo "Missing $APP/tls.crt or $APP/tls.key. Put them on the VPS before deploy." >&2
  exit 1
fi

if [ -f "$APP/deploy-postgres.sh" ]; then
  stage=postgres_deploy
  bash "$APP/deploy-postgres.sh"
fi

stage=prepare_build
cd "$APP"
BUILD=$(mktemp -d)
cp "$APP/server-all.jar" "$APP/Dockerfile.runtime" "$BUILD/"
stage=image_build
if ! docker build -f Dockerfile.runtime -t speaking-coach:next "$BUILD" 2>&1 | tee "$BUILD/build.log"; then
  if ! grep -q '429 Too Many Requests' "$BUILD/build.log"; then
    reason_code=image_build_failed
    echo "CMP image build failed for a reason other than Docker Hub rate limiting" >&2
    exit 1
  fi
  if ! docker image inspect speaking-coach:local >/dev/null 2>&1; then
    reason_code=cached_image_missing
    echo "Cannot reuse the previous CMP image: local image missing" >&2
    exit 1
  fi
  stage=cached_image_build
  printf 'FROM speaking-coach:local\nCOPY server-all.jar /app/server-all.jar\n' > "$BUILD/Dockerfile.cached"
  docker build -f "$BUILD/Dockerfile.cached" -t speaking-coach:next "$BUILD"
  log_deploy recovered registry_rate_limited 0 'The backend image was built from the local base image.'
fi
stage=image_tag
docker image tag speaking-coach:next speaking-coach:local
rm -rf "$BUILD"
stage=host_setup
mkdir -p "$APP/logs"
mkdir -p "$APP/audit-audio"
mkdir -p "$APP/analytics"
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
stage=replace_container
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
  -v "$APP/analytics:/opt/speaking-coach/analytics:ro" \
  speaking-coach:local

if [ -f "$APP/monitoring/deploy-remote.sh" ]; then
  stage=monitoring_update
  bash "$APP/monitoring/deploy-remote.sh" ||
    log_deploy degraded monitoring_update_failed "$?" 'The backend deploy script could not update monitoring.'
fi
stage=complete
