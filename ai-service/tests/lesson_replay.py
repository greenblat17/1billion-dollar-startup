"""Drive the public Mini App socket with deterministic provider and audio boundaries."""
import asyncio
import base64
import json
from contextlib import asynccontextmanager
from dataclasses import dataclass
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

from fastapi import FastAPI

from app import trial_api

FIRST = 'I like quiet hiking trails.'
INTERRUPTED = 'This text was never heard.'
RESUMED = 'Tell me about your favorite route.'
STOP = 'Давай закончим урок.'
GOODBYE = 'До встречи!'
HISTORY = [('user', FIRST), ('assistant', RESUMED), ('user', STOP), ('assistant', GOODBYE)]
PCM = b'\x01\x00' * 2400
REPLY_PCM = PCM[:480]  # 10 ms, below the relay playback tolerance.
SETTINGS = SimpleNamespace(openai_realtime_api_key='never-live', trial_realtime_model='replay',
                           trial_realtime_voice='marin')


class Provider:
    def __init__(self):
        self.inbound = asyncio.Queue()
        self.outbound = asyncio.Queue()
        self.session = None

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        pass

    def __aiter__(self):
        return self

    async def __anext__(self):
        return await self.recv()

    async def recv(self):
        return json.dumps(await self.inbound.get())

    async def send(self, raw):
        message = json.loads(raw)
        if message['type'] == 'session.update':
            self.session = message['session']
            self.emit('session.updated')
        self.outbound.put_nowait(message)

    def emit(self, kind, **fields):
        self.inbound.put_nowait({'type': kind, **fields})

    async def expect(self, kind):
        while (message := await self.outbound.get())['type'] == 'session.update':
            pass
        assert message['type'] == kind, message
        return message

    def audio(self, item, transcript, *, complete=True):
        self.emit('response.output_item.added', item={'id': item, 'type': 'message'})
        self.emit('response.output_audio.delta', item_id=item, content_index=0,
                  delta=base64.b64encode(REPLY_PCM).decode())
        self.emit('response.output_audio_transcript.done', item_id=item, transcript=transcript)
        if complete:
            self.emit('response.output_audio.done', item_id=item)
            self.done()

    def done(self, *output, status='completed'):
        self.emit('response.done', response={'status': status, 'output': output})


class Browser:
    def __init__(self):
        self.inbound = asyncio.Queue()
        self.outbound = asyncio.Queue()
        self.events = []

    def control(self, kind, **fields):
        self.inbound.put_nowait({'type': 'websocket.receive', 'text': json.dumps({'type': kind, **fields})})

    def auth(self, lesson_id, init_data):
        self.inbound.put_nowait({'type': 'websocket.receive', 'text': json.dumps({
            'lessonId': lesson_id, 'attemptId': uuid4().hex,
            'initData': init_data, 'protocol': 'realtime-pcm-v1',
        })})

    async def expect(self, kind):
        message = await self.outbound.get()
        if 'bytes' in message:
            assert kind == 'pcm'
            assert message['bytes'] == REPLY_PCM
        elif 'text' in message:
            message = json.loads(message['text'])
            assert message['type'] == kind, message
        else:
            assert message['type'] == kind, message
        self.events.append(kind)
        return message

    async def playback(self, item, *, complete):
        assert (await self.expect('audio_start'))['id'] == item
        await self.expect('pcm')
        if complete:
            assert (await self.expect('audio_end'))['id'] == item
            self.control('played', id=item, complete=True, playedMs=10)


@asynccontextmanager
async def connect(app, browser):
    scope = {'type': 'websocket', 'asgi': {'version': '3.0'}, 'scheme': 'ws',
             'path': '/miniapp/voice', 'query_string': b'', 'headers': [],
             'client': ('127.0.0.1', 1), 'server': ('localhost', 80), 'subprotocols': []}
    browser.inbound.put_nowait({'type': 'websocket.connect'})
    task = asyncio.create_task(app(scope, browser.inbound.get, browser.outbound.put))
    try:
        await browser.expect('websocket.accept')
        yield browser
    finally:
        browser.inbound.put_nowait({'type': 'websocket.disconnect', 'code': 1006})
        try:
            await asyncio.wait_for(task, 5)
        finally:
            task.cancel()


async def speak(browser, provider, kind, item, text, *, interrupt=None):
    browser.inbound.put_nowait({'type': 'websocket.receive', 'bytes': PCM})
    assert base64.b64decode((await provider.expect('input_audio_buffer.append'))['audio']) == PCM
    provider.emit('input_audio_buffer.speech_started', item_id=item, audio_start_ms=0)
    if interrupt:
        await browser.expect('interrupt')
        browser.control('played', id=interrupt, complete=False, playedMs=0)
        truncate = await provider.expect('conversation.item.truncate')
        assert {key: truncate[key] for key in ('item_id', 'content_index', 'audio_end_ms')} == {
            'item_id': interrupt, 'content_index': 0, 'audio_end_ms': 0}
        provider.emit('conversation.item.truncated', item_id=interrupt)
        provider.done(status='cancelled')
    provider.emit('input_audio_buffer.speech_stopped', item_id=item, audio_end_ms=100)
    provider.emit('input_audio_buffer.committed', item_id=item)
    await browser.expect('user_turn_complete')
    if kind == 'regular':
        await provider.expect('response.create')
    provider.emit('conversation.item.input_audio_transcription.completed', item_id=item, transcript=text)
    saved = await provider.expect('conversation.item.create')
    source = json.loads(saved['item']['content'][0]['text'].split(': ', 1)[1])
    assert source['body'] == text
    if kind == 'trial':
        await provider.expect('response.create')
    return source['id']


@dataclass
class Replay:
    browser_events: list[list[str]]
    archived: list[tuple[str, bytes]]


async def replay(monkeypatch, backend, contexts, lesson_id, init_data, kind, after_disconnect):
    providers = [Provider(), Provider()]
    next_provider = iter(providers)
    session_type = trial_api.RealtimeTrialSession
    monkeypatch.setattr(trial_api, 'RealtimeTrialSession', lambda *args: session_type(
        *args, connector=lambda _: next(next_provider)))
    archive = AsyncMock()
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({
        'topicId': None, 'problemId': None, 'outcome': None, 'asrUncertain': False,
        'evidence': None, 'rationale': None,
    })))
    app = FastAPI()
    install = trial_api.install_trial_api
    install(app, backend, contexts, model, None, SETTINGS, archive)
    first, second = Browser(), Browser()
    async with asyncio.timeout(25):
        provider = providers[0]
        async with connect(app, first):
            first.auth(lesson_id, init_data)
            assert (await first.expect('connected'))['lessonId'] == lesson_id
            await provider.expect('response.create')
            provider.audio('interrupted', INTERRUPTED, complete=False)
            await first.playback('interrupted', complete=False)
            await speak(first, provider, kind, 'learner-1', FIRST, interrupt='interrupted')
        await after_disconnect()

        provider = providers[1]
        async with connect(app, second):
            second.auth(lesson_id, init_data)
            assert (await second.expect('connected'))['lessonId'] == lesson_id
            await provider.expect('response.create')
            assert FIRST in provider.session['instructions']
            assert INTERRUPTED not in provider.session['instructions']
            provider.audio('resumed', RESUMED)
            await second.playback('resumed', complete=True)
            source = await speak(second, provider, kind, 'learner-2', STOP)
            arguments = ({'reason': 'learner_requested', 'sourceId': source, 'evidence': STOP}
                         if kind == 'trial' else {})
            provider.done({'type': 'function_call', 'call_id': 'finish-call', 'name': 'finish_after_reply',
                           'arguments': json.dumps(arguments, ensure_ascii=False)})
            command = await provider.expect('conversation.item.create')
            result = json.loads(command['item']['output'])
            assert result.get('ok' if kind == 'trial' else 'readyForGoodbye') is True, result
            await provider.expect('response.create')
            provider.audio('goodbye', GOODBYE)
            await second.playback('goodbye', complete=True)
            await second.expect('finished')
            await second.expect('websocket.close')
    archived = [call.args for call in archive.put.await_args_list]
    assert [data for _, data in archived] == [REPLY_PCM, PCM, REPLY_PCM, PCM, REPLY_PCM]
    return Replay([first.events, second.events], archived)
