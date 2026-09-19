#!/usr/bin/env bash
set -euo pipefail

# Create-if-missing Postgres on the Ktor host. Listen on loopback only.
# Never docker rm postgres: volume speaking-coach-postgres keeps users.

APP=/opt/speaking-coach
ENV_FILE="$APP/.env"
NAME=postgres

if [ ! -s "$ENV_FILE" ]; then
  echo "Missing or empty $ENV_FILE" >&2
  exit 1
fi

env_val() {
  local key="$1"
  local line value
  line=$(grep -E "^${key}=" "$ENV_FILE" | tail -n 1 || true)
  if [ -z "$line" ]; then
    printf '%s\n' ""
    return 0
  fi
  value="${line#*=}"
  value="${value%%#*}"
  value="${value//[$'\t\r\n ']/}"
  printf '%s\n' "$value"
}

PASSWORD="$(env_val POSTGRES_PASSWORD)"
if [ -z "$PASSWORD" ]; then
  echo "POSTGRES_PASSWORD unset; skip postgres."
  exit 0
fi

USER_NAME="$(env_val POSTGRES_USER)"
if [ -z "$USER_NAME" ]; then
  USER_NAME=speaking
fi
DB_NAME="$(env_val POSTGRES_DB)"
if [ -z "$DB_NAME" ]; then
  DB_NAME=speaking_coach
fi

docker volume create speaking-coach-postgres >/dev/null 2>&1 || true

if docker inspect "$NAME" >/dev/null 2>&1; then
  docker start "$NAME" >/dev/null
  echo "postgres already present; left running."
  exit 0
fi

docker run -d --name "$NAME" --restart unless-stopped \
  -p 127.0.0.1:5432:5432 \
  -e POSTGRES_USER="$USER_NAME" \
  -e POSTGRES_PASSWORD="$PASSWORD" \
  -e POSTGRES_DB="$DB_NAME" \
  -v speaking-coach-postgres:/var/lib/postgresql/data \
  postgres:16-alpine
