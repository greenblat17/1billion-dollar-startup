#!/usr/bin/env bash
set -euo pipefail
source infra/dev/operator-ssh.sh

python3 - <<'PY'
import os, uuid
uuid.UUID(os.environ['STUDENT_ID'])
uuid.UUID(os.environ['LESSON_ID'])
PY
if [[ "$SCENARIO" == read-only ]]; then
  scenario=infra/dev/scenarios/voice-read-only.json
else
  scenario="$RUNNER_TEMP/verified-voice-scenario.json"
fi
voice_options=()
if [[ "$SCENARIO" == regular-action-controlled-input ]]; then
  voice_options+=(--mute-boundary-every-turn)
elif [[ "$SCENARIO" == regular-action-mute || "$SCENARIO" == regular-action-clear-finish ]]; then
  voice_options+=(--mute-boundary-turn 3)
fi
dev_ssh_config ktor
cmp_opts=("${ssh_options[@]}")
dev_ssh_config ai
ai_opts=("${ssh_options[@]}")
umask 077
cmp_config="$RUNNER_TEMP/agent-voice-cmp.json"
ai_config="$RUNNER_TEMP/agent-voice-ai.json"
cmp_tunnel=
ai_tunnel=
cleanup() {
  if [[ -n "$cmp_tunnel" ]]; then kill "$cmp_tunnel" 2>/dev/null || true; fi
  if [[ -n "$ai_tunnel" ]]; then kill "$ai_tunnel" 2>/dev/null || true; fi
  rm -f "$cmp_config" "$ai_config"
}
trap cleanup EXIT
echo 'Voice preflight: checking DEV containers'
timeout 35s ssh "${cmp_opts[@]}" "$KTOR_USER@$KTOR_HOST" 'python3 -' > "$cmp_config" <<'PY'
import json, subprocess
info = json.loads(subprocess.check_output(['docker', 'inspect', 'speaking-coach']))[0]
env = dict(item.split('=', 1) for item in info['Config']['Env'] if '=' in item)
print(json.dumps({'running': info['State']['Running'], 'dev': env.get('SPEAKY_ENV') == 'dev',
                  'enabled': env.get('AGENT_TEST_ENABLED') == 'true',
                  'port': env.get('SERVER_PORT', '443'), 'token': env.get('AI_INTERNAL_TOKEN', '')}))
PY
timeout 35s ssh "${ai_opts[@]}" "$AI_USER@$AI_HOST" 'python3 -' > "$ai_config" <<'PY'
import json, subprocess
info = json.loads(subprocess.check_output(['docker', 'inspect', 'ai-service']))[0]
env = dict(item.split('=', 1) for item in info['Config']['Env'] if '=' in item)
print(json.dumps({'running': info['State']['Running'], 'dev': env.get('SPEAKY_ENV') == 'dev',
                  'enabled': env.get('AGENT_TEST_ENABLED') == 'true',
                  'port': info['NetworkSettings']['Ports']['8090/tcp'][0]['HostPort']}))
PY
chmod 600 "$cmp_config" "$ai_config"
python3 - "$cmp_config" "$ai_config" <<'PY'
import json, sys
cmp, ai = [json.load(open(path)) for path in sys.argv[1:]]
assert all(cmp[key] for key in ('running', 'dev', 'enabled', 'token'))
assert all(ai[key] for key in ('running', 'dev', 'enabled'))
assert str(cmp['port']).isdigit() and str(ai['port']).isdigit()
print('DEV CMP and AI voice gates: ready')
PY
cmp_port=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["port"])' "$cmp_config")
ai_port=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["port"])' "$ai_config")
token=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["token"])' "$cmp_config")
ssh "${cmp_opts[@]}" -o ExitOnForwardFailure=yes -N \
  -L "127.0.0.1:18443:127.0.0.1:$cmp_port" "$KTOR_USER@$KTOR_HOST" &
cmp_tunnel=$!
ssh "${ai_opts[@]}" -o ExitOnForwardFailure=yes -N \
  -L "127.0.0.1:18090:127.0.0.1:$ai_port" "$AI_USER@$AI_HOST" &
ai_tunnel=$!
sleep 2
kill -0 "$cmp_tunnel" "$ai_tunnel"
echo 'Voice preflight: tunnels ready, starting client'
umask 077
mkdir -p "$RUNNER_TEMP/agent-voice-run"
result=0
AI_INTERNAL_TOKEN="$token" python3 infra/dev/agent-voice.py \
  "$scenario" \
  --ktor https://127.0.0.1:18443 --ai http://127.0.0.1:18090 \
  --student "$STUDENT_ID" --lesson "$LESSON_ID" \
  --output "$RUNNER_TEMP/agent-voice-run" "${voice_options[@]}" \
  > "$RUNNER_TEMP/agent-voice-run/runner.log" 2>&1 || result=$?
work=$(ssh "${cmp_opts[@]}" "$KTOR_USER@$KTOR_HOST" 'mktemp -d /tmp/speaky-acceptance.XXXXXXXX')
[[ "$work" =~ ^/tmp/speaky-acceptance\.[A-Za-z0-9]+$ ]]
tar -cf - -C "$RUNNER_TEMP/agent-voice-run" . |
  ssh "${cmp_opts[@]}" "$KTOR_USER@$KTOR_HOST" "umask 077; tar -xf - -C $work"
printf 'Private Ktor evidence directory: %s\n' "$work"
if [[ "$result" != 0 ]]; then
  echo 'DEV voice acceptance stopped after one attempt. Inspect private evidence before retrying.' >&2
  exit "$result"
fi
echo 'DEV voice scenario completed'
