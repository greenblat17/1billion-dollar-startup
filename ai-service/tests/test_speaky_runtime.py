"""The shipping AI app must not construct legacy stores or background loops."""
import json
import os
import subprocess
import sys

import pytest
from uvicorn.importer import import_from_string

from app.config import Settings


def test_stage1_uses_kora_model_unless_overridden(monkeypatch):
    monkeypatch.delenv('STAGE1_MODEL', raising=False)
    monkeypatch.delenv('KORA_MODEL', raising=False)
    monkeypatch.setenv('SPEAKY_ENV', 'dev')
    monkeypatch.setenv('OPENAI_BASE_URL', 'https://openrouter.ai/api/v1')
    settings = Settings.from_env()
    assert settings.stage1_model is None
    assert settings.kora_model == 'anthropic/claude-sonnet-4.6'

    monkeypatch.setenv('STAGE1_MODEL', 'example/intro-model')
    assert Settings.from_env().stage1_model == 'example/intro-model'


def test_legacy_uvicorn_target_resolves_to_the_same_factory():
    assert import_from_string('app.main:create_app') is import_from_string('app.legacy.main:create_app')


@pytest.mark.parametrize('setting,value,reason', [
    ('FOUNDATION_BACKEND_URL', '', 'foundation_configuration_missing'),
    ('OPENAI_API_KEY', '', 'openai_configuration_missing'),
    ('FOUNDATION_BACKEND_CA_FILE', '/private-nonexistent/CONTROL_CA.pem', 'foundation_tls_configuration_failed'),
])
def test_shipping_runtime_reports_configuration_failure_before_readiness(setting, value, reason):
    env = dict(os.environ, AI_INTERNAL_TOKEN='control-internal-token', FOUNDATION_BACKEND_URL='http://127.0.0.1:1',
               OPENAI_API_KEY='control-openai-key', FOUNDATION_BACKEND_CA_FILE='', LOG_LEVEL='INFO', LOG_FILE='')
    env[setting] = value
    result = subprocess.run(
        [sys.executable, '-c', 'from app.speaky_main import create_app\n'
         'try:\n    create_app()\nexcept (RuntimeError, OSError, ValueError):\n    raise SystemExit(2)'],
        env=env, capture_output=True, text=True, timeout=30,
    )
    assert result.returncode == 2, result.stderr
    [record] = [json.loads(line.partition('runtime_event ')[2]) for line in result.stderr.splitlines()
                if 'runtime_event ' in line]
    assert (record['event'], record['stage'], record['outcome'], record['reason_code']) == (
        'ai_runtime', 'configuration', 'failed', reason)
    assert len(record['error_id']) == 32
    for secret in ('control-internal-token', 'control-openai-key', 'CONTROL_CA.pem'):
        assert secret not in result.stderr


def test_runtime_starts_without_redis_and_exposes_only_foundation_routes():
    program = '''
import asyncio
import importlib.abc
import sys
class NoLegacyRuntime(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path=None, target=None):
        if fullname in ("app.main", "app.legacy", "redis") or fullname.startswith(("app.legacy.", "redis.")):
            raise AssertionError(f"shipping runtime imported {fullname}")
sys.meta_path.insert(0, NoLegacyRuntime())
import httpx
from app.speaky_main import create_app
from app.config import Settings

async def check():
    settings = Settings.from_env()
    if True:
        app = create_app(settings)
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as client:
                for _ in range(30):
                    assert (await client.get('/health')).json()['runtime'] == 'speaky'
                assert (await client.get('/health', params={'lessonId': 'private-httpx-query'})).status_code == 200
                unauthorized = await client.post('/internal/foundation/compute', json={},
                    headers={'X-Diagnostic-Trace': 'f' * 32, 'X-Diagnostic-Fence': '99', 'X-Diagnostic-Attempt': '99'})
                assert unauthorized.status_code == 401
                assert unauthorized.headers['X-Diagnostic-Trace'] != 'f' * 32
                assert len(unauthorized.headers['X-Error-ID']) == 32
                malformed = await client.post('/internal/foundation/compute', content='{',
                    headers={'X-Internal-Token': 'local-test', 'X-Execution-Scope': 'private-scope',
                             'X-Diagnostic-Trace': 'e' * 32, 'X-Diagnostic-Call': 'private-header',
                             'X-Diagnostic-Job': 'd' * 32, 'X-Diagnostic-Fence': '8', 'X-Diagnostic-Attempt': '2'})
                assert malformed.status_code == 400
                assert malformed.headers['X-Diagnostic-Trace'] == 'e' * 32
                assert len(malformed.headers['X-Error-ID']) == len(malformed.headers['X-Request-ID']) == 32
                for path in ('/internal/calls/open', '/internal/vocabulary/list', '/internal/tutor/turn', '/internal/metrics'):
                    assert (await client.post(path, headers={'X-Internal-Token': 'local-test'}, json={})).status_code == 404
        assert {route.path for route in app.routes} == {'/health', '/internal/foundation/tutor/turn', '/internal/foundation/compute', '/internal/trial/followup', '/internal/trial/initial-roadmap', '/miniapp/api', '/miniapp/diagnostics', '/miniapp/voice'}
asyncio.run(check())
'''
    env = dict(os.environ, AI_INTERNAL_TOKEN='local-test', FOUNDATION_BACKEND_URL='http://127.0.0.1:1',
               OPENAI_API_KEY='local-test', REDIS_URL='redis://127.0.0.1:1', LOG_LEVEL='INFO', LOG_FILE='')
    result = subprocess.run([sys.executable, '-c', program], env=env, capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, result.stderr
    assert "private-scope" not in result.stderr and "private-header" not in result.stderr
    assert "private-httpx-query" not in result.stderr
    records = [json.loads(line.partition('runtime_event ')[2]) for line in result.stderr.splitlines()
               if 'runtime_event ' in line]
    lifecycle = [(row['stage'], row['outcome']) for row in records if row['event'] == 'ai_runtime']
    assert lifecycle == [('startup', 'completed'), ('shutdown', 'completed')]
    assert len([row for row in records if row['event'] == 'http_request']) == 6
    [denied] = [row for row in records if row.get('http_status') == 401]
    assert 'fence' not in denied and 'attempt' not in denied
    [trusted] = [row for row in records if row['trace_id'] == 'e' * 32]
    assert (trusted['job_id'], trusted['fence'], trusted['attempt']) == ('d' * 32, 8, 2)
