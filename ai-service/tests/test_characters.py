import base64
import json
from dataclasses import replace
from types import SimpleNamespace

import httpx
import pytest
from fakeredis import FakeAsyncRedis

from app.call_start import CallStarter, KORA_H_OPENING_REQUEST
from app.audit_artifacts import bind_writer, reset_writer
from app.calls import CallStore
from app.characters import Characters, CharacterStore, KoraHModel
from app.dialogue import MemoryDialogueStore
from app.gemini_speech import GeminiKoraSpeech
from app.hume_speech import HumeSpeech
from app.main import _build_pipeline
from app.personalization import context_note
from app.pipeline import ClipPipeline
from app.speech import SessionSpeech
from app.tts import TtsAudio
from tests.conftest import FakeLlm, FakeStt, FakeTts, build_app, test_settings as settings_fixture
from tests.test_call_start import Model, Personalization


class KoraSpeech(FakeTts):
    async def synthesize(self, text, **kwargs):
        self.last_speed = kwargs.get("speed")
        self.last_delivery = kwargs.get("delivery")
        self.last_guided = kwargs.get("guided")
        self.last_warm = kwargs.get("warm")
        audio = await super().synthesize(text)
        return TtsAudio(audio.data, audio.content_type, getattr(self, 'spoken', None) or text)


@pytest.mark.asyncio
async def test_character_persists_without_affecting_other_users():
    redis = FakeAsyncRedis(decode_responses=True)
    store = CharacterStore(redis)
    await redis.set('character:tg-1', 'kora')
    assert await CharacterStore(redis).get('tg-1') == 'speaky'
    with pytest.raises(ValueError):
        await store.set('tg-1', 'kora')
    await store.set('tg-1', 'kora_h')
    assert await CharacterStore(redis).get('tg-1') == 'kora_h'
    assert await store.get('tg-2') == 'speaky'
    with pytest.raises(ValueError):
        await store.set('tg-1', 'unknown')
    await redis.aclose()


@pytest.mark.asyncio
async def test_retired_kora_selection_uses_speaky_without_kora_h_history():
    redis = FakeAsyncRedis(decode_responses=True)
    await redis.set('character:tg-1', 'kora')
    dialogue = MemoryDialogueStore(40, 86400)
    ordinary, model_h = FakeLlm(), FakeLlm()
    characters = Characters(CharacterStore(redis), model_h, KoraSpeech(), enabled=True, allowlist='1')
    pipeline = ClipPipeline(FakeStt(['Hello']), ordinary, FakeTts(), dialogue,
                            characters=characters)
    result = await pipeline.run('tg-1', b'x', 'audio/ogg', 'voice.ogg')
    assert result.reply_text == 'Got it: Hello'
    assert ordinary.calls == [([], 'Hello')]
    assert model_h.calls == []
    assert await dialogue.history('tg-1:kora_h') == []
    await redis.aclose()


@pytest.mark.asyncio
async def test_kora_h_allowlist_blocks_stored_selection_and_accepts_telegram_session_formats():
    model_h = FakeLlm()
    characters = Characters(CharacterStore(), model_h, KoraSpeech(),
                            enabled=True, allowlist='123, 456')
    await characters.store.set('tg-789', 'kora_h')
    assert characters.allowed('tg-123')
    assert characters.allowed('tg-ChatId(chatId=456)')
    assert not characters.allowed('tg-789')
    assert not characters.allowed('tg-123-extra')
    assert await characters.variant('tg-789') is None
    assert await characters.variant('tg-ChatId(chatId=456)') is None
    await characters.store.set('tg-ChatId(chatId=456)', 'kora_h')
    assert await characters.variant('tg-ChatId(chatId=456)') == 'kora_h'
    characters.enabled = False
    assert await characters.variant('tg-ChatId(chatId=456)') is None
    with pytest.raises(ValueError, match='KORA_H_ALLOWLIST'):
        Characters(CharacterStore(), enabled=True, allowlist='123,other')


@pytest.mark.asyncio
async def test_kora_h_uses_gemini_speech():
    voice = KoraSpeech()
    characters = Characters(CharacterStore(), FakeLlm(), voice)
    await characters.synthesize('Hi.', speed=0.9, variant='kora_h')
    assert voice.texts == ['Hi.']
    assert voice.last_speed == 0.9
    assert voice.last_guided is None and voice.last_warm is None
    characters.speech_h = None
    assert not characters.h_available


def test_pipeline_builds_gemini_for_kora_h_without_hume():
    settings = replace(
        settings_fixture(), openai_api_key="test", groq_api_key="test",
        openai_base_url="https://openrouter.ai/api/v1",
    )
    pipeline = _build_pipeline(settings)
    assert isinstance(pipeline.characters.speech_h, GeminiKoraSpeech)
    assert pipeline.characters.speech_h.model == 'google/gemini-3.1-flash-tts-preview'
    assert pipeline.characters.h_available
    with_hume = _build_pipeline(replace(settings, hume_api_key="test", hume_config_id="test"))
    assert with_hume.characters.h_available


@pytest.mark.asyncio
async def test_kora_h_has_separate_history_and_gemini_delivery():
    dialogue = MemoryDialogueStore(40, 86400)
    model_h, voice = FakeLlm(), KoraSpeech()
    characters = Characters(CharacterStore(), model_h, voice, enabled=True, allowlist='1')
    await characters.store.set('tg-1', 'kora_h')
    await dialogue.record_turn('tg-1:kora', 'Old Kora topic', 'Old Kora reply')
    pipeline = ClipPipeline(FakeStt(['I like walking home.', 'It gives me time to think.', 'A cafe']),
                            FakeLlm(), FakeTts(), dialogue, calls=CallStore(), characters=characters)
    pipeline.personalization = Personalization()
    await pipeline.speech.speeds.set('tg-1', 0.9)
    first = await pipeline.run('tg-1', b'x', 'audio/ogg', 'voice.ogg')
    assert first.reply_text == 'Got it: I like walking home.'
    assert voice.last_guided is None and voice.last_speed == 0.9
    assert voice.last_warm is None
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
async def test_kora_h_changes_delivery_per_reply_without_changing_spoken_text(monkeypatch):
    model, voice = KoraHModel(None, 'test'), KoraSpeech()
    tones = iter((('excited', "Oh, your beta is live? That's great!"),
                  ('gentle', "Oh, that sounds hard. What happened?")))

    async def complete(messages, **kwargs):
        tone, reply = next(tones)
        return json.dumps(dict(interest='engaged', evidence='Learner gave a concrete update',
                               topic='Beta release', direction='stay', move='question',
                               adjacent_topic='', tone=tone, reply=reply))

    monkeypatch.setattr(model, '_complete', complete)
    characters = Characters(CharacterStore(), model, voice, enabled=True, allowlist='1')
    await characters.store.set('tg-1', 'kora_h')
    dialogue = MemoryDialogueStore(40, 86400)
    pipeline = ClipPipeline(FakeStt(['My beta is live.', 'But it crashed.']), FakeLlm(), FakeTts(),
                            dialogue, calls=CallStore(), characters=characters)
    pipeline.personalization = Personalization()

    first = await pipeline.run('tg-1', b'x', 'audio/ogg', 'voice.ogg')
    assert voice.last_delivery == 'excited'
    second = await pipeline.run('tg-1', b'x', 'audio/ogg', 'voice.ogg')
    assert voice.last_delivery == 'gentle'
    assert voice.texts == [first.reply_text, second.reply_text]
    assert [turn.content for turn in await dialogue.history('tg-1:kora_h')][-1] == second.reply_text


@pytest.mark.asyncio
async def test_gemini_receives_delivery_tag_only_in_audio_request(monkeypatch):
    requests = []

    class Speech:
        async def create(self, **kwargs):
            requests.append(kwargs)
            return SimpleNamespace(aread=read_pcm)

    async def read_pcm():
        return b'\x00\x00'

    async def convert(pcm, speed):
        return b'OggSvoice'

    client = SimpleNamespace(audio=SimpleNamespace(speech=Speech()))
    speech = GeminiKoraSpeech(client)
    monkeypatch.setattr(speech, '_ffmpeg', convert)
    await speech.synthesize('Oh, your beta is live?', delivery='surprised')
    await speech.synthesize('How was your day?')
    assert requests[0]['input'] == '[amazed] Oh, your beta is live?'
    assert requests[1]['input'] == 'How was your day?'
    with pytest.raises(ValueError, match='unsupported speech delivery'):
        await speech.synthesize('Hello.', delivery='[shouting]')


@pytest.mark.asyncio
async def test_kora_h_opening_uses_its_model_and_gemini_voice():
    dialogue = MemoryDialogueStore(40, 86400)
    model_h, voice = FakeLlm(), KoraSpeech()
    characters = Characters(CharacterStore(), model_h, voice, enabled=True, allowlist='1')
    await characters.store.set('tg-1', 'kora_h')
    starter = CallStarter(CallStore(), Model(), Personalization(), dialogue, SessionSpeech(FakeTts()), characters)
    first = await starter.start('tg-1')
    assert first['character'] == 'kora_h'
    assert first['question'] == f"Got it: {KORA_H_OPENING_REQUEST}"
    assert model_h.calls == [([], KORA_H_OPENING_REQUEST)]
    assert model_h.profile_notes == ['Use verified memory only.']
    assert model_h.modes == ['opening']
    assert voice.last_guided is None
    assert voice.last_warm is None
    await starter.delivered(first['callId'])
    assert len(await dialogue.history('tg-1:kora_h')) == 2
    assert await dialogue.history('tg-1:kora') == []


@pytest.mark.asyncio
async def test_kora_h_opening_uses_plain_delivery_regardless_of_sonnet_tone(monkeypatch):
    model_h, voice = KoraHModel(None, 'test'), KoraSpeech()

    async def complete(messages, **kwargs):
        return json.dumps(dict(interest='searching', evidence='New call', topic='Today',
                               direction='stay', move='question', adjacent_topic='',
                               tone='curious', reply='Hey! How are you today?'))

    monkeypatch.setattr(model_h, '_complete', complete)
    characters = Characters(CharacterStore(), model_h, voice, enabled=True, allowlist='1')
    await characters.store.set('tg-1', 'kora_h')
    starter = CallStarter(CallStore(), Model(), Personalization(), MemoryDialogueStore(40, 86400),
                          SessionSpeech(FakeTts()), characters)
    result = await starter.start('tg-1')
    assert result['question'] == 'Hey! How are you today?'
    assert voice.last_delivery == 'plain'
    assert voice.texts == ['Hey! How are you today?']


@pytest.mark.asyncio
async def test_kora_h_cached_opening_also_uses_plain_delivery():
    calls = CallStore()
    summary = await calls.open('tg-1')
    await calls.save_opening(summary['callId'], 'Hey! How are you today?')
    model_h, voice = FakeLlm(), KoraSpeech()
    characters = Characters(CharacterStore(), model_h, voice, enabled=True, allowlist='1')
    await characters.store.set('tg-1', 'kora_h')
    starter = CallStarter(calls, Model(), Personalization(), MemoryDialogueStore(40, 86400),
                          SessionSpeech(FakeTts()), characters)

    result = await starter.start('tg-1')

    assert result['question'] == 'Hey! How are you today?'
    assert voice.last_delivery == 'plain'
    assert model_h.calls == []


@pytest.mark.asyncio
async def test_kora_h_opening_uses_only_recent_learner_context():
    dialogue = MemoryDialogueStore(40, 86400)
    model_h, voice = FakeLlm(), KoraSpeech()
    characters = Characters(CharacterStore(), model_h, voice, enabled=True, allowlist='1')
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
async def test_kora_h_opening_uses_one_call_with_history_and_profile(monkeypatch):
    from app.dialogue import ChatMessage
    model = KoraHModel(None, 'test')
    calls = []
    turn = dict(interest='searching', evidence='Learner mentioned their project',
                topic='Project', direction='stay', move='question', tone='Warm',
                adjacent_topic='', reply='Hey! How are you? How did your project go?')
    async def complete(messages, **kwargs):
        calls.append((messages, kwargs))
        return json.dumps(turn)
    monkeypatch.setattr(model, '_complete', complete)
    history = [ChatMessage('user', 'I am launching a project.')]
    reply = await model.complete_reply(history, KORA_H_OPENING_REQUEST,
                                       'Use verified memory only.', mode='opening')
    assert reply == turn['reply']
    assert len(calls) == 1
    messages, kwargs = calls[0]
    assert 'actually raised' in messages[0]['content']
    assert messages[1] == {'role': 'system', 'content': 'Use verified memory only.'}
    assert messages[-2:] == [{'role': 'user', 'content': history[0].content},
                             {'role': 'user', 'content': KORA_H_OPENING_REQUEST}]
    assert kwargs['operation'] == 'kora_h_turn'
    assert kwargs['response_format']['type'] == 'json_schema'
    assert kwargs['response_format']['json_schema']['strict'] is True


@pytest.mark.asyncio
async def test_kora_h_opening_retries_invalid_move_once(monkeypatch):
    model = KoraHModel(None, 'test')
    calls = []
    turn = dict(interest='searching', evidence='No prior learner topic', topic='Today',
                direction='stay', move='question', tone='Warm', adjacent_topic='',
                reply='Hi! How are you? What have you enjoyed today?')
    async def complete(messages, **kwargs):
        calls.append((messages, kwargs))
        return json.dumps({**turn, 'move': 'perspective', 'reply': 'Today is a fresh start.'}
                          if len(calls) == 1 else turn)
    monkeypatch.setattr(model, '_complete', complete)
    reply = await model.complete_reply([], KORA_H_OPENING_REQUEST, mode='opening')
    assert reply == turn['reply']
    assert len(calls) == 2
    assert all(kwargs['operation'] == 'kora_h_turn' for _, kwargs in calls)
    assert any('invalid_opening_move' in message['content'] for message in calls[1][0])
    assert calls[1][1]['temperature'] == .2


@pytest.mark.asyncio
async def test_character_api_auth_unavailable_busy_and_active_call():
    app, *_ = build_app()
    chars = app.state.pipeline.characters
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as client:
        assert (await client.get('/internal/character/tg-1')).status_code == 401
        client.headers['X-Internal-Token'] = 'test-internal-token'
        path = '/internal/character'
        payload = {'sessionId': 'tg-1', 'character': 'kora_h'}
        assert (await client.post(path, json=payload)).json()['blocked'] == 'unavailable'
        assert (await client.post(path, json={**payload, 'character': 'kora'})).status_code == 400
        chars.model_h, chars.speech_h = FakeLlm(), KoraSpeech()
        chars.enabled = True
        chars.allowlist = frozenset({1})
        job = app.state.jobs.create('tg-1')
        assert (await client.post(path, json=payload)).json()['blocked'] == 'active_conversation'
        job.status = 'ok'
        opened = await app.state.pipeline.calls.open('tg-1')
        await app.state.pipeline.calls.save_opening(opened['callId'], 'Hello')
        await app.state.pipeline.calls.mark_opening_delivered(opened['callId'])
        assert (await client.post(path, json=payload)).json()['blocked'] == 'active_conversation'
        await app.state.pipeline.calls.seal('tg-1')
        assert (await client.post(path, json=payload)).json()['character'] == 'kora_h'
        assert (await client.get(path + '/tg-1')).json()['koraHAvailable'] is True
        assert (await client.get(path + '/tg-1')).json()['koraAvailable'] is False
        assert (await client.get(path + '/tg-2')).json()['character'] == 'speaky'
        denied = await client.post(path, json={**payload, 'sessionId': 'tg-2'})
        assert denied.json()['blocked'] == 'unavailable'
        assert denied.json()['koraHAvailable'] is False
        await chars.store.set('tg-2', 'kora_h')
        assert (await client.get(path + '/tg-2')).json()['character'] == 'speaky'
        assert await chars.variant('tg-2') is None
        await app.state.pipeline.calls.open('tg-1')  # Failed/undelivered next opening.
        switched = await client.post(path, json={**payload, 'character': 'speaky'})
        assert switched.json()['character'] == 'speaky'
        assert await app.state.pipeline.calls.open_call_today('tg-1') is None
        assert (await client.post(path, json={**payload, 'character': 'invalid'})).status_code == 400


@pytest.mark.asyncio
async def test_kora_h_one_call_preserves_profile_history_and_plan(monkeypatch):
    from app.dialogue import ChatMessage
    model = KoraHModel(None, 'test')
    calls, artifacts = [], {}
    turn = dict(interest='engaged', evidence='Learner sees walking as lost time',
                topic='Walking home', direction='stay', move='perspective', tone='Thoughtful',
                adjacent_topic='',
                reply='Maybe. But that walk might be your one quiet moment between work and home.')
    async def complete(messages, **kwargs):
        calls.append((messages, kwargs))
        return json.dumps(turn)
    async def write(field, value): artifacts[field] = value
    monkeypatch.setattr(model, '_complete', complete)
    profile_note = context_note({'person': {'interests': ['walking']},
                                 'proficiency': {'overall_cefr': 'B2'}}, 0, 0)
    token = bind_writer(write)
    try:
        reply = await model.complete_reply([ChatMessage('assistant', 'How was work?')],
                                           'Walking home wastes my time.', profile_note)
    finally:
        reset_writer(token)
    assert reply == turn['reply']
    assert len(calls) == 1
    messages, kwargs = calls[0]
    assert kwargs['operation'] == 'kora_h_turn'
    assert 'different, plausible angle' in messages[0]['content']
    assert 'A2 English' not in messages[0]['content']
    assert messages[1] == {'role': 'system', 'content': profile_note}
    assert messages[-2:] == [{'role': 'assistant', 'content': 'How was work?'},
                             {'role': 'user', 'content': 'Walking home wastes my time.'}]
    plan = json.loads(artifacts['conversation_plan'])
    assert plan['strategy'] == 'interest_spoken_reply_v4'
    assert plan['move'] == 'perspective'
    assert 'reply' not in plan
    assert 'contribution' not in plan


@pytest.mark.asyncio
async def test_kora_h_invalid_json_retries_without_direct_writer(monkeypatch):
    model = KoraHModel(None, 'test')
    calls = []
    turn = dict(interest='engaged', evidence='Learner describes a release',
                topic='Voice release', direction='stay', move='question', tone='Excited',
                adjacent_topic='', reply="Oh, the beta's live today? What feedback do you hope to hear?")
    async def complete(messages, **kwargs):
        calls.append(kwargs['operation'])
        return 'not-json' if len(calls) == 1 else json.dumps(turn)
    monkeypatch.setattr(model, '_complete', complete)
    reply = await model.complete_reply([], 'We release the beta today.')
    assert reply == turn['reply']
    assert calls == ['kora_h_turn', 'kora_h_turn']


@pytest.mark.asyncio
async def test_kora_h_two_invalid_turns_fail_with_diagnostic(monkeypatch):
    model = KoraHModel(None, 'test')
    calls, artifacts = [], {}
    async def complete(messages, **kwargs):
        calls.append(kwargs['operation'])
        return '{}'
    async def write(field, value): artifacts[field] = value
    monkeypatch.setattr(model, '_complete', complete)
    token = bind_writer(write)
    try:
        with pytest.raises(ValueError, match='invalid_kora_h_turn'):
            await model.complete_reply([], 'Hi')
    finally:
        reset_writer(token)
    assert calls == ['kora_h_turn', 'kora_h_turn']
    assert json.loads(artifacts['conversation_plan'])['turn_error'] == 'invalid_reply'


def test_kora_h_unknown_tone_falls_back_to_plain():
    from app.kora_h_prompts import parse_h_turn
    turn = dict(interest='engaged', evidence='Learner gave a detail', topic='Work',
                direction='stay', move='question', tone='softly curious',
                adjacent_topic='', reply='What happened next?')
    plan = parse_h_turn(json.dumps(turn))['plan']
    assert plan['tone'] == 'plain'
    assert plan['tone_fallback'] == 'softly curious'


def test_kora_h_turn_rejects_old_move_and_requires_fading_bridge():
    from app.kora_h_prompts import parse_h_turn
    turn = dict(interest='engaged', evidence='Learner has a view', topic='Feedback',
                direction='stay', move='perspective', tone='Warm', adjacent_topic='',
                reply='Returning users may show more than first compliments.')
    with pytest.raises(ValueError, match='invalid_move'):
        parse_h_turn(json.dumps({**turn, 'move': 'contribute'}))
    assert parse_h_turn('```json\n' + json.dumps(turn) + '\n```')['plan']['move'] == 'perspective'
    with pytest.raises(ValueError, match='invalid_adjacent_topic'):
        parse_h_turn(json.dumps({**turn, 'interest': 'fading'}))
    with pytest.raises(ValueError, match='invalid_question_reply'):
        parse_h_turn(json.dumps({**turn, 'move': 'question'}))


@pytest.mark.asyncio
async def test_kora_h_fading_turn_bridges_to_adjacent_topic(monkeypatch):
    model = KoraHModel(None, 'test')
    turn = dict(interest='fading', evidence='Several attempts about work got short replies',
                topic='Work', direction='adjacent', adjacent_topic='After-work rest',
                move='question', tone='Gentle',
                reply='Work sounds tiring. What helps you switch off afterward?')
    calls = []
    async def complete(messages, **kwargs):
        calls.append(kwargs['operation'])
        return json.dumps(turn)
    monkeypatch.setattr(model, '_complete', complete)
    reply = await model.complete_reply([], 'Yeah. Same as usual.')
    assert reply == turn['reply']
    assert calls == ['kora_h_turn']


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
