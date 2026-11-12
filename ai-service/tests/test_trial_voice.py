import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.trial_session import TrialSession
from app.trial_policy import TrialAnalyzer, TrialPlanner
from app.trial_providers import KoraTrialTTS


def session():
    backend = SimpleNamespace(command=AsyncMock(return_value={'objectKey': 'trial/test.pcm'}))
    socket = SimpleNamespace(send_json=AsyncMock(), send_bytes=AsyncMock(), close=AsyncMock())
    return TrialSession(socket, backend, None, None, None, None,
                        SimpleNamespace(put=AsyncMock(), delete=AsyncMock()),
                        {'scope': 'scope', 'lesson': {'id': 'lesson'}, 'callSessionId': 'episode'})


@pytest.mark.asyncio
async def test_goodbye_completes_only_after_playback_ack():
    s = session()
    s.pending['one'] = dict(text='До встречи!', finish=True, cancelled=False)
    assert not s.ended
    await s.acknowledged({'id': 'one', 'complete': True})
    assert s.ended
    actions = [call.args[2] for call in s.backend.command.call_args_list]
    assert actions == ['voice.playback', 'voice.append', 'voice.end']
    assert s.backend.command.call_args_list[-1].args[3]['reason'] == 'natural_goodbye'
    await s.acknowledged({'id': 'one', 'complete': True})
    assert s.backend.command.await_count == 3


@pytest.mark.asyncio
async def test_interrupted_goodbye_does_not_finish_or_store_unheard_words():
    s = session()
    s.pending['one'] = dict(text='Secret unheard ending.', finish=True, cancelled=False)
    await s.interrupt()
    await s.acknowledged({'id': 'one', 'complete': True, 'playedMs': 100})
    assert not s.ended
    args = s.backend.command.call_args.args
    assert args[2] == 'voice.append'
    assert 'Secret' not in args[3]['body']


@pytest.mark.asyncio
async def test_audio_registration_failure_removes_uploaded_object():
    s = session()
    s.backend.command.side_effect = [{'objectKey': 'trial/test.pcm'}, RuntimeError('epoch changed')]
    with pytest.raises(RuntimeError):
        await s.record('user', b'\x00\x00')
    s.store.delete.assert_awaited_once_with('trial/test.pcm')


@pytest.mark.asyncio
async def test_analyzer_rejects_invented_source_id():
    model = SimpleNamespace(complete_json=AsyncMock(return_value='{"status":"evaluated","observations":[{"sourceId":"other"}]}'))
    with pytest.raises(ValueError):
        await TrialAnalyzer(model).evaluate(SimpleNamespace(data={'sourceEvents': []}), {})


@pytest.mark.asyncio
async def test_preparation_does_not_require_roadmap_or_invent_evaluation():
    result = await TrialPlanner().prepare(SimpleNamespace(data={}), {})
    assert set(result) == {'objective', 'topic', 'strategy'}
    assert 'No roadmap or exam' in result['strategy']


@pytest.mark.asyncio
async def test_tts_preserves_kora_model_and_voice_and_yields_before_end():
    options = {}
    class Stream:
        async def __aenter__(self): return self
        async def __aexit__(self, *args): pass
        async def iter_bytes(self, **kwargs):
            yield b'\x01\x00'
            yield b'\x02\x00'
    def create(**kwargs):
        options.update(kwargs)
        return Stream()
    client = SimpleNamespace(audio=SimpleNamespace(speech=SimpleNamespace(with_streaming_response=SimpleNamespace(create=create))))
    result = [chunk async for chunk in KoraTrialTTS(client, 'current-kora-model', 'Leda').stream('Hello')]
    assert result == [b'\x01\x00', b'\x02\x00']
    assert options == {'model': 'current-kora-model', 'voice': 'Leda', 'input': 'Hello', 'response_format': 'pcm'}

@pytest.mark.asyncio
async def test_interruption_reaches_browser_before_slow_archive_finishes():
    s = session()
    cancelled = asyncio.Event()
    release = asyncio.Event()
    async def producing():
        try:
            await asyncio.sleep(100)
        finally:
            cancelled.set()
            await release.wait()
    s.turn_task = asyncio.create_task(producing())
    await asyncio.sleep(0)
    interruption = asyncio.create_task(s.interrupt())
    await cancelled.wait()
    s.socket.send_json.assert_awaited_with({'type': 'interrupt'})
    assert not interruption.done()
    release.set()
    await interruption


def test_websocket_routes_audio_and_finishes_after_ack_without_real_providers(monkeypatch):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from app.trial_api import install_trial_api
    import app.trial_api as api

    class STT:
        def __init__(self, key): pass
        async def __aenter__(self): return self
        async def __aexit__(self, *args): pass
        async def send(self, data): pass
        async def keepalive(self): pass
        async def events(self):
            await asyncio.Event().wait()
            yield {}
    class TTS:
        def __init__(self, *args): pass
        async def stream(self, text): yield b'\x00\x00' * 100
    monkeypatch.setattr(api, 'GroqTrialSTT', STT)
    monkeypatch.setattr(api, 'KoraTrialTTS', TTS)
    backend = SimpleNamespace(_request=AsyncMock(return_value={
        'scope': 'scoped', 'callSessionId': 'episode',
        'lesson': {'id': 'lesson', 'starts_at': '2026-11-11T10:00:00+00:00', 'duration_minutes': 15}}),
        command=AsyncMock(return_value={'objectKey': 'trial/test.pcm'}))
    context = SimpleNamespace(data={}, history=[])
    model = SimpleNamespace(complete_json=AsyncMock(return_value='{"reply":"До встречи!","wait":false,"finish":true}'))
    app = FastAPI()
    settings = SimpleNamespace(kora_tts_model='kora', kora_tts_voice='Leda')
    install_trial_api(app, backend, SimpleNamespace(build=AsyncMock(return_value=context)), model, None,
                      settings, SimpleNamespace(put=AsyncMock(), delete=AsyncMock()), speech=object())
    with TestClient(app) as client:
        assert client.get('/miniapp').status_code == 200
        with client.websocket_connect('/miniapp/voice') as socket:
            socket.send_json({'initData': 'signed', 'lessonId': 'lesson', 'attemptId': 'attempt'})
            assert socket.receive_json()['type'] == 'connected'
            start = socket.receive_json()
            assert start['type'] == 'audio_start'
            assert socket.receive_bytes() == b'\x00\x00' * 100
            assert socket.receive_json()['type'] == 'audio_end'
            assert not any(call.args[2] == 'voice.end' for call in backend.command.call_args_list)
            socket.send_json({'type': 'played', 'id': start['id'], 'complete': True, 'playedMs': 20})
            assert socket.receive_json()['type'] == 'finished'
    assert any(call.args[2] == 'voice.end' and call.args[3]['reason'] == 'natural_goodbye' for call in backend.command.call_args_list)


def test_unconfigured_trial_cannot_start_paid_providers():
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from app.trial_api import install_trial_api
    app = FastAPI()
    backend = SimpleNamespace(_request=AsyncMock())
    install_trial_api(app, backend, None, None, None, SimpleNamespace(), None)
    with TestClient(app) as client:
        with client.websocket_connect('/miniapp/voice') as socket:
            assert socket.receive_json() == {'type': 'error', 'code': 'trial_not_configured'}
    backend._request.assert_not_called()


@pytest.mark.asyncio
async def test_audio_erasure_deletes_all_exact_key_versions_and_rejects_partial_failure():
    from unittest.mock import Mock
    from app.trial_audio import TrialAudioStore
    store = object.__new__(TrialAudioStore)
    store.bucket = 'private-test'
    store.client = Mock()
    store.client.get_paginator.return_value.paginate.return_value = [{
        'Versions': [{'Key': 'trial/one', 'VersionId': 'v1'}, {'Key': 'trial/one-extra', 'VersionId': 'v2'}],
        'DeleteMarkers': [{'Key': 'trial/one', 'VersionId': 'marker'}],
    }]
    store.client.delete_objects.return_value = {}
    await store.delete('trial/one')
    store.client.delete_objects.assert_called_once_with(Bucket='private-test', Delete={'Objects': [
        {'Key': 'trial/one', 'VersionId': 'v1'}, {'Key': 'trial/one', 'VersionId': 'marker'},
    ]})
    store.client.delete_object.assert_called_once_with(Bucket='private-test', Key='trial/one')
    store.client.delete_objects.return_value = {'Errors': [{'Code': 'AccessDenied'}]}
    with pytest.raises(RuntimeError, match='audio_erasure_failed'):
        await store.delete('trial/one')


@pytest.mark.asyncio
async def test_groq_call_segments_pcm_and_reuses_existing_transcriber():
    import io
    import wave
    from app.stt import SttResult
    from app.trial_providers import GroqTrialSTT
    speech = SimpleNamespace(transcribe=AsyncMock(return_value=SttResult(text='Hello, привет')))
    detector = SimpleNamespace(is_speech=lambda frame, rate: frame != bytes(640))
    async with GroqTrialSTT(speech, detector) as stt:
        events = stt.events()
        # Silence and an isolated click must not invoke the provider or interrupt playback.
        await stt.send(bytes(640) * 45 + b'\x01\x00' * 320 + bytes(640) * 45)
        assert stt.results.empty()
        speech.transcribe.assert_not_called()
        await stt.send(b'\x01\x00' * (320 * 8))
        assert await anext(events) == {'speech_start': True}
        speech.transcribe.assert_not_called()
        await stt.send(bytes(640) * 40)
        event = await asyncio.wait_for(anext(events), 1)
        assert event['text'] == 'Hello, привет' and event['endpoint'] and event['final']
        call = speech.transcribe.call_args
        assert call.args[1:] == ('audio/wav', 'trial.wav')
        assert call.kwargs == {'language': None}
        with wave.open(io.BytesIO(call.args[0]), 'rb') as audio:
            assert (audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) == (1, 2, 16000)
            assert audio.getnframes() >= 320 * 48


@pytest.mark.asyncio
async def test_groq_call_bounds_long_utterance_without_declaring_turn_complete():
    from app.stt import SttResult
    from app.trial_providers import GroqTrialSTT
    speech = SimpleNamespace(transcribe=AsyncMock(return_value=SttResult(text='still speaking')))
    async with GroqTrialSTT(speech, SimpleNamespace(is_speech=lambda *args: True)) as stt:
        events = stt.events()
        await stt.send(b'\x01\x00' * (320 * stt.MAX_FRAMES))
        assert (await anext(events))['speech_start']
        result = await asyncio.wait_for(anext(events), 1)
        assert not result['endpoint']
        assert stt.speaking
        assert len(stt.segment) == 0


@pytest.mark.asyncio
async def test_groq_call_cancels_pending_transcription_on_disconnect():
    from app.trial_providers import GroqTrialSTT
    started, cancelled = asyncio.Event(), asyncio.Event()
    async def transcribe(*args, **kwargs):
        started.set()
        try:
            await asyncio.Event().wait()
        finally:
            cancelled.set()
    stt = GroqTrialSTT(SimpleNamespace(transcribe=transcribe), SimpleNamespace(is_speech=lambda *args: True))
    async with stt:
        await stt.send(b'\x01\x00' * (320 * 8))
        stt.emit_clip(True)
        await asyncio.wait_for(started.wait(), 1)
    assert cancelled.is_set()


@pytest.mark.asyncio
async def test_speech_activity_interrupts_before_transcription_returns():
    s = session()
    async def events():
        yield {'speech_start': True}
    s.stt = SimpleNamespace(events=events)
    s.pending['playing'] = {'cancelled': False}
    await s.recognize()
    s.socket.send_json.assert_awaited_with({'type': 'interrupt'})
    assert s.pending['playing']['cancelled']
    s.backend.command.assert_not_called()
