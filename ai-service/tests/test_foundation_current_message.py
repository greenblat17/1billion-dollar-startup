import json
from copy import deepcopy
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.context_builder import ContextBuilder
from app.foundation_tutor import FoundationTutor
from app.tutor import TutorRequest


def snapshot(body):
    return {'version': 1, 'textMode': 'introduction', 'student': {'id': 'student', 'tutor_id': 'sasha',
            'tutor_version': 1, 'version': 1, 'data_epoch': 1}, 'events': [
        {'id': 'old', 'sequence': 1, 'role': 'user', 'body': body},
        {'id': 'reply', 'sequence': 2, 'role': 'assistant', 'body': 'Обсудим встречу?'},
        {'id': 'current', 'sequence': 3, 'role': 'user', 'body': body}]}


@pytest.mark.asyncio
@pytest.mark.parametrize('body,start,budget', [('првиет', False, 24000),
                                               ('/start', True, 24000),
                                               ('давай', False, 1)])
async def test_authoritative_current_event_is_last_even_when_history_is_truncated(body, start, budget):
    source = {'id': 'current', 'body': body, 'occurred_at': '2026-11-12T10:27:00+00:00'}
    backend = SimpleNamespace(snapshot=AsyncMock(return_value=snapshot(body)), command=AsyncMock(
        side_effect=[{'status': 'claimed', 'input': source},
                     {'conversationId': 'conversation', 'reply': 'Ответ', 'degraded': False}]))
    captured = []

    async def complete(messages):
        captured.append(deepcopy(messages))
        return {'content': '{"reply": "Ответ"}'}

    tutor = FoundationTutor(backend, SimpleNamespace(complete=complete), ContextBuilder(backend, history_budget=budget))
    await tutor.respond(TutorRequest(sessionId='session', requestId='request', text=body,
                                    receivedAt=1794479220.0), 'scope', start=start)
    messages = captured[0]
    assert messages[-1] == {'role': 'user', 'content': body}
    assert messages[-2]['role'] == 'system'
    assert 'current incoming event' in messages[-2]['content']
    assert source['occurred_at'] in messages[-2]['content']
    assert not any(m['content'] == 'Начать или продолжить наше общение.' for m in messages)
    # Identical old text is retained by event identity, not removed by text equality.
    assert sum(m == {'role': 'user', 'content': body} for m in messages) == (1 if budget == 1 else 2)


@pytest.mark.asyncio
async def test_excluding_current_event_does_not_change_voice_or_learning_history():
    backend = SimpleNamespace(snapshot=AsyncMock(return_value=snapshot('давай')))
    contexts = ContextBuilder(backend)
    text = await contexts.build('scope', 'text', exclude_event_id='current')
    voice = await contexts.build('scope', 'voice')
    learning = await contexts.build('scope', 'learning')
    assert len(text.history) == 2
    assert len(voice.history) == len(learning.history) == 3
    assert learning.data['sourceEvents'][-1]['id'] == 'current'


@pytest.mark.asyncio
async def test_interrupted_introduction_is_context_not_a_script_to_finish_and_replay_skips_model():
    body = 'Английский нужен для работы, запиши на пробный сейчас'
    data = snapshot(body)
    delivered = ['Привет! 👋', 'Меня зовут Саша. Я — твой личный ИИ-репетитор английского.']
    data['events'] = [
        {'id': 'start', 'sequence': 1, 'role': 'user', 'body': '/start'},
        *[{'id': f'intro-{i}', 'sequence': i + 2, 'role': 'assistant', 'body': text}
          for i, text in enumerate(delivered)],
        {'id': 'current', 'sequence': 4, 'role': 'user', 'body': body},
    ]
    response = {'conversationId': 'conversation', 'reply': 'Ответ', 'degraded': False}
    backend = SimpleNamespace(snapshot=AsyncMock(return_value=data), command=AsyncMock(side_effect=[
        {'status': 'running', 'input': {'id': 'current', 'body': body}}, response,
        {'status': 'ready', 'response': response},
    ]))
    model = SimpleNamespace(complete=AsyncMock(return_value={'content': '{"reply": "Ответ"}'}))
    tutor = FoundationTutor(backend, model, ContextBuilder(backend))
    request = TutorRequest(sessionId='session', requestId='request', text=body, receivedAt=1794479220.0)
    first = await tutor.respond(request, 'scope')
    replay = await tutor.respond(request, 'scope')
    assert replay == first
    assert model.complete.await_count == 1
    messages = model.complete.call_args.args[0]
    assert [m['content'] for m in messages if m['role'] == 'assistant'] == delivered
    assert messages[-1] == {'role': 'user', 'content': body}
    assert 'without continuing or repeating the introduction' in messages[-2]['content']
    assert 'An explicit current request needs no additional confirmation.' in messages[-2]['content']


@pytest.mark.asyncio
@pytest.mark.parametrize('body', [
    'Хочу переехать в США',
    'Наверное B1. Долго формулирую предложения, английский нужен для работы',
    'Для жизни за границей. Уровень не знаю, трудно понимать на слух',
    'Запиши меня сейчас, остальное обсудим на уроке',
    'Пока не хочу пробный. Чем отличается say от tell?',
])
async def test_live_text_uses_consultation_policy_without_legacy_five_topic_requirement(body):
    from app.kora_personality import PERSONALITY
    from app.text_tutor_policy import INTRODUCTION_WRITING_STYLE, TEXT_RELATIONSHIP_POLICY
    from app.trial_policy import TEXT_CAPABILITIES

    source = {'id': 'current', 'body': body}
    backend = SimpleNamespace(snapshot=AsyncMock(return_value=snapshot(body)), command=AsyncMock(side_effect=[
        {'status': 'running', 'input': source},
        {'conversationId': 'conversation', 'reply': 'Ответ', 'degraded': False},
    ]))
    model = SimpleNamespace(complete=AsyncMock(return_value={'content': '{"reply": "Ответ"}'}))
    tutor = FoundationTutor(backend, model, ContextBuilder(backend))
    await tutor.respond(TutorRequest(sessionId='session', requestId='request', text=body,
                                    receivedAt=1794479220.0), 'scope')
    messages = model.complete.call_args.args[0]
    system = '\n'.join(m['content'] for m in messages if m['role'] == 'system')
    assert 'First explore all five topics' not in system
    assert 'Current mode: introduction.' in system
    assert PERSONALITY in messages[0]['content']
    assert INTRODUCTION_WRITING_STYLE in messages[0]['content']
    assert TEXT_RELATIONSHIP_POLICY not in system
    assert TEXT_CAPABILITIES not in system
    assert 'максимум одно полезное уточнение по этой теме' in system
    assert 'бесплатный созвон примерно на 30 минут' in system
    assert 'Из описания по-русски уровень не выводи' in system
    assert 'Если в переписке уже есть предложение записаться' in system
    assert 'get_subscription' in system
    assert messages[-1] == {'role': 'user', 'content': body}


@pytest.mark.asyncio
async def test_introduction_supplies_previous_clarification_and_preserves_paragraphs_on_replay():
    body = 'Даже не знаю. А пробный платный?'
    data = snapshot(body)
    previous = [
        ('assistant', 'Знаешь свой уровень английского?'),
        ('user', 'Не знаю.'),
        ('assistant', 'Получается поддержать короткий разговор на английском?'),
    ]
    data['events'] = [
        {'id': f'past-{i}', 'sequence': i, 'role': role, 'body': text}
        for i, (role, text) in enumerate(previous, start=1)
    ] + [{'id': 'current', 'sequence': 4, 'role': 'user', 'body': body}]
    data['facts'] = [
        dict(category='personal', fact_key='difficulty', value='Трудно понимать на слух', kind='user_report', revision=1),
    ]
    reply = 'Пробный бесплатный.\n\nА для чего тебе нужен английский?'
    saved = {}

    async def command(scope, key, action, payload):
        if action == 'run.claim':
            return {'status': 'ready', 'response': saved} if saved else {
                'status': 'running', 'input': {'id': 'current', 'body': body},
            }
        assert action == 'run.complete'
        assert payload == {'reply': reply, 'bookingId': None, 'degraded': False}
        saved.update(conversationId='conversation', reply=payload['reply'])
        return saved

    backend = SimpleNamespace(snapshot=AsyncMock(return_value=data), command=AsyncMock(side_effect=command))
    model = SimpleNamespace(complete=AsyncMock(return_value={'content': json.dumps({'reply': reply})}))
    tutor = FoundationTutor(backend, model, ContextBuilder(backend))
    request = TutorRequest(sessionId='session', requestId='request', text=body, receivedAt=1794479220.0)

    first = await tutor.respond(request, 'scope')
    assert first['reply'] == reply
    assert await tutor.respond(request, 'scope') == first
    model.complete.assert_awaited_once()
    messages = model.complete.call_args.args[0]
    assert [(m['role'], m['content']) for m in messages if m['role'] != 'system'] == previous + [('user', body)]
    assert any('Трудно понимать на слух' in m['content'] for m in messages if m['role'] == 'system')
    assert [call.args[2] for call in backend.command.call_args_list] == ['run.claim', 'run.complete', 'run.claim']


@pytest.mark.asyncio
async def test_known_level_survives_selector_failure_and_voice_keeps_its_personality():
    data = snapshot('Привет снова')
    data['facts'] = [dict(category='personal', fact_key='interest', value='books', kind='user_report', revision=1)]
    data['learningState'] = {'schemaVersion': 1, 'revision': 2, 'profile': {
        'currentLevels': {'selfAssessment': {'overall': {'level': 'B1', 'explanation': 'Наверное B1'}}}}}
    backend = SimpleNamespace(snapshot=AsyncMock(return_value=data))
    selector = SimpleNamespace(select=AsyncMock(side_effect=RuntimeError('selection unavailable')))
    contexts = ContextBuilder(backend, selector=selector)
    text = await contexts.build('scope', 'text')
    voice = await contexts.build('scope', 'voice')
    assert 'Наверное B1' in text.instructions('text')
    assert text.data['learningState'] == data['learningState']
    assert text.data['memorySelection']['status'] == 'unavailable_core_only'
    assert 'Current mode: introduction.' in text.instructions('text')
    assert 'help find a topic with an easy question' in voice.instructions('voice')


@pytest.mark.asyncio
async def test_explicit_dialogue_mode_keeps_kora_without_consultation_strategy():
    from app.kora_personality import PERSONALITY
    from app.text_tutor_policy import TEXT_RELATIONSHIP_POLICY
    from app.trial_policy import TEXT_CAPABILITIES
    data = snapshot('Хочу поговорить о фильме')
    data['textMode'] = 'dialogue'
    source = data['events'][-1]
    backend = SimpleNamespace(snapshot=AsyncMock(return_value=data), command=AsyncMock(side_effect=[
        {'status': 'running', 'input': source}, {'conversationId': 'conversation', 'reply': 'Ответ'},
    ]))
    model = SimpleNamespace(complete=AsyncMock(return_value={'content': '{"reply": "Ответ"}'}))
    tutor = FoundationTutor(backend, model, ContextBuilder(backend))
    await tutor.respond(TutorRequest(sessionId='session', requestId='request', text=source['body'],
                                    receivedAt=1794479220.0), 'scope')
    prompt = '\n'.join(m['content'] for m in model.complete.call_args.args[0] if m['role'] == 'system')
    assert PERSONALITY in prompt
    assert 'Current mode: ordinary dialogue.' in prompt
    assert 'Curiosity need not serve trial preparation.' in prompt
    assert TEXT_RELATIONSHIP_POLICY in prompt
    assert TEXT_CAPABILITIES in prompt
    assert 'Как Саша пишет в этом диалоге' not in prompt


@pytest.mark.asyncio
async def test_unrelated_message_does_not_override_backend_introduction_mode():
    data = snapshot('Давай просто поболтаем о фильмах')
    context = await ContextBuilder(SimpleNamespace(snapshot=AsyncMock(return_value=data))).build('scope', 'text')
    assert context.data['textMode'] == 'introduction'
    assert 'Current mode: introduction.' in context.instructions('text')
    assert 'На постороннюю тему ответь кратко и по делу' in context.instructions('text')


@pytest.mark.asyncio
async def test_active_memory_tool_records_refusal_without_overriding_conversation_mode():
    from app.trial_tools import TrialTutorModel
    from app.tutor_tools import TOOLS
    legacy = deepcopy(TOOLS)
    llm = SimpleNamespace(complete_tools=AsyncMock(return_value={'content': 'Ответ'}))
    await TrialTutorModel(llm).complete([])
    tools = llm.complete_tools.call_args.args[1]
    memory = next(t['function'] for t in tools if t['function']['name'] == 'update_learner_context')
    assert 'does not change the conversation mode' in memory['description']
    assert 'Declined means stop unsolicited trial promotion' not in memory['description']
    assert TOOLS == legacy
