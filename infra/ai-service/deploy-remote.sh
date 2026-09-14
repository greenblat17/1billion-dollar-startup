#!/usr/bin/env bash
set -euo pipefail

APP=/opt/ai-service
: "${GROQ_API_KEY:?}"
: "${OPENAI_API_KEY:?}"

umask 077
{
  printf 'GROQ_API_KEY=%s\n' "$GROQ_API_KEY"
  printf 'OPENAI_API_KEY=%s\n' "$OPENAI_API_KEY"
} > "$APP/.env"
chmod 600 "$APP/.env"

cd "$APP"
BUILD=$(mktemp -d)
cp "$APP/Dockerfile" "$APP/requirements.txt" "$BUILD/"
cp -R "$APP/app" "$BUILD/app"
docker build -t ai-service:local "$BUILD"
rm -rf "$BUILD"
docker rm -f ai-service >/dev/null 2>&1 || true
docker run -d --name ai-service --restart unless-stopped \
  -p 127.0.0.1:8090:8090 \
  --env-file "$APP/.env" \
  ai-service:local
