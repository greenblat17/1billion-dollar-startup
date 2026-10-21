import base64
import json

import httpx
import pytest
from fakeredis import FakeAsyncRedis

from app.call_start import CallStarter
from app.calls import CallStore
from app.characters import Characters, CharacterStore, KoraModel
from app.dialogue import MemoryDialogueStore
from app.hume_speech import HumeSpeech
from app.pipeline import ClipPipeline
from app.speech import SessionSpeech
from tests.conftest import FakeLlm, FakeStt, FakeTts, build_app
from tests.test_call_start import Model, Personalization


class KoraSpeech(FakeTts):
    async def synthesize(self, text, **kwargs):
        return await super().synthesize(text)


@pytest.mark.asyncio
async def test_character_persists_without_affecting_other_users():
    redis = FakeAsyncRedis(decode_responses=True)
    store = CharacterStore(redis)
    await store.set('tg-1', 'kora')
    assert await CharacterStore(redis).get('tg-1') == 'kora'
    assert await store.get('tg-2') == 'speaky'
    with pytest.raises(ValueError):
        await store.set('tg-1', 'unknown')
    await redis.aclose()


@pytest.mark.asyncio
async def test_kora_routing_history_and_roleplay_isolation():
    dialogue = MemoryDialogueStore(40, 86400)
    ordinary, kora = FakeLlm(), FakeLlm()
    voice, kora_voice = FakeTts(), KoraSpeech()
    characters = Characters(CharacterStore(), kora, kora_voice)
    await characters.store.set('tg-1', 'kora')
    await dialogue.record_turn('tg-1', 'Original topic', 'Original reply')
    pipeline = ClipPipeline(FakeStt(['Travel', 'Cooking']), ordinary, voice, dialogue,
                            calls=CallStore(), characters=characters)
    result = await pipeline.run('tg-1', b'x', 'audio/ogg', 'voice.ogg')
    assert result.reply_text == 'Got it: Travel'
    assert not ordinary.calls and not voice.texts
    assert kora.calls == [([], 'Travel')]
    assert kora_voice.texts == ['Got it: Travel']
    assert [t.content for t in await dialogue.history('tg-1')] == ['Original topic', 'Original reply']
    assert len(await dialogue.history('tg-1:kora')) == 2
    await pipeline.calls.open('tg-1', {'kind': 'custom', 'description': 'A cafe'})
    await pipeline.run('tg-1', b'x', 'audio/ogg', 'voice.ogg')
    assert ordinary.calls[-1][1] == 'Cooking'
    assert len(kora.calls) == 1
    assert len(await dialogue.history('tg-1:kora')) == 2


@pytest.mark.asyncio
async def test_kora_failed_speech_does_not_advance_history_or_fallback():
    class FailedSpeech:
        async def synthesize(self, *args, **kwargs):
            raise RuntimeError('unavailable')
    dialogue = MemoryDialogueStore(40, 86400)
    characters = Characters(CharacterStore(), FakeLlm(), FailedSpeech())
    await characters.store.set('tg-1', 'kora')
    ordinary = FakeLlm()
    pipeline = ClipPipeline(FakeStt(['Hello']), ordinary, FakeTts(), dialogue, characters=characters)
    with pytest.raises(RuntimeError, match='unavailable'):
        await pipeline.run('tg-1', b'x', 'audio/ogg', 'voice.ogg')
    assert await dialogue.history('tg-1:kora') == []
    assert not ordinary.calls


@pytest.mark.asyncio
async def test_opening_and_delivery_use_kora_history_once():
    dialogue = MemoryDialogueStore(40, 86400)
    model, voice = FakeLlm(), KoraSpeech()
    characters = Characters(CharacterStore(), model, voice)
    await characters.store.set('tg-1', 'kora')
    starter = CallStarter(CallStore(), Model(), Personalization(), dialogue, SessionSpeech(FakeTts()), characters)
    first = await starter.start('tg-1')
    await starter.start('tg-1')
    assert len(model.calls) == 1
    assert base64.b64decode(first['audioBase64']).startswith(b'OggS')
    await starter.delivered(first['callId'])
    await starter.delivered(first['callId'])
    assert len(await dialogue.history('tg-1:kora')) == 2
    assert await dialogue.history('tg-1') == []


@pytest.mark.asyncio
async def test_character_api_auth_unavailable_busy_and_active_call():
    app, *_ = build_app()
    chars = app.state.pipeline.characters
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as client:
        assert (await client.get('/internal/character/tg-1')).status_code == 401
        client.headers['X-Internal-Token'] = 'test-internal-token'
        path = '/internal/character'
        payload = {'sessionId': 'tg-1', 'character': 'kora'}
        assert (await client.post(path, json=payload)).json()['blocked'] == 'unavailable'
        chars.model, chars.speech = FakeLlm(), KoraSpeech()
        job = app.state.jobs.create('tg-1')
        assert (await client.post(path, json=payload)).json()['blocked'] == 'active_conversation'
        job.status = 'ok'
        opened = await app.state.pipeline.calls.open('tg-1')
        await app.state.pipeline.calls.save_opening(opened['callId'], 'Hello')
        await app.state.pipeline.calls.mark_opening_delivered(opened['callId'])
        assert (await client.post(path, json=payload)).json()['blocked'] == 'active_conversation'
        await app.state.pipeline.calls.seal('tg-1')
        assert (await client.post(path, json=payload)).json()['character'] == 'kora'
        assert (await client.get(path + '/tg-2')).json()['character'] == 'speaky'
        await app.state.pipeline.calls.open('tg-1')  # Failed/undelivered next opening.
        switched = await client.post(path, json={**payload, 'character': 'speaky'})
        assert switched.json()['character'] == 'speaky'
        assert await app.state.pipeline.calls.open_call_today('tg-1') is None
        assert (await client.post(path, json={**payload, 'character': 'invalid'})).status_code == 400


@pytest.mark.asyncio
async def test_planner_and_writer_preserve_history_and_brief(monkeypatch):
    from app.dialogue import ChatMessage
    model = KoraModel(None, 'test')
    calls = []
    plan = dict(interest='engaged', evidence='User shares a worry', topic='Work', direction='stay',
                move='contribute', tone='Warm', contribution='Small teams can make work feel personal.')
    async def complete(messages, **kwargs):
        calls.append((messages, kwargs))
        return json.dumps(plan) if len(calls) == 1 else 'Small teams can make work feel personal.'
    monkeypatch.setattr(model, '_complete', complete)
    reply = await model.complete_reply([ChatMessage('assistant', 'How is work?')], 'We have a small team')
    assert reply == plan['contribution']
    assert json.loads(calls[0][0][-1]['content'])['user'] == 'We have a small team'
    assert plan['contribution'] in calls[1][0][0]['content']
    assert calls[1][0][-2:] == [{'role': 'assistant', 'content': 'How is work?'},
                               {'role': 'user', 'content': 'We have a small team'}]


@pytest.mark.asyncio
@pytest.mark.parametrize('ending,text,valid', [(True, 'Hello.', True), (False, 'Hello.', False), (True, 'Wrong.', False)])
@pytest.mark.parametrize('warning', [None, 'W0107', 'W0108', 'E9999'])
async def test_hume_accepts_only_complete_exact_audio(monkeypatch, ending, text, valid, warning):
    sent = []
    messages = [{'type': 'assistant_message', 'message': {'content': text}},
                {'type': 'audio_output', 'data': base64.b64encode(b'wav').decode()}]
    if warning:
        messages.insert(0, {'type': 'error', 'code': warning})
    valid = valid and warning != 'E9999'
    if ending:
        messages.append({'type': 'assistant_end'})
    class Socket:
        async def __aenter__(self): return self
        async def __aexit__(self, *args): pass
        async def send(self, raw): sent.append(json.loads(raw))
        def __aiter__(self):
            async def items():
                for message in messages: yield json.dumps(message)
            return items()
    monkeypatch.setattr('app.hume_speech.websockets.connect', lambda *a, **k: Socket())
    speech = HumeSpeech('secret', 'config')
    async def convert(payload, args): return b'OggSchecked' if 'ogg' in args else b'pcm'
    monkeypatch.setattr(speech, '_ffmpeg', convert)
    if valid:
        audio = await speech.synthesize('Hello.')
        assert audio.data == b'OggSchecked'
    else:
        with pytest.raises(RuntimeError): await speech.synthesize('Hello.')
    assert [m['type'] for m in sent] == ['pause_assistant_message', 'session_settings', 'user_input',
                                        'assistant_input', 'resume_assistant_message']
