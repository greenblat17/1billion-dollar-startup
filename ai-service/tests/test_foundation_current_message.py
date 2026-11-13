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
    assert 'Do not sustain an unrelated topic.' in system
    assert 'Previous learning experience is OPTIONAL' in system
    assert 'not a proficiency test' in system
    assert 'no answer-choice buttons' in system
    assert 'without finishing the consultation' in system
    assert messages[-1] == {'role': 'user', 'content': body}


@pytest.mark.asyncio
async def test_known_level_survives_selector_failure_and_voice_keeps_its_personality():
    data = snapshot('Привет снова')
    data['facts'] = [dict(category='learning', fact_key='level', value='Наверное B1',
                          kind='self_assessment', revision=1)]
    backend = SimpleNamespace(snapshot=AsyncMock(return_value=data))
    selector = SimpleNamespace(select=AsyncMock(side_effect=RuntimeError('selection unavailable')))
    contexts = ContextBuilder(backend, selector=selector)
    text = await contexts.build('scope', 'text')
    voice = await contexts.build('scope', 'voice')
    assert 'Наверное B1' in text.instructions('text')
    assert 'professional tutor' in text.instructions('text')
    assert 'Current mode: introduction.' in text.instructions('text')
    assert 'help find a topic with an easy question' in voice.instructions('voice')


@pytest.mark.asyncio
async def test_explicit_dialogue_mode_keeps_kora_without_consultation_strategy():
    from app.kora_personality import PERSONALITY
    data = snapshot('Хочу поговорить о фильме')
    data['textMode'] = 'dialogue'
    backend = SimpleNamespace(snapshot=AsyncMock(return_value=data))
    context = await ContextBuilder(backend).build('scope', 'text')
    prompt = context.instructions('text')
    assert PERSONALITY in prompt
    assert 'Current mode: ordinary dialogue.' in prompt
    assert 'Curiosity need not serve trial preparation.' in prompt
    assert 'Previous learning experience is OPTIONAL' not in prompt
    assert 'Do not sustain an unrelated topic.' not in prompt


@pytest.mark.asyncio
async def test_unrelated_message_does_not_override_backend_introduction_mode():
    data = snapshot('Давай просто поболтаем о фильмах')
    context = await ContextBuilder(SimpleNamespace(snapshot=AsyncMock(return_value=data))).build('scope', 'text')
    assert context.data['textMode'] == 'introduction'
    assert 'Current mode: introduction.' in context.instructions('text')
    assert 'politely set the boundary' in context.instructions('text')


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
