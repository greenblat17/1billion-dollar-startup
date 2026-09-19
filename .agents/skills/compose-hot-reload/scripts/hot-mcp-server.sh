#!/usr/bin/env bash
set -euo pipefail

# Cursor's agent/MCP sandbox often sets GRADLE_USER_HOME to an empty
# cursor-sandbox-cache, so the wrapper ZIP is downloaded again.
# OpenCode and Codex must not use this script; they run ./gradlew from cmp/.
if [[ -z "${HOME:-}" && -n "${USER:-}" ]]; then
  HOME="$(getent passwd "$USER" | cut -d: -f6 || true)"
  export HOME
fi
if [[ -n "${HOME:-}" ]]; then
  case "${GRADLE_USER_HOME:-}" in
    *cursor-sandbox-cache* | "")
      export GRADLE_USER_HOME="$HOME/.gradle"
      ;;
  esac
fi

dir="${PWD}"
root=""
while [[ "$dir" != "/" ]]; do
  if [[ -f "$dir/settings.gradle.kts" || -f "$dir/settings.gradle" ]]; then
    root="$dir"
    break
  fi
  dir="$(dirname "$dir")"
done
if [[ -z "$root" && -f "${PWD}/cmp/settings.gradle.kts" ]]; then
  root="${PWD}/cmp"
fi
if [[ -z "$root" ]]; then
  echo "hot-mcp-server: no settings.gradle(.kts) above ${PWD}" >&2
  exit 1
fi

gradlew="$root/gradlew"
if [[ ! -x "$gradlew" ]]; then
  echo "hot-mcp-server: missing executable $gradlew" >&2
  exit 1
fi

cd "$root"
exec "$gradlew" --no-daemon --quiet --console=plain "${HOT_MCP_TASK:-:app:desktopApp:hotMcpServer}"
