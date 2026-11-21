#!/usr/bin/env bash
set -euo pipefail
source infra/dev/operator-ssh.sh

[[ "$ALLOW_PAID" == true ]] || { echo 'Enable paid STT calls explicitly' >&2; exit 2; }
[[ "$GITHUB_RUN_ATTEMPT" == 1 ]] || { echo 'Dispatch a new authorized run for another paid benchmark' >&2; exit 2; }
dev_revision ai "$AI_REVISION"
archive="$RUNNER_TEMP/speech-time-benchmark.tgz"
tar -czf "$archive" load/speech-time-dev-benchmark.py load/fixtures/librispeech-test-clean
work=$(dev_ssh 'mktemp -d /tmp/speaky-speech-benchmark.XXXXXXXX')
[[ "$work" =~ ^/tmp/speaky-speech-benchmark\.[A-Za-z0-9]+$ ]]
trap 'dev_ssh "rm -rf $work" >/dev/null 2>&1 || true' EXIT
scp "${ssh_options[@]}" "$archive" "$ssh_target:$work/benchmark.tgz"
dev_ssh "bash -s -- $work" <<'REMOTE'
set -euo pipefail
work="$1"
tar -xzf "$work/benchmark.tgz" -C "$work"
echo "DEV AI logical CPUs: $(nproc)"
image=$(docker inspect --format '{{.Image}}' ai-service)
# Provider exception messages can contain endpoint details. The runner emits controlled metrics on stdout.
result=0
timeout --kill-after=10s 240s docker run --rm --name "${work##*/}" \
  --env-file /opt/ai-service/.env \
  -v "$work:/bench:ro" -w /bench -e PYTHONPATH=/app \
  "$image" sh -c 'pip install --quiet --no-cache-dir webrtcvad-wheels==2.0.14.post1 >/dev/null &&
    python load/speech-time-dev-benchmark.py' 2>"$work/runner.log" || result=$?
docker rm -f "${work##*/}" >/dev/null 2>&1 || true
if [[ "$result" != 0 ]]; then
  echo "DEV STT benchmark failed with status $result" >&2
  exit "$result"
fi
REMOTE
