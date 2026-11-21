#!/usr/bin/env bash
# Source from a GitHub-hosted DEV operator job.
ssh_options=()

dev_ssh_config() {
  local component="$1" host user key alias key_path known_hosts
  case "$component" in
    ktor)
      host="${KTOR_HOST:?}"; user="${KTOR_USER:?}"; key="${KTOR_KEY:?}"; alias=cmp
      ;;
    ai)
      host="${AI_HOST:?}"; user="${AI_USER:?}"; key="${AI_KEY:?}"; alias=ai
      ;;
    *) return 2 ;;
  esac
  [[ "$host" =~ ^[a-zA-Z0-9][a-zA-Z0-9.:-]{0,252}$ && "$user" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] ||
    { echo 'Invalid DEV SSH target' >&2; return 2; }
  known_hosts="$RUNNER_TEMP/speaky-operator-known-hosts"
  (umask 077; printf '%s\n' "${DEV_KNOWN_HOSTS:?Configure the pinned DEV SSH host keys}" > "$known_hosts")
  key_path="$RUNNER_TEMP/speaky-operator-$alias-key"
  (umask 077; printf '%s\n' "$key" > "$key_path")
  ssh_target="$user@$host"
  ssh_options=(-i "$key_path" -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=yes
    -o ConnectTimeout=10 -o ServerAliveInterval=10 -o ServerAliveCountMax=2
    -o HostKeyAlias="speaky-dev-$alias" -o UserKnownHostsFile="$known_hosts")
}

dev_ssh() {
  ssh "${ssh_options[@]}" "$ssh_target" "$@"
}

dev_revision() {
  local component="$1" expected="$2"
  [[ "$expected" =~ ^[0-9a-f]{40}$ ]] || { echo 'Expected a full DEV source SHA' >&2; return 2; }
  dev_ssh_config "$component"
  dev_ssh "python3 - $component $expected" < infra/dev/require-revision.py
}
