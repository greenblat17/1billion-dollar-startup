import asyncio
import base64
import json
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.foundation_client import BackendFailure
from app.trial_api import install_trial_api
from app.trial_realtime import RealtimeTrialSession, RealtimeProviderError, open_realtime

SETTINGS = SimpleNamespace(openai_realtime_api_key='test-never-live', trial_realtime_model='gpt-realtime-2.1', trial_realtime_voice='marin')
ADMITTED = {'scope': 'private-scope', 'lesson': {'id': 'lesson', 'starts_at': '2026-11-12T12:00:00+00:00', 'duration_minutes': 15}, 'callSessionId': 'episode'}


def session():
    backend = SimpleNamespace(command=AsyncMock(return_value={'objectKey': 'trial/key'}))
    socket = SimpleNamespace(send_json=AsyncMock(), send_bytes=AsyncMock())
    context = SimpleNamespace(build=AsyncMock(return_value=SimpleNamespace(data={'goal': 'work'}, history=[{'role': 'user', 'content': 'Known goal'}])))
    store = SimpleNamespace(put=AsyncMock(), delete=AsyncMock())
    s = RealtimeTrialSession(socket, backend, context, store, ADMITTED, SETTINGS)
    s.provider = SimpleNamespace(send=AsyncMock())
    return s


def sent(s):
    return [json.loads(c.args[0]) for c in s.provider.send.call_args_list]


async def output(s, key='reply', finish=False):
    s.finish_next = finish
    await s.provider_event({'type': 'response.output_item.added', 'item': {'type': 'message', 'id': key}})
    await s.provider_event({'type': 'response.output_audio.delta', 'item_id': key, 'content_index': 0,
                            'delta': base64.b64encode(b'\0\0' * 2400).decode()})
    s.items[key]['sent_at'] = time.monotonic() - 1


@pytest.mark.asyncio
async def test_audio_archive_precedes_forward_and_metadata_is_24khz():
    s = session()
    order = []
    s.store.put.side_effect = lambda *args: order.append('archive')
    s.socket.send_bytes.side_effect = lambda *args: order.append('browser')
    await output(s)
    assert order == ['archive', 'browser']
    metadata = s.backend.command.call_args_list[0].args[3]['metadata']
    assert metadata['sampleRate'] == 24000 and metadata['utteranceId'] == 'reply'
    s.input_buffer.extend(b'\0\0' * 2400)
    await s.flush_input()
    assert sent(s)[-1]['type'] == 'input_audio_buffer.append'
    assert not s.input_buffer


@pytest.mark.asyncio
async def test_archive_failure_stops_forwarding_and_no_provider_retry():
    s = session()
    s.store.put.side_effect = RuntimeError('storage_down')
    with pytest.raises(RuntimeError):
        await output(s)
    s.socket.send_bytes.assert_not_awaited()
    s.input_buffer.extend(b'\0\0')
    with pytest.raises(RuntimeError):
        await s.flush_input()
    assert not sent(s)


@pytest.mark.asyncio
async def test_interruption_truncates_before_next_response_and_never_saves_unheard_text():
    s = session()
    await output(s, finish=True)
    await s.provider_event({'type': 'response.output_audio_transcript.done', 'item_id': 'reply', 'transcript': 'UNHEARD SECRET ENDING'})
    await s.provider_event({'type': 'input_audio_buffer.speech_started'})
    await s.provider_event({'type': 'input_audio_buffer.committed', 'item_id': 'user1'})
    assert not any(e['type'] == 'response.create' for e in sent(s))
    await s.settle('reply', {'complete': True, 'playedMs': 40})
    assert sent(s)[-1] == {'type': 'conversation.item.truncate', 'item_id': 'reply', 'content_index': 0, 'audio_end_ms': 40}
    assert not s.ended
    await s.provider_event({'type': 'conversation.item.truncated', 'item_id': 'reply'})
    assert sent(s)[-1]['type'] == 'response.create'
    assert 'UNHEARD' not in str(s.backend.command.call_args_list)
    await s.settle('reply', {'complete': True, 'playedMs': 100})
    assert sum(e['type'] == 'conversation.item.truncate' for e in sent(s)) == 1


@pytest.mark.asyncio
async def test_finish_requires_provider_done_audio_transcript_playback_and_input_transcript():
    s = session()
    s.response_active = True
    s.pending_inputs.add('user1')
    await output(s, finish=True)
    await s.settle('reply', {'complete': True, 'playedMs': 100})
    assert not s.ended  # early forged complete
    await s.provider_event({'type': 'response.output_audio.done', 'item_id': 'reply'})
    await s.settle('reply', {'complete': True, 'playedMs': 100})
    assert not s.ended  # transcript late
    await s.provider_event({'type': 'response.output_audio_transcript.done', 'item_id': 'reply', 'transcript': 'До встречи!'})
    assert not s.ended  # response and last learner transcript late
    await s.provider_event({'type': 'response.done', 'response': {'status': 'completed', 'output': []}})
    assert not s.ended
    await s.provider_event({'type': 'conversation.item.input_audio_transcription.completed', 'item_id': 'user1', 'transcript': 'Было комфортно'})
    assert s.ended
    assert s.backend.command.call_args.args[2:] == ('voice.end', {'lessonId': 'lesson', 'callSessionId': 'episode', 'reason': 'natural_goodbye'})


@pytest.mark.asyncio
async def test_duplicate_events_and_transcriptions_do_not_duplicate_history():
    s = session()
    event = {'type': 'conversation.item.input_audio_transcription.completed', 'item_id': 'one', 'transcript': 'Hello', 'event_id': 'event1'}
    await s.provider_event(event)
    await s.provider_event(event)
    await s.provider_event(dict(event, event_id='event2'))
    assert s.backend.command.await_count == 1
    assert s.backend.command.call_args.args[3]['sourceId'] == 'episode:one'


@pytest.mark.asyncio
async def test_unknown_and_duplicate_tools_cannot_end_or_execute_business_operations():
    s = session()
    for name, args in [('voice.end', '{}'), ('finish_after_reply', '{"lessonId":"other"}')]:
        await s.tool({'name': name, 'arguments': args, 'call_id': name})
    assert not s.finish_next
    s.backend.command.assert_not_awaited()
    await s.tool({'name': 'finish_after_reply', 'arguments': '{}', 'call_id': 'valid'})
    assert s.finish_next
    count = len(sent(s))
    await s.tool({'name': 'finish_after_reply', 'arguments': '{}', 'call_id': 'valid'})
    assert len(sent(s)) == count
    assert not s.ended


@pytest.mark.asyncio
async def test_browser_cannot_inject_provider_events_and_mute_flushes_tail():
    s = session()
    s.socket.receive = AsyncMock(side_effect=[
        {'type': 'websocket.receive', 'bytes': b'\0\0' * 200},
        {'type': 'websocket.receive', 'text': json.dumps({'type': 'mute', 'value': True})},
        {'type': 'websocket.receive', 'bytes': b'\0\0' * 200},
        {'type': 'websocket.receive', 'text': json.dumps({'type': 'response.done', 'response': {'status': 'completed'}})},
        {'type': 'websocket.receive', 'text': json.dumps({'type': 'end'})},
    ])
    await s.receive()
    assert s.store.put.await_count == 1
    assert len(sent(s)) == 1 and sent(s)[0]['type'] == 'input_audio_buffer.append'
    assert s.backend.command.call_args.args[3]['reason'] == 'end_button'


@pytest.mark.asyncio
async def test_browser_metrics_do_not_interrupt_session(caplog):
    s = session()
    s.socket.receive = AsyncMock(side_effect=[
        *[{'type': 'websocket.receive', 'text': json.dumps({
            'type': 'metric', 'name': name, 'milliseconds': 123,
        })} for name in ('join', 'first_audio', 'barge_in')],
        {'type': 'websocket.receive', 'text': json.dumps({'type': 'end'})},
    ])
    with caplog.at_level('INFO', logger='app.diagnostics'):
        await s.receive()
    assert s.ended
    assert s.backend.command.call_args.args[3]['reason'] == 'end_button'
    for name in ('join', 'first_audio', 'barge_in'):
        assert f'"metric": "{name}"' in caplog.text


class FakeProvider:
    def __init__(self):
        self.events = asyncio.Queue()
        self.sent = []
        self.closed = False
        self.responses = 0
    async def __aenter__(self): return self
    async def __aexit__(self, *args): self.closed = True
    async def recv(self): return json.dumps(await self.events.get())
    def __aiter__(self): return self
    async def __anext__(self): return await self.recv()
    async def send(self, raw):
        data = json.loads(raw)
        self.sent.append(data)
        if data['type'] == 'session.update':
            await self.events.put({'type': 'session.updated'})
        if data['type'] == 'response.create':
            self.responses += 1
            if self.responses == 1:
                await self.events.put({'type': 'response.done', 'response': {'status': 'completed', 'output': [
                    {'type': 'function_call', 'name': 'finish_after_reply', 'arguments': '{}', 'call_id': 'end'}]}})
            else:
                for event in [
                    {'type': 'response.output_item.added', 'item': {'id': 'goodbye', 'type': 'message'}},
                    {'type': 'response.output_audio.delta', 'item_id': 'goodbye', 'content_index': 0, 'delta': base64.b64encode(b'\0\0' * 100).decode()},
                    {'type': 'response.output_audio_transcript.done', 'item_id': 'goodbye', 'transcript': 'До встречи!'},
                    {'type': 'response.output_audio.done', 'item_id': 'goodbye'},
                    {'type': 'response.done', 'response': {'status': 'completed', 'output': []}},
                ]:
                    await self.events.put(event)


def route_app(monkeypatch, failure=None):
    import app.trial_api as api
    provider = FakeProvider()
    def factory(*args):
        return RealtimeTrialSession(*args, connector=lambda settings: provider)
    monkeypatch.setattr(api, 'RealtimeTrialSession', factory)
    backend = SimpleNamespace(_request=AsyncMock(return_value=ADMITTED, side_effect=failure), command=AsyncMock(return_value={'objectKey': 'trial/test'}))
    context = SimpleNamespace(build=AsyncMock(return_value=SimpleNamespace(data={}, history=[{'role': 'user', 'content': 'Known goal'}])))
    store = SimpleNamespace(put=AsyncMock(), delete=AsyncMock(), check_credentials=AsyncMock())
    app = FastAPI()
    install_trial_api(app, backend, context, None, None, SETTINGS, store)
    return app, provider, backend


def test_real_route_realtime_audio_goodbye_and_reconnect_context(monkeypatch):
    app, provider, backend = route_app(monkeypatch)
    with TestClient(app) as client:
        assert client.get('/miniapp').status_code == 404
        with client.websocket_connect('/miniapp/voice') as socket:
            socket.send_json({'initData': 'signed', 'lessonId': 'lesson', 'attemptId': 'new', 'protocol': 'realtime-pcm-v1'})
            assert socket.receive_json()['type'] == 'connected'
            assert socket.receive_json()['type'] == 'audio_start'
            assert socket.receive_bytes() == b'\0\0' * 100
            assert socket.receive_json()['type'] == 'audio_end'
            socket.send_json({'type': 'played', 'id': 'goodbye', 'complete': True, 'playedMs': 10})
            assert socket.receive_json()['type'] == 'finished'
    assert provider.closed
    configuration = provider.sent[0]['session']
    assert configuration['audio']['output']['voice'] == 'marin'
    assert 'Known goal' in configuration['instructions']
    assert 'Return JSON only' not in configuration['instructions']
    assert any(c.args[2] == 'voice.end' and c.args[3]['reason'] == 'natural_goodbye' for c in backend.command.call_args_list)


@pytest.mark.parametrize('code', ['not_owned', 'invalid_telegram_auth', 'active_episode', 'too_early', 'stale_episode'])
def test_admission_rejection_never_connects_provider(monkeypatch, code):
    app, provider, backend = route_app(monkeypatch, BackendFailure(code, 403))
    with TestClient(app) as client:
        with client.websocket_connect('/miniapp/voice') as socket:
            socket.send_json({'initData': 'signed', 'lessonId': 'other', 'attemptId': 'new', 'protocol': 'realtime-pcm-v1'})
            assert socket.receive_json()['code'] == code
    assert not provider.sent
    backend.command.assert_not_awaited()


def test_old_frontend_rejected_before_admission(monkeypatch):
    app, provider, backend = route_app(monkeypatch)
    with TestClient(app) as client:
        with client.websocket_connect('/miniapp/voice') as socket:
            socket.send_json({'initData': 'signed', 'lessonId': 'lesson', 'attemptId': 'new'})
            assert socket.receive_json()['code'] == 'client_update_required'
    assert not provider.sent
    backend._request.assert_not_awaited()


@pytest.mark.asyncio
async def test_provider_failure_after_admission_releases_episode(monkeypatch):
    s = session()
    class Failure:
        async def __aenter__(self): raise RealtimeProviderError({'code': 'insufficient_quota'})
        async def __aexit__(self, *args): pass
    s.connector = lambda settings: Failure()
    with pytest.raises(RealtimeProviderError):
        await s.run()
    assert s.backend.command.call_args.args[3]['reason'] == 'technical_disconnect'


@pytest.mark.asyncio
async def test_configuration_has_no_provider_key_and_reconnect_uses_new_scope():
    s = session()
    s.provider = FakeProvider()
    await s.configure()
    assert 'private-scope' not in json.dumps(s.provider.sent)
    assert 'test-never-live' not in json.dumps(s.provider.sent)
    s.contexts.build.assert_awaited_once_with('private-scope', 'voice', 'lesson')
    detection = s.provider.sent[0]['session']['audio']['input']['turn_detection']
    assert detection['create_response'] is False
    assert detection['eagerness'] == 'medium'


@pytest.mark.asyncio
async def test_three_successive_turns_and_muted_speech_commit():
    s = session()
    for index in range(3):
        key = f'answer{index}'
        await s.provider_event({'type': 'input_audio_buffer.committed', 'item_id': f'user{index}'})
        await output(s, key)
        await s.provider_event({'type': 'response.output_audio_transcript.done', 'item_id': key, 'transcript': f'Answer {index}'})
        await s.provider_event({'type': 'response.output_audio.done', 'item_id': key})
        await s.provider_event({'type': 'response.done', 'response': {'status': 'completed', 'output': []}})
        await s.settle(key, {'complete': True, 'playedMs': 100})
    assert sum(e['type'] == 'response.create' for e in sent(s)) == 3
    assert sum(c.args[2] == 'voice.append' for c in s.backend.command.call_args_list) == 3
    s.muted = True
    await s.provider_event({'type': 'input_audio_buffer.speech_started'})
    assert sent(s)[-1]['type'] == 'input_audio_buffer.commit'
    await s.commit_muted_speech()
    assert sum(e['type'] == 'input_audio_buffer.commit' for e in sent(s)) == 1


@pytest.mark.asyncio
async def test_forged_duration_is_bounded_and_no_ack_uses_zero_truncation():
    s = session()
    await output(s)
    await s.interrupt()
    await s.settle('reply', {'complete': False, 'playedMs': float('nan')})
    assert sent(s)[-1]['audio_end_ms'] == 0
    assert not any(c.args[2] == 'voice.append' for c in s.backend.command.call_args_list)


@pytest.mark.asyncio
async def test_input_flood_rejected_before_forwarding():
    s = session()
    s.received_bytes = 200000
    s.socket.receive = AsyncMock(return_value={'type': 'websocket.receive', 'bytes': b'\0\0' * 480})
    with pytest.raises(ValueError, match='audio_rate_exceeded'):
        await s.receive()
    s.provider.send.assert_not_awaited()


@pytest.mark.asyncio
async def test_handshake_402_is_not_retried_and_logs_safe_provider_reason(monkeypatch, caplog):
    from websockets.exceptions import InvalidStatus
    from unittest.mock import Mock
    from app.diagnostics import event
    response = SimpleNamespace(status_code=402, reason_phrase='Payment Required',
        headers={'x-request-id': 'req-test'},
        body=b'{"error":{"code":"insufficient_quota","message":"Denied token=sk-secret-example"}}')
    class Denied:
        async def __aenter__(self): raise InvalidStatus(response)
        async def __aexit__(self, *args): pass
    connector = Mock(return_value=Denied())
    monkeypatch.setattr('app.trial_realtime.connect', connector)
    with pytest.raises(RealtimeProviderError) as caught:
        async with open_realtime(SETTINGS):
            pytest.fail('must not connect')
    event('provider_failed', error=caught.value)
    assert connector.call_count == 1
    assert 'insufficient_quota' in caplog.text and '402' in caplog.text
    assert 'sk-secret-example' not in caplog.text
    assert 'test-never-live' not in caplog.text


@pytest.mark.asyncio
async def test_latency_stages_use_matching_turn_and_measure_archive(monkeypatch, caplog):
    s = session()
    clock = [100.0]
    monkeypatch.setattr('app.trial_realtime.time', SimpleNamespace(monotonic=lambda: clock[0]))
    with caplog.at_level('INFO', logger='app.diagnostics'):
        await s.provider_event({'type': 'input_audio_buffer.speech_stopped', 'item_id': 'user1', 'audio_end_ms': 500})
        clock[0] = 100.1
        await s.provider_event({'type': 'input_audio_buffer.committed', 'item_id': 'user1'})
        clock[0] = 100.4
        async def slow_archive(*args):
            clock[0] += 0.2
        s.store.put.side_effect = slow_archive
        await output(s)
        s.log_audio_state({})
        s.log_audio_state({})  # Empty interval is valid.
    events = [json.loads(r.message.split('runtime_event ', 1)[1]) for r in caplog.records]
    latency = next(r for r in events if r['event'] == 'trial_realtime_first_audio')
    assert latency['turn_id'] == 'user1'
    assert latency['stop_to_commit_ms'] == 100
    assert latency['commit_to_request_ms'] == 0
    assert latency['request_to_audio_ms'] == 300
    assert latency['first_chunk_archive_ms'] == 200
    assert latency['commit_to_forward_ms'] == 500
    archive = [r['archive'] for r in events if r['event'] == 'trial_audio_state']
    assert archive == [{'assistant': {'count': 1, 'mean_ms': 200, 'max_ms': 200}}, {}]
