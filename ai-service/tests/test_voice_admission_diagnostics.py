import json
import logging
import re
from dataclasses import replace
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from fastapi.testclient import TestClient

from app import speaky_main, trial_api
from app.config import Settings


_ATTEMPT = 'f' * 32
_FRAME = {'protocol': 'realtime-pcm-v1', 'attemptId': _ATTEMPT,
          'lessonId': 'PRIVATE_LESSON', 'initData': 'PRIVATE_INIT'}


@pytest.fixture
def voice_app(monkeypatch):
    monkeypatch.setenv('SPEAKY_ENV', 'dev')
    monkeypatch.setenv('AGENT_TEST_ENABLED', 'true')
    request = AsyncMock(side_effect=AssertionError('A rejected voice request reached Foundation'))
    store = SimpleNamespace(cleanup=AsyncMock(), check_credentials=AsyncMock())
    monkeypatch.setattr(speaky_main.FoundationClient, '_request', request)
    monkeypatch.setattr(speaky_main, 'TrialAudioStore', lambda *_: store)
    settings = replace(Settings.from_env(), log_level='INFO', log_file='',
                       ai_internal_token='PRIVATE_INTERNAL', foundation_backend_url='http://127.0.0.1:1',
                       openai_api_key='PRIVATE_MODEL', openai_realtime_api_key='PRIVATE_REALTIME',
                       trial_audio_bucket='PRIVATE_BUCKET')

    def build(**overrides):
        return speaky_main.create_app(replace(settings, **overrides))

    return build, request, store


def _connect(app, caplog, frame, *, response_code, peer='127.0.0.1', token='PRIVATE_INTERNAL'):
    caplog.set_level(logging.INFO)
    headers = {'X-Diagnostic-Trace': 'e' * 32}
    if token is not None:
        headers['X-Internal-Token'] = token
    with TestClient(app, client=(peer, 50000)) as client:
        caplog.clear()
        with client.websocket_connect('/miniapp/voice', headers=headers) as socket:
            if isinstance(frame, dict):
                socket.send_json(frame)
            elif isinstance(frame, bytes):
                socket.send_bytes(frame)
            elif frame is not None:
                socket.send_text(frame)
            response = socket.receive_json() if response_code is not None else None
            assert socket.receive() == {'type': 'websocket.close', 'code': 1000, 'reason': ''}
        rows = [json.loads(record.message.removeprefix('runtime_event ')) for record in caplog.records
                if record.message.startswith('runtime_event ')]
    start, finish = rows
    assert (start['event'], start['stage'], start['outcome']) == ('trial_socket', 'receive', 'started')
    assert start['operation'] == finish['operation'] == 'trial.voice'
    for field in ('trace_id', 'request_id'):
        assert re.fullmatch(r'[0-9a-f]{32}', start[field])
        assert start[field] == finish[field]
    assert start['trace_id'] not in (_ATTEMPT, 'e' * 32)
    assert start['error_id'] is None and 'client_attempt_id' not in start
    assert re.fullmatch(r'[0-9a-f]{32}', finish['error_id'])
    if response is not None:
        assert response == {'type': 'error', 'code': response_code, 'errorId': finish['error_id']}
    assert 'PRIVATE_' not in caplog.text
    return finish


@pytest.mark.parametrize(('frame', 'reason', 'response', 'attempt'), [
    pytest.param('{"initData":"PRIVATE_MALFORMED"', 'invalid_voice_request', 'connection_lost', None, id='malformed-json'),
    pytest.param('["PRIVATE_NON_OBJECT"]', 'invalid_voice_request', 'connection_lost', None, id='non-object-json'),
    pytest.param(b'PRIVATE_BINARY', 'invalid_voice_request', 'connection_lost', None, id='binary-first-frame'),
    pytest.param('PRIVATE_OVERSIZED'.ljust(20001), 'request_too_large', None, None, id='oversized'),
    pytest.param(_FRAME | {'protocol': 'PRIVATE_OLD_PROTOCOL'}, 'client_update_required',
                 'client_update_required', _ATTEMPT, id='unsupported-protocol'),
    pytest.param({key: value for key, value in _FRAME.items() if key != 'lessonId'},
                 'invalid_voice_request', 'connection_lost', _ATTEMPT, id='missing-lesson'),
    pytest.param({key: value for key, value in _FRAME.items() if key != 'attemptId'},
                 'invalid_voice_request', 'connection_lost', None, id='missing-attempt'),
    pytest.param({key: value for key, value in _FRAME.items() if key != 'initData'},
                 'invalid_voice_request', 'connection_lost', _ATTEMPT, id='missing-auth'),
    pytest.param(None, 'voice_request_timeout', 'connection_lost', None, id='first-frame-timeout'),
])
def test_voice_rejected_first_frame_has_one_safe_reason(voice_app, monkeypatch, caplog, frame, reason, response, attempt):
    build, request, _ = voice_app
    if frame is None:
        monkeypatch.setattr(trial_api, '_VOICE_FIRST_FRAME_TIMEOUT_SECONDS', 0.01)
    finish = _connect(build(), caplog, frame, response_code=response)
    assert (finish['event'], finish['stage'], finish['outcome'], finish['reason_code']) == (
        'trial_failed', 'validate', 'rejected', reason)
    assert finish.get('client_attempt_id') == attempt
    assert 'stack' not in finish
    request.assert_not_awaited()


@pytest.mark.parametrize(('environment', 'enabled', 'peer', 'token', 'mixed', 'expected'), [
    pytest.param('dev', 'true', '127.0.0.1', 'PRIVATE_INTERNAL', True,
                 (True, True, 'loopback', True, True), id='mixed-auth'),
    pytest.param('prod', 'true', '127.0.0.1', 'PRIVATE_INTERNAL', False,
                 (False, True, 'loopback', True, False), id='production'),
    pytest.param('dev', 'false', '127.0.0.1', 'PRIVATE_INTERNAL', False,
                 (True, False, 'loopback', True, False), id='disabled'),
    pytest.param('dev', 'true', 'public.example', 'PRIVATE_INTERNAL', False,
                 (True, True, 'other', True, False), id='public-peer'),
    pytest.param('dev', 'true', '127.0.0.1', None, False,
                 (True, True, 'loopback', False, False), id='missing-service-token'),
    pytest.param('dev', 'true', '127.0.0.1', 'PRIVATE_WRONG_TOKEN', False,
                 (True, True, 'loopback', False, False), id='wrong-service-token'),
])
def test_voice_agent_policy_rejections_log_which_conditions_failed(
        voice_app, monkeypatch, caplog, environment, enabled, peer, token, mixed, expected):
    build, request, _ = voice_app
    monkeypatch.setenv('SPEAKY_ENV', environment)
    monkeypatch.setenv('AGENT_TEST_ENABLED', enabled)
    frame = _FRAME | {'agentGrant': 'PRIVATE_GRANT'}
    if not mixed:
        del frame['initData']
    finish = _connect(build(), caplog, frame, response_code='not_permitted', peer=peer, token=token)
    assert (finish['event'], finish['stage'], finish['outcome'], finish['reason_code']) == (
        'agent_voice_denied', 'authorize', 'rejected', 'not_permitted')
    assert finish['client_attempt_id'] == _ATTEMPT
    assert tuple(finish[key] for key in ('dev', 'enabled', 'peer_kind', 'token_valid', 'mixed_auth')) == expected
    request.assert_not_awaited()


@pytest.mark.parametrize('field', ['openai_realtime_api_key', 'trial_audio_bucket'])
def test_voice_reports_unconfigured_provider_or_archive_before_reading_credentials(voice_app, caplog, field):
    build, request, store = voice_app
    finish = _connect(build(**{field: None}), caplog, None, response_code='trial_not_configured')
    assert (finish['event'], finish['stage'], finish['outcome'], finish['reason_code']) == (
        'trial_failed', 'authenticate', 'failed', 'trial_not_configured')
    request.assert_not_awaited()
    store.check_credentials.assert_not_awaited()


def test_voice_reports_audio_credentials_failure_without_reaching_foundation(voice_app, caplog):
    build, request, store = voice_app
    store.check_credentials.side_effect = RuntimeError('PRIVATE_STORAGE_FAILURE')
    finish = _connect(build(), caplog, _FRAME, response_code='trial_not_configured')
    assert (finish['event'], finish['stage'], finish['outcome'], finish['reason_code']) == (
        'trial_failed', 'audio_credentials', 'failed', 'audio_credentials_unavailable')
    assert finish['error_type'] == 'RuntimeError'
    request.assert_not_awaited()
