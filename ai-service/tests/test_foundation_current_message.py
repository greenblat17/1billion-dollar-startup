from copy import deepcopy
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.context_builder import ContextBuilder
from app.foundation_tutor import FoundationTutor
from app.tutor import TutorRequest


def snapshot(body):
    return {'version': 1, 'student': {'id': 'student', 'tutor_id': 'sasha',
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
