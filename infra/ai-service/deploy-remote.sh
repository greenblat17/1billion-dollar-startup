#!/usr/bin/env bash
set -euo pipefail

APP=/opt/ai-service
mkdir -p "$APP"

cd "$APP"
BUILD=$(mktemp -d)
cp "$APP/Dockerfile" "$APP/main.py" "$APP/requirements.txt" "$BUILD/"
docker build -t ai-service:local "$BUILD"
rm -rf "$BUILD"
docker rm -f ai-service >/dev/null 2>&1 || true
docker run -d --name ai-service --restart unless-stopped \
  -p 127.0.0.1:8090:8090 \
  ai-service:local
