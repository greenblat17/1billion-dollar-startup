"""Agent voice admission never reaches Foundation from a public or disabled relay."""
from types import SimpleNamespace
from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest

from app.trial_api import install_trial_api
from app.foundation_client import BackendFailure


class Backend:
    calls = 0

    async def _request(self, *args, **kwargs):
        self.calls += 1
        raise AssertionError('Foundation must not receive an unauthorized grant')


class Store:
    async def check_credentials(self):
        pass


@pytest.mark.parametrize('environment,enabled,peer', [
    ('prod', 'true', ('127.0.0.1', 50000)),
    ('dev', 'false', ('127.0.0.1', 50000)),
    ('dev', 'true', ('public.example', 50000)),
])
def test_agent_grant_requires_dev_flag_and_loopback(monkeypatch, environment, enabled, peer):
    monkeypatch.setenv('SPEAKY_ENV', environment)
    monkeypatch.setenv('AGENT_TEST_ENABLED', enabled)
    backend = Backend()
    app = FastAPI()
    install_trial_api(app, backend, None, None, None,
                      SimpleNamespace(openai_realtime_api_key='configured', ai_internal_token='service-token'), Store())
    with TestClient(app, client=peer, headers={'X-Internal-Token': 'service-token'}) as client:
        with client.websocket_connect('/miniapp/voice') as socket:
            socket.send_json({'protocol': 'realtime-pcm-v1', 'agentGrant': 'untrusted',
                              'lessonId': 'lesson', 'attemptId': 'attempt'})
            assert socket.receive_json() == {'type': 'error', 'code': 'not_permitted'}
    assert backend.calls == 0


def test_loopback_dev_grant_reaches_existing_foundation_admission(monkeypatch):
    monkeypatch.setenv('SPEAKY_ENV', 'dev')
    monkeypatch.setenv('AGENT_TEST_ENABLED', 'true')

    class CapturingBackend:
        payload = None

        async def _request(self, method, path, scope, **kwargs):
            self.payload = kwargs['json']
            raise BackendFailure('not_owned', 403)

    backend = CapturingBackend()
    app = FastAPI()
    install_trial_api(app, backend, None, None, None,
                      SimpleNamespace(openai_realtime_api_key='configured', ai_internal_token='service-token'), Store())
    with TestClient(app, client=('127.0.0.1', 50000), headers={'X-Internal-Token': 'service-token'}) as client:
        with client.websocket_connect('/miniapp/voice') as socket:
            socket.send_json({'protocol': 'realtime-pcm-v1', 'agentGrant': 'one-use',
                              'lessonId': 'lesson', 'attemptId': 'attempt'})
            assert socket.receive_json()['code'] == 'not_owned'
    assert backend.payload == {'action': 'open', 'lessonId': 'lesson',
                               'attemptId': 'attempt', 'agentGrant': 'one-use'}


def test_agent_grant_needs_service_header(monkeypatch):
    monkeypatch.setenv('SPEAKY_ENV', 'dev')
    monkeypatch.setenv('AGENT_TEST_ENABLED', 'true')
    backend = Backend()
    app = FastAPI()
    install_trial_api(app, backend, None, None, None,
                      SimpleNamespace(openai_realtime_api_key='configured', ai_internal_token='service-token'), Store())
    with TestClient(app, client=('127.0.0.1', 50000)) as client:
        with client.websocket_connect('/miniapp/voice') as socket:
            socket.send_json({'protocol': 'realtime-pcm-v1', 'agentGrant': 'one-use',
                              'lessonId': 'lesson', 'attemptId': 'attempt'})
            assert socket.receive_json()['code'] == 'not_permitted'
    assert backend.calls == 0
