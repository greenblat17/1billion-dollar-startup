"""DEV-only read-only readiness and optional bounded synthetic tutor smoke. Never prints credentials."""
import json
import re
import ssl
import subprocess
import sys
from datetime import datetime, timedelta
import urllib.request
import urllib.error


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
        if 'ERROR' not in line and 'Caused by:' not in line and not re.match(r'^[\w.$]+(?:Exception|Error):', line):
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
        print('config_ready=' + json.dumps({k: bool(env.get(k)) for k in ['OPENAI_API_KEY', 'AI_INTERNAL_TOKEN', 'FOUNDATION_BACKEND_URL']}))
        print('model=' + env.get('KORA_MODEL', 'anthropic/claude-sonnet-4.6'))
        assert all(env.get(k) for k in ['OPENAI_API_KEY', 'AI_INTERNAL_TOKEN', 'FOUNDATION_BACKEND_URL'])
        probe = """
import os, ssl, urllib.request, urllib.error
ctx = ssl.create_default_context()
if os.environ.get('FOUNDATION_BACKEND_CA_FILE'):
    ctx.load_verify_locations(os.environ['FOUNDATION_BACKEND_CA_FILE'])
root = os.environ['FOUNDATION_BACKEND_URL'].rstrip('/')
with urllib.request.urlopen(root + '/health', context=ctx, timeout=15) as response:
    assert response.status == 200
req = urllib.request.Request(root + '/internal/foundation/v1/snapshot',
    headers={'X-Internal-Token': os.environ['AI_INTERNAL_TOKEN']})
try:
    urllib.request.urlopen(req, context=ctx, timeout=15)
except urllib.error.HTTPError as exc:
    assert exc.code == 403
else:
    raise AssertionError('backend accepted a request without execution scope')
print('ai_to_backend_tls_and_service_auth=passed')
import asyncio
import boto3
from botocore.config import Config
from app.trial_audio import TrialAudioStore
assert os.environ.get('GROQ_API_KEY'), 'trial_stt_not_configured'
assert os.environ.get('TRIAL_AUDIO_BUCKET'), 'trial_audio_bucket_not_configured'
store = TrialAudioStore(os.environ['TRIAL_AUDIO_BUCKET'], os.environ.get('TRIAL_S3_ENDPOINT'))
print('trial_audio_readiness=checking_credentials', flush=True)
asyncio.run(store.check_credentials())
# Use the version-list permission already required by audio erasure.
s3 = boto3.client('s3', endpoint_url=os.environ.get('TRIAL_S3_ENDPOINT') or None,
                  config=Config(connect_timeout=5, read_timeout=5, retries={'max_attempts': 0}))
print('trial_audio_readiness=checking_bucket_version_access', flush=True)
s3.list_object_versions(Bucket=os.environ['TRIAL_AUDIO_BUCKET'], Prefix='trial/', MaxKeys=1)
print('trial_audio_credentials_and_bucket_read=passed; write_and_erasure=unverified')
"""
        result = subprocess.run(['docker', 'exec', '-i', container, 'python', '-'], input=probe.encode(),
                                capture_output=True, timeout=60)
        # SDK tracebacks can contain endpoints; expose only the controlled success markers.
        for line in result.stdout.decode().splitlines():
            if line.startswith(('ai_to_backend_tls_and_service_auth=', 'trial_audio_credentials_and_bucket_read=',
                                'trial_audio_readiness=')):
                print(line)
        if result.returncode:
            raise RuntimeError('ai_backend_or_trial_readiness_failed')
        return
    tls = ssl.create_default_context(cafile='/opt/speaking-coach/tls.crt')
    tls.check_hostname = False  # Trust the deployed certificate itself for the loopback health probe.
    print('cmp_health_status=', request('https://127.0.0.1:' + env.get('SERVER_PORT', '443') + '/health', context=tls)[0])
    root = env['AI_SERVICE_BASE_URL'].rstrip('/')
    token = env['AI_INTERNAL_TOKEN']
    print('cmp_to_ai_health_status=', request(root + '/health')[0])
    try:
        request(root + '/internal/foundation/compute', token, {})
    except urllib.error.HTTPError as exc:
        assert exc.code == 422, 'service_auth_or_foundation_route_failed'
        print('cmp_to_ai_service_auth=passed; missing scope correctly rejected')
    else:
        raise AssertionError('AI accepted a request without execution scope')
    bot_token = env.get('TELEGRAM_BOT_TOKEN', env.get('TELEGRAM_TOKEN'))
    if bot_token:
        status, body = request('https://api.telegram.org/bot' + bot_token + '/getMe')
        print('dev_bot_username=', json.loads(body).get('result', {}).get('username'))
    if '--smoke' in sys.argv:
        raise RuntimeError('Legacy synthetic smoke is unavailable in SP-00; use scoped integration tests')


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print('DEV verification failed:', type(exc).__name__, 'http_status=', getattr(exc, 'code', None))
        sys.exit(1)
