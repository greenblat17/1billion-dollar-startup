#!/usr/bin/env bash
set -euo pipefail
source infra/dev/operator-ssh.sh

case "$MODE" in
  text|foundation|foundation-entitlement|voice) ;;
  *) exit 2 ;;
esac
[[ "$ATTEMPTS" == 1 ]] || { echo 'Exactly one attempt is supported per run' >&2; exit 2; }
paid=false
if [[ "$MODE" == text || ( "$MODE" == voice && "$SCENARIO" != read-only ) ]]; then
  paid=true
fi
if [[ "$MODE" == foundation ]]; then
  [[ "$SCENARIO" =~ ^foundation-[a-z0-9-]+\.json$ ]]
  paid=$(python3 - "$SCENARIO" <<'PYTHON'
import json
import sys
from pathlib import Path

scenario = json.loads((Path("infra/dev/scenarios") / sys.argv[1]).read_text())
print(str(any(step["request"]["kind"] not in {"read", "catalog"} for step in scenario["steps"])).lower())
PYTHON
  )
fi
if [[ "$paid" == true ]]; then
  [[ "$ALLOW_PAID" == true ]] || { echo 'Enable paid model calls explicitly' >&2; exit 2; }
  [[ "$GITHUB_RUN_ATTEMPT" == 1 ]] || { echo 'Dispatch a new authorized run for another paid attempt' >&2; exit 2; }
fi
dev_revision ktor "$KTOR_REVISION"
dev_revision ai "$AI_REVISION"

if [[ "$MODE" == voice ]]; then
  exec bash infra/dev/acceptance-voice.sh
fi

dev_ssh_config ktor
umask 077
work=$(dev_ssh 'mktemp -d /tmp/speaky-acceptance.XXXXXXXX')
[[ "$work" =~ ^/tmp/speaky-acceptance\.[A-Za-z0-9]+$ ]]
trap 'dev_ssh "rm -f $work/input.json $work/foundation-input.json" >/dev/null 2>&1 || true' EXIT

if [[ "$MODE" == text ]]; then
  [[ -n "$LEARNER_MESSAGE" ]] || { echo 'Enter a synthetic learner message' >&2; exit 2; }
  python3 - <<'PY'
import json, os
from pathlib import Path
(Path(os.environ["RUNNER_TEMP"]) / "input.json").write_text(
    json.dumps({"message": os.environ["LEARNER_MESSAGE"], "studentId": os.environ["STUDENT_ID"]})
)
PY
  scp "${ssh_options[@]}" infra/dev/agent-dialogue.py infra/dev/agent-dialogue-remote.py \
    "$RUNNER_TEMP/input.json" "$ssh_target:$work/"
  command=agent-dialogue-remote.py
else
  files=(infra/dev/foundation-check-remote.py)
  if [[ "$MODE" == foundation ]]; then
    files+=("infra/dev/scenarios/$SCENARIO")
  fi
  python3 - <<'PY'
import json, os
from pathlib import Path
(Path(os.environ["RUNNER_TEMP"]) / "foundation-input.json").write_text(
    json.dumps({"mode": "regular_entitlement" if os.environ["MODE"] == "foundation-entitlement" else "scenario",
                "studentId": os.environ["STUDENT_ID"], "scenario": os.environ["SCENARIO"]})
)
PY
  scp "${ssh_options[@]}" "${files[@]}" "$RUNNER_TEMP/foundation-input.json" "$ssh_target:$work/"
  command=foundation-check-remote.py
fi

result=0
dev_ssh "umask 077; python3 $work/$command $work > $work/runner.log 2>&1" || result=$?
printf 'Private Ktor evidence directory: %s\n' "$work"
if [[ "$result" != 0 ]]; then
  echo 'DEV acceptance stopped after one attempt. Inspect private evidence before retrying.' >&2
  exit "$result"
fi
echo 'DEV acceptance scenario completed'
