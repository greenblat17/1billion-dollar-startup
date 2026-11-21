#!/usr/bin/env bash
set -euo pipefail

APP=/opt/ai-service
NETWORK=speaking-coach
stage=configuration
reason_code=command_failed

log_deploy() {
  local timestamp
  timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ) || timestamp=unknown
  printf 'operation_event timestamp=%s service=ai operation=deploy stage=%s outcome=%s reason_code=%s exit_status=%s message="%s"\n' \
    "$timestamp" "$stage" "$1" "$2" "$3" "$4" >&2 || true
}

log_result() {
  local status="$1"
  if [ "$status" -eq 0 ]; then
    log_deploy completed none "$status" 'The AI deploy script completed.'
  else
    log_deploy failed "$reason_code" "$status" 'The AI deploy script stopped.'
  fi
}
trap 'log_result "$?"' EXIT

# shellcheck source=ai-service-port.sh
. "$APP/ai-service-port.sh"

if [ ! -s "$APP/.env" ]; then
  reason_code=env_missing
  echo "Missing or empty $APP/.env" >&2
  exit 1
fi
if ! grep -q '^FOUNDATION_BACKEND_URL=.' "$APP/.env"; then
  reason_code=foundation_backend_url_missing
  echo "FOUNDATION_BACKEND_URL is required for Speaky; legacy runtime is not a fallback" >&2
  exit 1
fi
chmod 600 "$APP/.env"

# Local DEV S3 is on an isolated network. Validate it before replacing AI.
STORAGE_NETWORK_ARGS=()
if grep -qx 'TRIAL_S3_ENDPOINT=http://speaky-storage:8333' "$APP/.env"; then
  if ! docker network inspect speaky-storage >/dev/null 2>&1; then
    stage=storage_network
    reason_code=storage_network_missing
    echo "Local trial storage network is missing; existing AI container preserved" >&2
    exit 1
  fi
  STORAGE_NETWORK_ARGS=(--network speaky-storage)
fi

stage=prepare_build
cd "$APP"
BUILD=$(mktemp -d)
cp "$APP/Dockerfile" "$APP/requirements.txt" "$BUILD/"
cp -R "$APP/app" "$BUILD/app"
stage=image_build
if ! docker build -t ai-service:next "$BUILD" 2>&1 | tee "$BUILD/build.log"; then
  if ! grep -q '429 Too Many Requests' "$BUILD/build.log"; then
    reason_code=image_build_failed
    echo "AI image build failed for a reason other than Docker Hub rate limiting" >&2
    exit 1
  fi
  if ! docker image inspect ai-service:local >/dev/null 2>&1 ||
     ! docker run --rm --entrypoint cat ai-service:local /app/requirements.txt | cmp -s - "$BUILD/requirements.txt"; then
    reason_code=cached_image_unusable
    echo "Cannot reuse the previous AI image: image missing or dependencies changed" >&2
    exit 1
  fi
  stage=cached_image_build
  printf 'FROM ai-service:local\nRUN rm -rf /app/app\nCOPY app /app/app\nCMD ["uvicorn", "app.speaky_main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8090"]\n' > "$BUILD/Dockerfile.cached"
  docker build -f "$BUILD/Dockerfile.cached" -t ai-service:next "$BUILD"
  log_deploy recovered registry_rate_limited 0 'The AI image was built from the local base image.'
fi
stage=image_tag
docker image tag ai-service:next ai-service:local
rm -rf "$BUILD"
stage=replace_container
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
BACKEND_TLS_ARGS=()
if [ -s "$APP/foundation-backend-ca.pem" ]; then
  BACKEND_TLS_ARGS=(-v "$APP/foundation-backend-ca.pem:/app/foundation-backend-ca.pem:ro"
    -e FOUNDATION_BACKEND_CA_FILE=/app/foundation-backend-ca.pem)
fi
docker run -d --name ai-service --restart unless-stopped \
  --log-driver local --log-opt max-size=10m --log-opt max-file=3 \
  --network "$NETWORK" \
  "${STORAGE_NETWORK_ARGS[@]}" \
  -p "${PUBLISH_PORT}:8090" \
  --env-file "$APP/.env" \
  "${BACKEND_TLS_ARGS[@]}" \
  -e LOG_FILE=/var/log/ai-service/app.log \
  -v "$APP/logs:/var/log/ai-service" \
  ai-service:local

# Legacy campaign workers and Redis are intentionally absent from the Speaky runtime.

stage=firewall_setup
chmod 755 "$APP/restrict-8090.sh"
install -m 644 "$APP/restrict-8090.service" /etc/systemd/system/restrict-8090.service
systemctl daemon-reload
systemctl enable --now restrict-8090.service
bash "$APP/restrict-8090.sh"
if [ -f "$APP/deploy-alloy.sh" ]; then
  stage=log_shipping_update
  bash "$APP/deploy-alloy.sh" ||
    log_deploy degraded log_shipping_update_failed "$?" 'The AI deploy script could not update log shipping.'
fi
stage=complete
