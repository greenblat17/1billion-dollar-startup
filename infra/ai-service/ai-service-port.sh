# Sourced by deploy-remote.sh and restrict-8090.sh. Default 8090.
# Reads AI_SERVICE_PORT from /opt/ai-service/.env (host publish port).
ai_service_port_from_env() {
  local file="${1:-/opt/ai-service/.env}"
  local port=8090
  local line
  if [ -f "$file" ]; then
    line=$(grep -E '^AI_SERVICE_PORT=' "$file" | tail -n 1 || true)
    if [ -n "$line" ]; then
      port="${line#AI_SERVICE_PORT=}"
      port="${port%%#*}"
      port="${port//[$'\t\r\n ']/}"
    fi
  fi
  case "$port" in
    '' | *[!0-9]*)
      echo "Invalid AI_SERVICE_PORT='$port'" >&2
      return 1
      ;;
  esac
  if [ "$port" -lt 1 ] || [ "$port" -gt 65535 ]; then
    echo "AI_SERVICE_PORT out of range: $port" >&2
    return 1
  fi
  printf '%s\n' "$port"
}
