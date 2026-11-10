#!/usr/bin/env bash
set -euo pipefail
: "${TEST_POSTGRES_URL:?Set TEST_POSTGRES_URL to an isolated PostgreSQL test database}"
foundation_root="$(cd "$(dirname "$0")/.." && pwd)"
foundation_python="${FOUNDATION_TEST_PYTHON:-python3}"
export SP00_TEST_PORT="${SP00_TEST_PORT:-58082}"
export SP00_TEST_TOKEN="${SP00_TEST_TOKEN:-foundation-local-test-only}"
export SP00_TEST_URL="http://127.0.0.1:${SP00_TEST_PORT}"
foundation_log="$(mktemp -t speaky-foundation.XXXXXX)"
cd "$foundation_root/cmp"
./gradlew :server:foundationIntegrationTest --console=plain
"$foundation_python" - <<'PY'
import os
import socket
with socket.socket() as check:
    check.bind(('127.0.0.1', int(os.environ['SP00_TEST_PORT'])))
PY
./gradlew :server:foundationHarness --console=plain >"$foundation_log" 2>&1 &
foundation_harness_pid=$!
cleanup() {
  kill "$foundation_harness_pid" 2>/dev/null || true
  wait "$foundation_harness_pid" 2>/dev/null || true
  cat "$foundation_log"
  rm -f "$foundation_log"
}
trap cleanup EXIT
"$foundation_python" - <<'PY'
import os
import time
import urllib.request
for attempt in range(180):
    try:
        with urllib.request.urlopen(os.environ['SP00_TEST_URL'] + '/test/health', timeout=1) as response:
            if response.read() == b'ok':
                break
    except (OSError, TimeoutError):
        time.sleep(1)
else:
    raise SystemExit('Foundation harness failed to start; inspect the Gradle log')
PY
cd "$foundation_root/ai-service"
case "${FOUNDATION_TEST_SUITE:-runtime}" in
  runtime) "$foundation_python" -m pytest integration/test_foundation_http.py -q ;;
  migration)
    : "${SP00_IMPORT_REDIS_URL:?Migration suite requires an isolated Redis source}"
    "$foundation_python" -m pytest integration/test_tutor_import_redis.py -q ;;
  *) echo "FOUNDATION_TEST_SUITE must be runtime or migration" >&2; exit 1 ;;
esac
