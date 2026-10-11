#!/usr/bin/env bash
# Online backup for Speaky. Run on the host as root. Do not call this from
# deploy-remote.sh: deploy must not delete old data, and this script does not
# either.
#
# CMP host (container postgres, dir /opt/speaking-coach/audit-audio):
#   bash infra/backup/backup-remote.sh /var/backups/speaky/$(date +%F)
#   writes postgres.dump (pg_dump -Fc) and audit-audio.tar.gz
#
# AI host (container redis):
#   bash infra/backup/backup-remote.sh /var/backups/speaky/$(date +%F)
#   writes redis.rdb next to the live data, not over the volume's dump.rdb
#
# Containers stay up. The script never docker rm and never docker stop.
# `restore` refuses volumes speaking-coach-postgres and speaking-coach-redis.
# It does not restore into any volume: use an empty throwaway container.
set -euo pipefail

LIVE_POSTGRES_VOLUME=speaking-coach-postgres
LIVE_REDIS_VOLUME=speaking-coach-redis
APP=/opt/speaking-coach
POSTGRES_CONTAINER=postgres
REDIS_CONTAINER=redis

usage() {
  echo "usage: backup-remote.sh DEST_DIR" >&2
  echo "       backup-remote.sh restore --volume NAME" >&2
  exit 2
}

refuse_live_volume_name() {
  case "$1" in
    "$LIVE_POSTGRES_VOLUME"|"$LIVE_REDIS_VOLUME")
      echo "refusing to restore into live volume $1" >&2
      exit 1
      ;;
  esac
}

refuse_dest_on_live_volume() {
  local dest="$1"
  local volume mount
  for volume in "$LIVE_POSTGRES_VOLUME" "$LIVE_REDIS_VOLUME"; do
    mount="$(docker volume inspect -f '{{.Mountpoint}}' "$volume" 2>/dev/null || true)"
    if [ -z "$mount" ] || [ "$mount" = "/" ]; then
      continue
    fi
    case "$dest" in
      "$mount"|"$mount"/*)
        echo "refusing to write a backup inside live volume $volume" >&2
        exit 1
        ;;
    esac
  done
}

env_val() {
  local key="$1"
  local file="$2"
  local line value
  line="$(grep -E "^${key}=" "$file" | tail -n 1 || true)"
  if [ -z "$line" ]; then
    printf '%s\n' ""
    return 0
  fi
  value="${line#*=}"
  value="${value%%#*}"
  value="${value//[$'\t\r\n ']/}"
  printf '%s\n' "$value"
}

backup_postgres() {
  local dest="$1"
  local env_file="$APP/.env"
  local user_name db_name partial
  user_name="speaking"
  db_name="speaking_coach"
  if [ -s "$env_file" ]; then
    local from_env
    from_env="$(env_val POSTGRES_USER "$env_file")"
    if [ -n "$from_env" ]; then
      user_name="$from_env"
    fi
    from_env="$(env_val POSTGRES_DB "$env_file")"
    if [ -n "$from_env" ]; then
      db_name="$from_env"
    fi
  fi
  partial="$(mktemp "$dest/postgres.dump.partial.XXXXXX")"
  # Local socket inside the official image is trust, so the password is not passed.
  if ! docker exec "$POSTGRES_CONTAINER" \
    pg_dump -U "$user_name" -d "$db_name" -Fc >"$partial"; then
    rm -f "$partial"
    echo "pg_dump failed" >&2
    exit 1
  fi
  if [ ! -s "$partial" ]; then
    rm -f "$partial"
    echo "pg_dump wrote an empty file" >&2
    exit 1
  fi
  docker cp "$partial" "$POSTGRES_CONTAINER":/tmp/speaky-postgres-backup.dump
  if ! docker exec "$POSTGRES_CONTAINER" pg_restore -l /tmp/speaky-postgres-backup.dump >/dev/null; then
    docker exec "$POSTGRES_CONTAINER" rm -f /tmp/speaky-postgres-backup.dump
    rm -f "$partial"
    echo "pg_restore -l could not read the dump; live database was not changed" >&2
    exit 1
  fi
  docker exec "$POSTGRES_CONTAINER" rm -f /tmp/speaky-postgres-backup.dump
  mv "$partial" "$dest/postgres.dump"
  echo "wrote $dest/postgres.dump"
}

backup_audit_audio() {
  local dest="$1"
  local partial
  partial="$(mktemp "$dest/audit-audio.tar.gz.partial.XXXXXX")"
  tar -C "$APP" -czf "$partial" audit-audio
  if [ ! -s "$partial" ]; then
    rm -f "$partial"
    echo "audit-audio archive is empty" >&2
    exit 1
  fi
  gzip -t "$partial"
  mv "$partial" "$dest/audit-audio.tar.gz"
  echo "wrote $dest/audit-audio.tar.gz"
}

backup_redis() {
  local dest="$1"
  if ! docker exec "$REDIS_CONTAINER" redis-cli PING | grep -q '^PONG$'; then
    echo "redis did not answer PING; live dump.rdb was not replaced" >&2
    exit 1
  fi
  local partial
  partial="$(mktemp "$dest/redis.rdb.partial.XXXXXX")"
  # --rdb writes a new file inside the container. /tmp is not the data volume,
  # so the live dump.rdb under speaking-coach-redis is left in place.
  if ! docker exec "$REDIS_CONTAINER" redis-cli --rdb /tmp/speaky-redis-backup.rdb; then
    rm -f "$partial"
    echo "redis-cli --rdb failed; live dump.rdb was not replaced" >&2
    exit 1
  fi
  docker cp "$REDIS_CONTAINER":/tmp/speaky-redis-backup.rdb "$partial"
  docker exec "$REDIS_CONTAINER" rm -f /tmp/speaky-redis-backup.rdb
  local magic
  magic="$(head -c 5 "$partial" || true)"
  if [ "$magic" != "REDIS" ] || [ ! -s "$partial" ]; then
    rm -f "$partial"
    echo "redis backup is not an RDB file" >&2
    exit 1
  fi
  mv "$partial" "$dest/redis.rdb"
  echo "wrote $dest/redis.rdb"
}

cmd_restore() {
  local volume=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --volume)
        if [ $# -lt 2 ]; then
          usage
        fi
        volume="$2"
        shift 2
        ;;
      *)
        usage
        ;;
    esac
  done
  if [ -z "$volume" ]; then
    usage
  fi
  refuse_live_volume_name "$volume"
  echo "refusing to restore from this script; use an empty throwaway container with its own volume" >&2
  exit 1
}

cmd_backup() {
  local dest="${1:-}"
  if [ -z "$dest" ] || [ $# -ne 1 ]; then
    usage
  fi
  refuse_live_volume_name "$(basename "$dest")"
  mkdir -p "$dest"
  dest="$(cd "$dest" && pwd)"
  refuse_dest_on_live_volume "$dest"
  local did=0
  if docker inspect "$POSTGRES_CONTAINER" >/dev/null 2>&1; then
    backup_postgres "$dest"
    did=1
  fi
  if [ -d "$APP/audit-audio" ]; then
    backup_audit_audio "$dest"
    did=1
  fi
  if docker inspect "$REDIS_CONTAINER" >/dev/null 2>&1; then
    backup_redis "$dest"
    did=1
  fi
  if [ "$did" -ne 1 ]; then
    echo "nothing to back up: no postgres container, audit-audio dir, or redis container" >&2
    exit 1
  fi
}

if [ $# -lt 1 ]; then
  usage
fi

case "$1" in
  restore)
    shift
    cmd_restore "$@"
    ;;
  *)
    cmd_backup "$@"
    ;;
esac
