#!/usr/bin/env bash
set -euo pipefail

scenario_name="$1"
output="$RUNNER_TEMP/voice-inputs"
case "$scenario_name" in
  regular-action-mute) scenario=infra/dev/scenarios/voice-regular-action.json ;;
  short|early-exit|phase-probe|regular-action|regular-action-clear-finish|regular-action-controlled-input)
    scenario="infra/dev/scenarios/voice-$scenario_name.json" ;;
  *) echo 'Unsupported voice scenario' >&2; exit 2 ;;
esac

if [[ "$scenario_name" == regular-action-controlled-input ]]; then
  python3 infra/dev/prepare-voice-audio.py verify "$output" "$scenario" > "$RUNNER_TEMP/verified-voice-scenario.json"
else
  timeout --kill-after=5s 60s python3 -m pip install --quiet --disable-pip-version-check \
    --only-binary=:all: --no-deps --require-hashes -r <(
      printf '%s\n' 'espeakng-loader==0.2.4 --hash=sha256:08721baf27d13d461f6be6eed9a65277e70d68234ff484fd8b9897b222cdcb6d'
    )
  timeout 60s python3 infra/dev/prepare-voice-audio.py generate "$output" "$scenario"
  python3 infra/dev/prepare-voice-audio.py verify "$output" "$scenario" > "$RUNNER_TEMP/verified-voice-scenario.json"
fi
