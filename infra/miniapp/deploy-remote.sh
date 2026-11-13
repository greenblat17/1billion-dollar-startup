#!/usr/bin/env bash
set -euo pipefail
APP=${MINI_APP_RELEASE:-/opt/mini-app}
cd "$APP"
docker build -t mini-app:next .
# Start and check the candidate before touching the running frontend.
docker rm -f mini-app-candidate >/dev/null 2>&1 || true
docker run -d --name mini-app-candidate --network speaking-coach mini-app:next
trap 'docker rm -f mini-app-candidate >/dev/null 2>&1 || true' EXIT
for attempt in $(seq 1 20); do
  if docker exec mini-app-candidate wget -q -O /dev/null http://127.0.0.1:8080/miniapp; then break; fi
  sleep 1
done
docker exec mini-app-candidate wget -q -O /dev/null http://127.0.0.1:8080/miniapp/assets/app.js
previous=$(docker inspect --format '{{.Image}}' mini-app 2>/dev/null || true)
docker rm -f mini-app >/dev/null 2>&1 || true
if ! docker run -d --name mini-app --restart unless-stopped \
  --log-driver local --log-opt max-size=10m --log-opt max-file=3 \
  --network speaking-coach mini-app:next; then
  docker rm -f mini-app >/dev/null 2>&1 || true
  if [ -n "$previous" ]; then
    docker run -d --name mini-app --restart unless-stopped --network speaking-coach "$previous"
  fi
  exit 1
fi
docker exec mini-app cat /usr/share/nginx/html/version.json
