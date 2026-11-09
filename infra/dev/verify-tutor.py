"""DEV-only read-only readiness and optional bounded synthetic tutor smoke. Never prints credentials."""
import json
import re
import ssl
import subprocess
import sys
import time
from datetime import datetime, timedelta
import urllib.request


def request(url, token=None, payload=None, context=None):
    headers = {'X-Internal-Token': token} if token else {}
    data = None
    if payload is not None:
        headers['Content-Type'] = 'application/json'
        data = json.dumps(payload).encode()
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers), timeout=75, context=context) as response:
        body = response.read().decode()
        return response.status, body


def main():
    component, revision = sys.argv[1:3]
    container = 'speaking-coach' if component == 'cmp' else 'ai-service'
    info = json.loads(subprocess.check_output(['docker', 'inspect', container]))[0]
    env = dict(item.split('=', 1) for item in info['Config']['Env'] if '=' in item)
    print(json.dumps({'component': component, 'verification_ref': revision, 'image': info['Image'],
                      'running': info['State']['Running'], 'restarts': info['RestartCount'], 'oom': info['State']['OOMKilled']}))
    assert info['State']['Running'] and not info['State']['OOMKilled']
    startup_end = (datetime.fromisoformat(info['State']['StartedAt'].replace('Z', '+00:00')) + timedelta(seconds=60)).isoformat()
    logs = subprocess.check_output(['docker', 'logs', '--since', info['State']['StartedAt'], '--until', startup_end, container], stderr=subprocess.STDOUT).decode(errors='replace')
    markers = ['Application started', 'Application startup complete', 'FlywayValidateException', 'Traceback', 'ERROR']
    print('startup_log_markers=' + json.dumps({m: logs.count(m) for m in markers}))
    for line in logs.splitlines():
        if 'ERROR' not in line:
            continue
        for value in env.values():
            if len(value) >= 8:
                line = line.replace(value, '[REDACTED]')
        line = re.sub(r'sk-[A-Za-z0-9_-]+|[0-9]{8,12}:[A-Za-z0-9_-]{30,}', '[REDACTED]', line)
        print('startup_error_redacted=' + line[:700])
    assert 'FlywayValidateException' not in logs
    if component == 'ai':
        port = info['NetworkSettings']['Ports']['8090/tcp'][0]['HostPort']
        print('ai_health_status=', request('http://127.0.0.1:' + port + '/health')[0])
        print('config_ready=' + json.dumps({k: bool(env.get(k)) for k in ['OPENAI_API_KEY', 'AI_INTERNAL_TOKEN', 'REDIS_URL']}))
        print('model=' + env.get('KORA_MODEL', 'anthropic/claude-sonnet-4.6'))
        assert all(env.get(k) for k in ['OPENAI_API_KEY', 'AI_INTERNAL_TOKEN', 'REDIS_URL'])
        return
    tls = ssl.create_default_context(cafile='/opt/speaking-coach/tls.crt')
    tls.check_hostname = False  # Trust the deployed certificate itself for the loopback health probe.
    print('cmp_health_status=', request('https://127.0.0.1:' + env.get('SERVER_PORT', '443') + '/health', context=tls)[0])
    root = env['AI_SERVICE_BASE_URL'].rstrip('/')
    token = env['AI_INTERNAL_TOKEN']
    print('cmp_to_ai_health_status=', request(root + '/health')[0])
    learner = 'synthetic-sp01-dev-' + revision[:12]
    print('authenticated_context_status=', request(root + '/internal/learners/' + learner + '/context', token)[0])
    bot_token = env.get('TELEGRAM_BOT_TOKEN', env.get('TELEGRAM_TOKEN'))
    if bot_token:
        status, body = request('https://api.telegram.org/bot' + bot_token + '/getMe')
        print('dev_bot_username=', json.loads(body).get('result', {}).get('username'))
    if '--smoke' in sys.argv:
        for number, text in enumerate(['Хочу урок прямо сейчас.', 'Отмени пробную встречу.']):
            _, body = request(root + '/internal/tutor/turn', token,
                              dict(sessionId=learner, requestId='smoke-' + str(number), text=text, receivedAt=time.time()))
            result = json.loads(body)
            print('synthetic_smoke=' + json.dumps(result, ensure_ascii=False))
            if result.get('degraded'):
                print('SMOKE_BLOCKED: degraded response; no retries or further model requests')
                return
            booking = result.get('booking')
            assert booking and booking['status'] == ('scheduled' if number == 0 else 'cancelled'), 'smoke_booking_contract'


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print('DEV verification failed:', type(exc).__name__, 'http_status=', getattr(exc, 'code', None))
        sys.exit(1)
