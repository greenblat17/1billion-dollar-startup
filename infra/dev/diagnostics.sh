#!/usr/bin/env bash
set -euo pipefail
source infra/dev/operator-ssh.sh

case "$MODE" in
  readiness|correlation-probe)
    dev_revision ai "$AI_REVISION"
    dev_revision ktor "$KTOR_REVISION"
    dev_ssh_config ai
    if [[ "$MODE" == correlation-probe ]]; then
      output="$RUNNER_TEMP/verify-operator-ai.txt"
      dev_ssh "python3 - ai $AI_REVISION --operator --logging-smoke" < infra/dev/verify-tutor.py | tee "$output"
      trace=$(sed -n 's/^logging_probe_trace=\([0-9a-f]\{32\}\)$/\1/p' "$output")
      error=$(sed -n 's/^logging_probe_error_id=\([0-9a-f]\{32\}\)$/\1/p' "$output")
      [[ "$trace" =~ ^[0-9a-f]{32}$ && "$error" =~ ^[0-9a-f]{32}$ ]]
      dev_ssh_config ktor
      dev_ssh "python3 - cmp $KTOR_REVISION --operator --logging-smoke $trace $error" < infra/dev/verify-tutor.py
    else
      dev_ssh "python3 - ai $AI_REVISION --operator" < infra/dev/verify-tutor.py
      dev_ssh_config ktor
      dev_ssh "python3 - cmp $KTOR_REVISION --operator" < infra/dev/verify-tutor.py
    fi
    ;;
  ai-log)
    if [[ -n "$ERROR_ID" ]]; then [[ "$ERROR_ID" =~ ^[0-9a-f]{32}$ ]]; fi
    if [[ -n "$CALL_SESSION_ID" ]]; then [[ "$CALL_SESSION_ID" =~ ^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$ ]]; fi
    [[ -z "$ERROR_ID" || -z "$CALL_SESSION_ID" ]] || { echo 'Choose one AI lookup ID' >&2; exit 2; }
    dev_revision ai "$AI_REVISION"
    dev_ssh "python3 - '$ERROR_ID' '$CALL_SESSION_ID'" < infra/dev/diagnose-tutor.py
    ;;
  ktor-log)
    [[ "$DIAGNOSTIC_AT" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]]
    if [[ -n "$JOB_ID" ]]; then [[ "$JOB_ID" =~ ^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$ ]]; fi
    dev_revision ktor "$KTOR_REVISION"
    dev_ssh "python3 - '$DIAGNOSTIC_AT' '$JOB_ID'" < infra/dev/diagnose-tutor-cmp.py
    ;;
  *) exit 2 ;;
esac
