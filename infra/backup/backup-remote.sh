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
operation=backup
stage=arguments
reason_code=command_failed
postgres_saved=false
audit_audio_saved=false
redis_saved=false

log_result() {
  local status="$1" timestamp outcome message
  timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ) || timestamp=unknown
  if [ "$status" -eq 0 ]; then
    outcome=completed
    message='Backup saved all available artifacts.'
    reason_code=none
  elif [ "$operation" = restore ]; then
    outcome=rejected
    message='The script refused the restore request.'
  elif [[ $postgres_saved == true || $audit_audio_saved == true || $redis_saved == true ]]; then
    outcome=partial
    message='Backup stopped after saving some artifacts.'
  else
    outcome=failed
    message='Backup stopped before saving an artifact.'
  fi
  printf 'operation_event timestamp=%s operation=%s stage=%s outcome=%s reason_code=%s exit_status=%s postgres_saved=%s audit_audio_saved=%s redis_saved=%s message="%s"\n' \
    "$timestamp" "$operation" "$stage" "$outcome" "$reason_code" "$status" \
    "$postgres_saved" "$audit_audio_saved" "$redis_saved" "$message" >&2 || true
}
trap 'log_result "$?"' EXIT

usage() {
  reason_code=invalid_arguments
  echo "usage: backup-remote.sh DEST_DIR" >&2
  echo "       backup-remote.sh restore --volume NAME" >&2
  exit 2
}

refuse_live_volume_name() {
  case "$1" in
    "$LIVE_POSTGRES_VOLUME"|"$LIVE_REDIS_VOLUME")
      reason_code=live_volume_name
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
        reason_code=destination_inside_live_volume
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
  stage=postgres
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
    reason_code=postgres_dump_failed
    rm -f "$partial"
    echo "pg_dump failed" >&2
    exit 1
  fi
  if [ ! -s "$partial" ]; then
    reason_code=postgres_dump_empty
    rm -f "$partial"
    echo "pg_dump wrote an empty file" >&2
    exit 1
  fi
  stage=postgres_validate
  docker cp "$partial" "$POSTGRES_CONTAINER":/tmp/speaky-postgres-backup.dump
  if ! docker exec "$POSTGRES_CONTAINER" pg_restore -l /tmp/speaky-postgres-backup.dump >/dev/null; then
    reason_code=postgres_dump_unreadable
    docker exec "$POSTGRES_CONTAINER" rm -f /tmp/speaky-postgres-backup.dump
    rm -f "$partial"
    echo "pg_restore -l could not read the dump; live database was not changed" >&2
    exit 1
  fi
  docker exec "$POSTGRES_CONTAINER" rm -f /tmp/speaky-postgres-backup.dump
  stage=postgres_publish
  mv "$partial" "$dest/postgres.dump"
  postgres_saved=true
  echo "wrote $dest/postgres.dump"
}

backup_audit_audio() {
  stage=audit_audio
  local dest="$1"
  local partial
  partial="$(mktemp "$dest/audit-audio.tar.gz.partial.XXXXXX")"
  tar -C "$APP" -czf "$partial" audit-audio
  if [ ! -s "$partial" ]; then
    reason_code=audit_audio_archive_empty
    rm -f "$partial"
    echo "audit-audio archive is empty" >&2
    exit 1
  fi
  stage=audit_audio_validate
  gzip -t "$partial"
  stage=audit_audio_publish
  mv "$partial" "$dest/audit-audio.tar.gz"
  audit_audio_saved=true
  echo "wrote $dest/audit-audio.tar.gz"
}

backup_redis() {
  stage=redis
  local dest="$1"
  if ! docker exec "$REDIS_CONTAINER" redis-cli PING | grep -q '^PONG$'; then
    reason_code=redis_ping_failed
    echo "redis did not answer PING; live dump.rdb was not replaced" >&2
    exit 1
  fi
  local partial
  partial="$(mktemp "$dest/redis.rdb.partial.XXXXXX")"
  # --rdb writes a new file inside the container. /tmp is not the data volume,
  # so the live dump.rdb under speaking-coach-redis is left in place.
  if ! docker exec "$REDIS_CONTAINER" redis-cli --rdb /tmp/speaky-redis-backup.rdb; then
    reason_code=redis_dump_failed
    rm -f "$partial"
    echo "redis-cli --rdb failed; live dump.rdb was not replaced" >&2
    exit 1
  fi
  docker cp "$REDIS_CONTAINER":/tmp/speaky-redis-backup.rdb "$partial"
  docker exec "$REDIS_CONTAINER" rm -f /tmp/speaky-redis-backup.rdb
  stage=redis_validate
  local magic
  magic="$(head -c 5 "$partial" || true)"
  if [ "$magic" != "REDIS" ] || [ ! -s "$partial" ]; then
    reason_code=redis_dump_invalid
    rm -f "$partial"
    echo "redis backup is not an RDB file" >&2
    exit 1
  fi
  stage=redis_publish
  mv "$partial" "$dest/redis.rdb"
  redis_saved=true
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
  stage=volume_guard
  refuse_live_volume_name "$volume"
  reason_code=restore_unsupported
  echo "refusing to restore from this script; use an empty throwaway container with its own volume" >&2
  exit 1
}

cmd_backup() {
  local dest="${1:-}"
  if [ -z "$dest" ] || [ $# -ne 1 ]; then
    usage
  fi
  stage=destination
  refuse_live_volume_name "$(basename "$dest")"
  mkdir -p "$dest"
  dest="$(cd "$dest" && pwd)"
  refuse_dest_on_live_volume "$dest"
  if docker inspect "$POSTGRES_CONTAINER" >/dev/null 2>&1; then
    backup_postgres "$dest"
  fi
  if [ -d "$APP/audit-audio" ]; then
    backup_audit_audio "$dest"
  fi
  if docker inspect "$REDIS_CONTAINER" >/dev/null 2>&1; then
    backup_redis "$dest"
  fi
  if [[ $postgres_saved == false && $audit_audio_saved == false && $redis_saved == false ]]; then
    stage=discover_sources
    reason_code=no_backup_sources
    echo "nothing to back up: no postgres container, audit-audio dir, or redis container" >&2
    exit 1
  fi
  stage=complete
}

if [ $# -lt 1 ]; then
  usage
fi

case "$1" in
  restore)
    operation=restore
    shift
    cmd_restore "$@"
    ;;
  *)
    cmd_backup "$@"
    ;;
esac
