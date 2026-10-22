import base64
import json

import httpx
import pytest
from fakeredis import FakeAsyncRedis

from app.call_start import CallStarter, KORA_H_OPENING_REQUEST
from app.audit_artifacts import bind_writer, reset_writer
from app.calls import CallStore
from app.characters import Characters, CharacterStore, KoraHModel, KoraModel
from app.dialogue import MemoryDialogueStore
from app.hume_speech import HumeSpeech
from app.personalization import context_note
from app.pipeline import ClipPipeline
from app.speech import SessionSpeech
from app.tts import TtsAudio
from tests.conftest import FakeLlm, FakeStt, FakeTts, build_app
from tests.test_call_start import Model, Personalization


class KoraSpeech(FakeTts):
    async def synthesize(self, text, **kwargs):
        self.last_speed = kwargs.get("speed")
        self.last_guided = kwargs.get("guided")
        self.last_warm = kwargs.get("warm")
        audio = await super().synthesize(text)
        return TtsAudio(audio.data, audio.content_type, getattr(self, 'spoken', None) or text)


@pytest.mark.asyncio
async def test_character_persists_without_affecting_other_users():
    redis = FakeAsyncRedis(decode_responses=True)
    store = CharacterStore(redis)
    await store.set('tg-1', 'kora')
    assert await CharacterStore(redis).get('tg-1') == 'kora'
    await store.set('tg-1', 'kora_h')
    assert await CharacterStore(redis).get('tg-1') == 'kora_h'
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
    await pipeline.speech.speeds.set('tg-1', 0.8)
    result = await pipeline.run('tg-1', b'x', 'audio/ogg', 'voice.ogg')
    assert result.reply_text == 'Got it: Travel'
    assert not ordinary.calls and not voice.texts
    assert kora.calls == [([], 'Travel')]
    assert kora_voice.texts == ['Got it: Travel']
    assert kora_voice.last_speed == 0.8
    assert kora_voice.last_warm is False
    assert [t.content for t in await dialogue.history('tg-1')] == ['Original topic', 'Original reply']
    assert len(await dialogue.history('tg-1:kora')) == 2
    await pipeline.calls.open('tg-1', {'kind': 'custom', 'description': 'A cafe'})
    await pipeline.run('tg-1', b'x', 'audio/ogg', 'voice.ogg')
    assert ordinary.calls[-1][1] == 'Cooking'
    assert len(kora.calls) == 1
    assert len(await dialogue.history('tg-1:kora')) == 2


@pytest.mark.asyncio
async def test_kora_h_has_separate_history_and_exact_hume_delivery():
    dialogue = MemoryDialogueStore(40, 86400)
    model_h, voice = FakeLlm(), KoraSpeech()
    characters = Characters(CharacterStore(), FakeLlm(), voice, model_h)
    await characters.store.set('tg-1', 'kora_h')
    await dialogue.record_turn('tg-1:kora', 'Old Kora topic', 'Old Kora reply')
    pipeline = ClipPipeline(FakeStt(['I like walking home.', 'It gives me time to think.', 'A cafe']),
                            FakeLlm(), FakeTts(), dialogue, calls=CallStore(), characters=characters)
    pipeline.personalization = Personalization()
    await pipeline.speech.speeds.set('tg-1', 0.9)
    first = await pipeline.run('tg-1', b'x', 'audio/ogg', 'voice.ogg')
    assert first.reply_text == 'Got it: I like walking home.'
    assert voice.last_guided is False and voice.last_speed == 0.9
    assert voice.last_warm is True
    assert model_h.calls == [([], 'I like walking home.')]
    assert model_h.profile_notes == ['Use verified memory only.']
    await pipeline.run('tg-1', b'x', 'audio/ogg', 'voice.ogg')
    assert model_h.calls[-1][0] == ['I like walking home.', first.reply_text]
    assert model_h.profile_notes == ['Use verified memory only.'] * 2
    assert len(await dialogue.history('tg-1:kora_h')) == 4
    assert len(await dialogue.history('tg-1:kora')) == 2
    await pipeline.calls.open('tg-1', {'kind': 'custom', 'description': 'A cafe'})
    await pipeline.run('tg-1', b'x', 'audio/ogg', 'voice.ogg')
    assert len(model_h.calls) == 2


@pytest.mark.asyncio
async def test_kora_records_spoken_reply_and_keeps_sonnet_draft_for_comparison():
    dialogue = MemoryDialogueStore(40, 86400)
    voice = KoraSpeech()
    voice.spoken = 'Hmm, travel sounds fun.'
    model = FakeLlm()
    characters = Characters(CharacterStore(), model, voice)
    await characters.store.set('tg-1', 'kora')
    pipeline = ClipPipeline(FakeStt(['Travel', 'Tell me more']), FakeLlm(), FakeTts(), dialogue,
                            characters=characters)
    artifacts = {}
    async def write(field, value):
        artifacts[field] = value
    token = bind_writer(write)
    try:
        result = await pipeline.run('tg-1', b'x', 'audio/ogg', 'voice.ogg')
        assert result.reply_text == voice.spoken
        assert artifacts['reply_draft'] == 'Got it: Travel'
        assert artifacts['reply'] == voice.spoken
        assert [m.content for m in await dialogue.history('tg-1:kora')] == ['Travel', voice.spoken]
        await pipeline.run('tg-1', b'x', 'audio/ogg', 'voice.ogg')
        assert model.calls[-1][0] == ['Travel', voice.spoken]
    finally:
        reset_writer(token)


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
    voice.spoken = 'Hey, what is on your mind today?'
    characters = Characters(CharacterStore(), model, voice)
    await characters.store.set('tg-1', 'kora')
    starter = CallStarter(CallStore(), Model(), Personalization(), dialogue, SessionSpeech(FakeTts()), characters)
    first = await starter.start('tg-1')
    await starter.start('tg-1')
    assert len(model.calls) == 1
    assert base64.b64decode(first['audioBase64']).startswith(b'OggS')
    assert first['question'] == voice.spoken
    saved = await starter.calls.get(first['callId'])
    assert saved['openingDraft'] == 'Got it: Let\'s start a conversation.'
    assert saved['openingQuestion'] == voice.spoken
    await starter.delivered(first['callId'])
    await starter.delivered(first['callId'])
    assert len(await dialogue.history('tg-1:kora')) == 2
    assert (await dialogue.history('tg-1:kora'))[-1].content == voice.spoken
    assert await dialogue.history('tg-1') == []


@pytest.mark.asyncio
async def test_kora_h_opening_uses_its_model_and_exact_voice():
    dialogue = MemoryDialogueStore(40, 86400)
    model_h, voice = FakeLlm(), KoraSpeech()
    characters = Characters(CharacterStore(), FakeLlm(), voice, model_h)
    await characters.store.set('tg-1', 'kora_h')
    starter = CallStarter(CallStore(), Model(), Personalization(), dialogue, SessionSpeech(FakeTts()), characters)
    first = await starter.start('tg-1')
    assert first['question'] == f"Got it: {KORA_H_OPENING_REQUEST}"
    assert model_h.calls == [([], KORA_H_OPENING_REQUEST)]
    assert model_h.profile_notes == ['Use verified memory only.']
    assert model_h.modes == ['opening']
    assert voice.last_guided is False
    assert voice.last_warm is True
    await starter.delivered(first['callId'])
    assert len(await dialogue.history('tg-1:kora_h')) == 2
    assert await dialogue.history('tg-1:kora') == []


@pytest.mark.asyncio
async def test_kora_h_opening_uses_only_recent_learner_context():
    dialogue = MemoryDialogueStore(40, 86400)
    model_h, voice = FakeLlm(), KoraSpeech()
    characters = Characters(CharacterStore(), FakeLlm(), voice, model_h)
    await characters.store.set('tg-1', 'kora_h')
    await dialogue.record_turn('tg-1:kora_h', "Let's start a practice conversation.", 'Hello!')
    for index in range(5):
        await dialogue.record_turn('tg-1:kora_h', f'Learner topic {index}', f'Reply {index}')
    await dialogue.record_turn('tg-1', 'Unrelated Speaky topic', 'Speaky reply')
    starter = CallStarter(CallStore(), Model(), Personalization(), dialogue, SessionSpeech(FakeTts()), characters)
    await starter.start('tg-1')
    seen = model_h.calls[0][0]
    assert seen == [item for index in range(1, 5) for item in (f'Learner topic {index}', f'Reply {index}')]
    assert model_h.profile_notes == ['Use verified memory only.']
    assert model_h.modes == ['opening']


@pytest.mark.asyncio
async def test_kora_h_opening_prompts_ground_follow_up_and_allow_check_in(monkeypatch):
    from app.dialogue import ChatMessage
    model = KoraHModel(None, 'test')
    calls = []
    plan = dict(interest='searching', evidence='Learner mentioned a project last time',
                topic='Project', direction='stay', move='question', tone='Warm',
                contribution='How did your project go?')
    async def complete(messages, **kwargs):
        calls.append(messages)
        return json.dumps(plan) if len(calls) == 1 else 'Hey! How are you? How did your project go?'
    monkeypatch.setattr(model, '_complete', complete)
    history = [ChatMessage('user', 'I am launching a project.')]
    reply = await model.complete_reply(history, KORA_H_OPENING_REQUEST,
                                       'Use verified memory only.', mode='opening')
    assert reply.startswith('Hey! How are you?')
    assert 'actually raised' in calls[0][0]['content']
    assert calls[0][1] == {'role': 'system', 'content': 'Use verified memory only.'}
    assert 'OPENING EXCEPTION' in calls[1][0]['content']
    assert calls[1][-2:] == [{'role': 'user', 'content': history[0].content},
                             {'role': 'user', 'content': KORA_H_OPENING_REQUEST}]


@pytest.mark.asyncio
async def test_kora_h_opening_retries_a_plan_without_a_question(monkeypatch):
    model = KoraHModel(None, 'test')
    calls = []
    plan = dict(interest='searching', evidence='No prior learner topic', topic='Today',
                direction='stay', move='contribute', tone='Warm', contribution='Today is a fresh start.')
    async def complete(messages, **kwargs):
        calls.append(kwargs['operation'])
        if len(calls) == 2:
            return json.dumps({**plan, 'move': 'question', 'contribution': 'What have you enjoyed today?'})
        return json.dumps(plan) if len(calls) == 1 else 'Hi! How are you? What have you enjoyed today?'
    monkeypatch.setattr(model, '_complete', complete)
    await model.complete_reply([], KORA_H_OPENING_REQUEST, mode='opening')
    assert calls == ['kora_h_plan', 'kora_h_plan', 'kora_h_reply']


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
        assert (await client.post(path, json={**payload, 'character': 'kora_h'})).json()['blocked'] == 'unavailable'
        chars.model, chars.speech = FakeLlm(), KoraSpeech()
        chars.model_h = FakeLlm()
        job = app.state.jobs.create('tg-1')
        assert (await client.post(path, json=payload)).json()['blocked'] == 'active_conversation'
        job.status = 'ok'
        opened = await app.state.pipeline.calls.open('tg-1')
        await app.state.pipeline.calls.save_opening(opened['callId'], 'Hello')
        await app.state.pipeline.calls.mark_opening_delivered(opened['callId'])
        assert (await client.post(path, json=payload)).json()['blocked'] == 'active_conversation'
        await app.state.pipeline.calls.seal('tg-1')
        assert (await client.post(path, json=payload)).json()['character'] == 'kora'
        assert (await client.post(path, json={**payload, 'character': 'kora_h'})).json()['character'] == 'kora_h'
        assert (await client.get(path + '/tg-1')).json()['koraHAvailable'] is True
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
@pytest.mark.parametrize('valid_on_retry', [True, False])
async def test_kora_h_planner_retry_and_direct_fallback(monkeypatch, valid_on_retry):
    model = KoraHModel(None, 'test')
    calls, artifacts = [], {}
    plan = dict(interest='engaged', evidence='User has an opinion', topic='Walking', direction='stay',
                move='contribute', tone='Warm', contribution='The walk can be a quiet break.')
    async def complete(messages, **kwargs):
        calls.append((messages, kwargs))
        if kwargs['operation'] == 'kora_h_plan':
            return json.dumps(plan) if valid_on_retry and len(calls) == 2 else 'not-json'
        return 'Maybe that walk is a quiet break.'
    async def write(field, value): artifacts[field] = value
    monkeypatch.setattr(model, '_complete', complete)
    profile_note = context_note({'person': {'interests': ['walking']},
                                 'proficiency': {'overall_cefr': 'B2'}}, 0, 0)
    token = bind_writer(write)
    try:
        reply = await model.complete_reply([], 'Walking home wastes my time.', profile_note)
    finally:
        reset_writer(token)
    assert reply == 'Maybe that walk is a quiet break.'
    assert [options['operation'] for _, options in calls] == ['kora_h_plan', 'kora_h_plan', 'kora_h_reply']
    assert 'look for a different but plausible way' in calls[0][0][0]['content']
    assert all(call[0][1] == {'role': 'system', 'content': profile_note} for call in calls)
    assert '"overall_cefr": "B2"' in profile_note
    assert 'A2 English' not in calls[0][0][0]['content']
    writer_prompt = calls[-1][0][0]['content']
    assert 'exact words that Hume will say aloud' in writer_prompt
    assert 'A2 English' not in writer_prompt
    saved_plan = json.loads(artifacts['conversation_plan'])
    if valid_on_retry:
        assert saved_plan['strategy'] == 'interest_alternative_view_v1'
        assert saved_plan['planner_retry'] is True
        assert plan['contribution'] in writer_prompt
    else:
        assert saved_plan['planner_fallback'] == 'invalid_plan'
        assert 'CURRENT TURN' not in writer_prompt


@pytest.mark.asyncio
@pytest.mark.parametrize('ending,text,valid', [(True, 'Hello.', True), (False, 'Hello.', False), (True, 'Wrong.', False)])
@pytest.mark.parametrize('speed', [0.8, 0.9, 1.0])
@pytest.mark.parametrize('warning', [None, 'W0107', 'W0108', 'E9999'])
async def test_hume_accepts_only_complete_exact_audio(monkeypatch, ending, text, valid, warning, speed):
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
    async def convert(payload, args):
        if 'ogg' in args:
            assert ('-af' in args) == (speed != 1.0)
            if speed != 1.0:
                assert args[args.index('-af') + 1] == f'atempo={speed}'
            return b'OggSchecked'
        return b'pcm'
    monkeypatch.setattr(speech, '_ffmpeg', convert)
    warm = speed == 0.9 and warning is None and ending and text == 'Hello.'
    if valid:
        audio = await speech.synthesize('Hello.', speed=speed, guided=False, warm=warm)
        assert audio.data == b'OggSchecked'
        assert audio.spoken_text == 'Hello.'
    else:
        with pytest.raises(RuntimeError): await speech.synthesize('Hello.', speed=speed, guided=False, warm=warm)
    assert [m['type'] for m in sent] == ['pause_assistant_message', 'session_settings', 'user_input',
                                        'assistant_input', 'resume_assistant_message']
    assert ('Vary your energy, pitch' in sent[1]['system_prompt']) is warm
    assert sent[3]['text'] == 'Hello.'


@pytest.mark.asyncio
async def test_hume_guided_delivery_returns_actual_spoken_words(monkeypatch):
    sent = []
    messages = [
        {'type': 'assistant_message', 'message': {'content': 'Hmm, that sounds hard.'}},
        {'type': 'audio_output', 'data': base64.b64encode(b'wav').decode()},
        {'type': 'assistant_end'},
    ]
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
    audio = await speech.synthesize('That sounds hard.', user='My day was difficult.')
    assert audio.spoken_text == 'Hmm, that sounds hard.'
    assert [m['type'] for m in sent] == [
        'pause_assistant_message', 'session_settings', 'user_input', 'resume_assistant_message']
    prompt = sent[1]['system_prompt']
    assert json.loads(prompt[prompt.index('{'):])['TARGET_REPLY'] == 'That sounds hard.'
