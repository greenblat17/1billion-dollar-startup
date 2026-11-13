from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest
from botocore.exceptions import NoCredentialsError
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.trial_audio import TrialAudioStore
from app.trial_api import install_trial_api


@pytest.mark.asyncio
async def test_missing_credentials_fail_without_an_s3_request():
    store = object.__new__(TrialAudioStore)
    store.session = Mock()
    store.session.get_credentials.return_value = None
    store.client = Mock()
    with pytest.raises(NoCredentialsError):
        await store.check_credentials()
    assert not store.client.mock_calls


@pytest.mark.asyncio
async def test_refreshable_credentials_are_resolved_without_exposing_values():
    store = object.__new__(TrialAudioStore)
    credentials = Mock()
    store.session = Mock()
    store.session.get_credentials.return_value = credentials
    assert await store.check_credentials() is None
    credentials.get_frozen_credentials.assert_called_once_with()
    credentials.get_frozen_credentials.side_effect = NoCredentialsError()
    with pytest.raises(NoCredentialsError):
        await store.check_credentials()


def test_missing_s3_credentials_do_not_open_episode_or_call_paid_providers(caplog):
    app = FastAPI()
    backend = SimpleNamespace(_request=AsyncMock(), command=AsyncMock())
    model = SimpleNamespace(complete_json=AsyncMock())
    store = SimpleNamespace(check_credentials=AsyncMock(side_effect=NoCredentialsError()), put=AsyncMock())
    install_trial_api(app, backend, None, model, None, SimpleNamespace(openai_realtime_api_key='test'), store, speech=object())
    with TestClient(app) as client:
        with client.websocket_connect('/miniapp/voice') as socket:
            socket.send_json({'initData': 'not-logged', 'lessonId': 'same-lesson', 'attemptId': 'new-attempt', 'protocol': 'realtime-pcm-v1'})
            result = socket.receive_json()
    assert result['type'] == 'error'
    assert result['code'] == 'trial_not_configured'
    assert result['errorId']
    backend._request.assert_not_awaited()
    backend.command.assert_not_awaited()
    model.complete_json.assert_not_awaited()
    store.put.assert_not_awaited()
    assert 'audio_credentials' in caplog.text
    assert 'NoCredentialsError' in caplog.text
    assert 'not-logged' not in caplog.text


def test_lesson_status_reads_authenticated_backend_without_opening_voice():
    app = FastAPI()
    result = {'serverNow': '2026-11-13T10:00:00Z', 'lesson': {'status': 'scheduled'}}
    backend = SimpleNamespace(_request=AsyncMock(return_value=result), command=AsyncMock())
    model = SimpleNamespace(complete_json=AsyncMock())
    install_trial_api(app, backend, None, model, None, SimpleNamespace(), None)
    payload = {'action': 'lesson_status', 'initData': 'signed', 'lessonId': 'lesson'}
    with TestClient(app) as client:
        response = client.post('/miniapp/api', json=payload)
    assert response.status_code == 200
    assert response.json() == result
    backend._request.assert_awaited_once_with('POST', '/trial/access', '', json=payload)
    backend.command.assert_not_awaited()
    model.complete_json.assert_not_awaited()
