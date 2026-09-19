#!/usr/bin/env bash
set -euo pipefail

# Limit the host-published FastAPI port (AI_SERVICE_PORT, default 8090)
# to loopback plus IPv4 sources in 8090.allow.
# Docker published ports skip UFW; DOCKER-USER is the hook that actually filters them.

APP=/opt/ai-service
ALLOW_FILE="$APP/8090.allow"
CHAIN=SPEAKY_8090
CHAIN6=SPEAKY_8090_V6
# shellcheck source=ai-service-port.sh
. "$APP/ai-service-port.sh"
PUBLISH_PORT="$(ai_service_port_from_env "$APP/.env")"

is_ipv4() {
  local value="$1"
  [[ "$value" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}(/([0-9]|[12][0-9]|3[0-2]))?$ ]]
}

ensure_chain() {
  local bin="$1"
  local chain="$2"
  "$bin" -n -L "$chain" >/dev/null 2>&1 || "$bin" -N "$chain"
  "$bin" -F "$chain"
}

jump_if_missing() {
  local bin="$1"
  local table_chain="$2"
  local spec="$3"
  # shellcheck disable=SC2086
  "$bin" -C $table_chain $spec >/dev/null 2>&1 || "$bin" -I $table_chain $spec
}

ensure_chain iptables "$CHAIN"
iptables -A "$CHAIN" -s 127.0.0.0/8 -j RETURN
if [ -f "$ALLOW_FILE" ]; then
  while IFS= read -r raw || [ -n "$raw" ]; do
    line="${raw%%#*}"
    line="${line//[[:space:]]/}"
    [ -z "$line" ] && continue
    if ! is_ipv4 "$line"; then
      echo "Skip invalid 8090.allow entry: $raw" >&2
      continue
    fi
    iptables -A "$CHAIN" -s "$line" -j RETURN
  done < "$ALLOW_FILE"
fi
iptables -A "$CHAIN" -j DROP

jump_if_missing iptables INPUT "-p tcp --dport $PUBLISH_PORT -j $CHAIN"
if iptables -n -L DOCKER-USER >/dev/null 2>&1; then
  jump_if_missing iptables DOCKER-USER "-p tcp --dport $PUBLISH_PORT -j $CHAIN"
fi

if command -v ip6tables >/dev/null 2>&1; then
  ensure_chain ip6tables "$CHAIN6"
  ip6tables -A "$CHAIN6" -s ::1/128 -j RETURN
  ip6tables -A "$CHAIN6" -j DROP
  jump_if_missing ip6tables INPUT "-p tcp --dport $PUBLISH_PORT -j $CHAIN6"
  if ip6tables -n -L DOCKER-USER >/dev/null 2>&1; then
    jump_if_missing ip6tables DOCKER-USER "-p tcp --dport $PUBLISH_PORT -j $CHAIN6"
  fi
fi
