#!/usr/bin/env bash
set -euo pipefail
APP=${MINI_APP_RELEASE:-/opt/mini-app}
stage=prepare_build
reason_code=command_failed
candidate_started=false

log_deploy() {
  local timestamp
  timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ) || timestamp=unknown
  printf 'operation_event timestamp=%s service=miniapp operation=deploy stage=%s outcome=%s reason_code=%s exit_status=%s message="%s"\n' \
    "$timestamp" "$stage" "$1" "$2" "$3" "$4" >&2 || true
}

finish_deploy() {
  local status="$1"
  if [ "$candidate_started" = true ]; then
    docker rm -f mini-app-candidate >/dev/null 2>&1 || true
  fi
  if [ "$status" -eq 0 ]; then
    log_deploy completed none "$status" 'The Mini App deploy script completed.'
  else
    log_deploy failed "$reason_code" "$status" 'The Mini App deploy script stopped.'
  fi
}
trap 'finish_deploy "$?"' EXIT

cd "$APP"
stage=image_build
docker build -t mini-app:next .
# Start and check the candidate before touching the running frontend.
stage=candidate_start
docker rm -f mini-app-candidate >/dev/null 2>&1 || true
docker run -d --name mini-app-candidate --network speaking-coach mini-app:next
candidate_started=true
stage=candidate_check
html_ready=false
for attempt in $(seq 1 20); do
  if docker exec mini-app-candidate wget -q -O /dev/null http://127.0.0.1:8080/miniapp; then html_ready=true; break; fi
  sleep 1
done
if [ "$html_ready" != true ]; then
  reason_code=candidate_html_unavailable
  exit 1
fi
docker exec mini-app-candidate wget -q -O /dev/null http://127.0.0.1:8080/miniapp/assets/app.js
stage=replace_container
previous=$(docker inspect --format '{{.Image}}' mini-app 2>/dev/null || true)
docker rm -f mini-app >/dev/null 2>&1 || true
if ! docker run -d --name mini-app --restart unless-stopped \
  --log-driver local --log-opt max-size=10m --log-opt max-file=3 \
  --network speaking-coach mini-app:next; then
  reason_code=container_start_failed
  docker rm -f mini-app >/dev/null 2>&1 || true
  if [ -n "$previous" ]; then
    stage=rollback
    reason_code=previous_container_restart_failed
    docker run -d --name mini-app --restart unless-stopped --network speaking-coach "$previous"
    log_deploy recovered container_start_failed 0 'The previous Mini App container was started.'
    stage=replace_container
    reason_code=container_start_failed
  else
    reason_code=container_start_failed_no_previous_image
  fi
  exit 1
fi
stage=read_version
docker exec mini-app cat /usr/share/nginx/html/version.json
stage=complete
