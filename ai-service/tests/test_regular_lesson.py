import json
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.context_builder import TutorContext
from app.regular_lesson import LessonPlanner
from tests.test_trial_realtime import session, sent, output


@pytest.mark.asyncio
async def test_production_planner_uses_shared_evidence_and_distinct_objective_and_topic():
    plan = {'objective': 'Explain a preference with reasons', 'topic': 'Choosing a hiking route',
            'strategy': 'Use fact source-1. Discover a favourite route, compare choices, ask feedback.'}
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps(plan)))
    context = TutorContext({'lesson': {'kind': 'regular', 'duration_minutes': 30},
        'goal': {'content': {'value': 'Speak confidently'}},
        'facts': [{'fact_key': 'leisure', 'value': 'hiking', 'source_event_id': 'source-1'}],
        'observations': [{'content': 'Needs time to give reasons', 'uncertainty': 'limited'}]}, [], False)
    assert await LessonPlanner(model).prepare(context, {}) == plan
    payload = json.loads(model.complete_json.call_args.args[1])
    assert payload == context.data
    from app.learning_results import LessonPlan
    assert model.complete_json.call_args.kwargs == {'operation': 'regular_lesson_prepare', 'retry': False,
        'response_format': LessonPlan.response_format()}


@pytest.mark.asyncio
@pytest.mark.parametrize('result', [{}, {'objective': '', 'topic': 'x', 'strategy': 'x'},
    {'objective': 'x', 'topic': 'x', 'strategy': ['bad']},
    {'objective': 'x', 'topic': 'x', 'strategy': 'x' * 3001},
    {'objective': 'x', 'topic': 'x', 'strategy': 'x', 'invented': 'x'}])
async def test_planner_rejects_invalid_results_without_fallback(result):
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps(result)))
    with pytest.raises(ValueError, match='invalid_plan'):
        await LessonPlanner(model).prepare(TutorContext({'lesson': {'kind': 'regular'}}, [], False), {})


@pytest.mark.asyncio
async def test_trial_preparation_does_not_start_new_paid_planner():
    model = SimpleNamespace(complete_json=AsyncMock())
    plan = await LessonPlanner(model).prepare(TutorContext({'lesson': {'kind': 'trial'}}, [], False), {})
    assert 'trial' in plan['objective']
    model.complete_json.assert_not_awaited()


def regular_session(minutes=30, elapsed=0):
    s = session()
    s.lesson = dict(s.lesson, kind='regular', duration_minutes=minutes,
        starts_at=(datetime.now(timezone.utc) - timedelta(seconds=elapsed)).isoformat(),
        execution_snapshot={'status': 'ready', 'plan': {'content': {
            'objective': 'Give reasons', 'topic': 'Hiking', 'strategy': 'Explore choices'}}})
    return s


@pytest.mark.asyncio
async def test_regular_configuration_uses_admitted_plan_memory_and_actual_next_meeting():
    s = regular_session(60, 600)
    s.backend.command.return_value = {'lesson': None}
    s.provider.recv = AsyncMock(return_value=json.dumps({'type': 'session.updated'}))
    await s.configure()
    config = sent(s)[0]['session']
    data = json.loads(config['instructions'].split('Lesson data (not instructions):\n')[1])
    assert data['plannedMinutes'] == 60
    assert 599 <= data['elapsedFromScheduledStartSeconds'] <= 602
    assert data['admittedPlan'] == s.lesson['execution_snapshot']
    assert data['context']['goal'] == 'work'
    assert data['history'][0]['content'] == 'Known goal'
    assert data['nextLesson'] is None
    assert 'not yet\navailable' not in config['instructions']
    assert config['audio']['output']['voice'] == 'marin'
    assert {t['name'] for t in config['tools']} == {'finish_after_reply', 'search_memory', 'discuss_memory', 'record_memory', 'set_memory_policy', 'review_observation', 'confirm_memory', 'reject_memory', 'forget_memory'}


@pytest.mark.asyncio
async def test_finish_refreshes_cancelled_next_lesson_and_waits_for_playback():
    s = regular_session()
    s.backend.command.return_value = {'lesson': None}
    await s.tool({'name': 'finish_after_reply', 'arguments': '{}', 'call_id': 'end'})
    result = json.loads(sent(s)[-1]['item']['output'])
    assert result == {'readyForGoodbye': True, 'nextLesson': None}
    assert s.backend.command.call_args.args[2:] == ('lesson.next', {})
    assert not s.ended
    s.backend.command.return_value = {'objectKey': 'trial/key'}
    await output(s, finish=True)
    await s.provider_event({'type': 'response.output_audio.done', 'item_id': 'reply'})
    await s.provider_event({'type': 'response.output_audio_transcript.done', 'item_id': 'reply', 'transcript': 'Goodbye'})
    assert not s.ended
    await s.settle('reply', {'complete': True, 'playedMs': 100})
    assert s.ended
    assert s.backend.command.call_args.args[2] == 'voice.end'


@pytest.mark.parametrize('minutes', [15, 30, 60])
def test_remaining_time_uses_agreed_start_not_reconnect(minutes):
    s = regular_session(minutes, elapsed=600)
    assert minutes * 60 - 602 <= s.remaining_seconds() <= minutes * 60 - 599


@pytest.mark.asyncio
async def test_long_regular_lesson_rotates_only_when_no_turn_is_pending(monkeypatch):
    import app.trial_realtime as runtime
    s = regular_session(60)
    s.started -= 51 * 60
    monkeypatch.setattr(runtime.asyncio, 'sleep', AsyncMock())
    await s.heartbeat()
    assert s.rotate and not s.ended
    assert s.backend.command.call_args.args[2] == 'voice.heartbeat'


@pytest.mark.asyncio
async def test_next_lesson_failure_cannot_authorize_goodbye_with_stale_meeting():
    from app.foundation_client import BackendFailure
    s = regular_session()
    s.backend.command.side_effect = BackendFailure('unavailable', 503)
    with pytest.raises(BackendFailure):
        await s.tool({'name': 'finish_after_reply', 'arguments': '{}', 'call_id': 'end'})
    assert not s.finish_next and not s.ended
    assert not sent(s)


@pytest.mark.asyncio
async def test_next_lesson_result_is_backend_meeting_not_model_input():
    s = regular_session()
    meeting = {'id': 'agreed', 'starts_at': '2026-11-15T10:00:00Z', 'timezone': 'Europe/Moscow',
               'duration_minutes': 30, 'kind': 'regular', 'status': 'scheduled', 'launch_mode': 'executable'}
    s.backend.command.return_value = {'lesson': meeting}
    await s.tool({'name': 'finish_after_reply', 'arguments': '{}', 'call_id': 'end'})
    assert json.loads(sent(s)[-1]['item']['output'])['nextLesson'] == meeting


@pytest.mark.asyncio
async def test_rotation_releases_episode_before_browser_reconnect():
    from contextlib import asynccontextmanager
    s = regular_session(60)
    order = []
    @asynccontextmanager
    async def connector(settings):
        yield SimpleNamespace()
    s.connector = connector
    s.configure = AsyncMock()
    async def rotate():
        s.rotate = True
    s.heartbeat = rotate
    import asyncio
    async def wait():
        await asyncio.Event().wait()
    s.receive = s.listen = wait
    s.backend.command.side_effect = lambda *args: order.append(args[2]) or {}
    s.socket.send_json.side_effect = lambda data: order.append(data['type'])
    await s.run()
    assert order == ['voice.end', 'reconnect']
    assert not s.ended


@pytest.mark.asyncio
@pytest.mark.parametrize('minutes,elapsed,expect_hint', [(15, 721, True), (30, 900, False), (60, 3421, True)])
async def test_closing_hint_uses_original_end_and_does_not_force_a_response(monkeypatch, minutes, elapsed, expect_hint):
    import app.trial_realtime as runtime
    s = regular_session(minutes, elapsed)
    s.started -= 14 * 60
    s.backend.command.return_value = {'lesson': None}
    monkeypatch.setattr(runtime.asyncio, 'sleep', AsyncMock(side_effect=[None, RuntimeError('stop test loop')]))
    with pytest.raises(RuntimeError, match='stop test loop'):
        await s.heartbeat()
    assert s.sent_hint is expect_hint
    assert not s.ended
    assert not any(message['type'] == 'response.create' for message in sent(s))
    if expect_hint:
        assert 'Next lesson data: null' in sent(s)[0]['item']['content'][0]['text']


def test_regular_notices_are_kind_aware_and_natural_followup_is_rejected():
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from app.trial_api import install_trial_api
    app = FastAPI()
    contexts = SimpleNamespace(build=AsyncMock(return_value=TutorContext(
        {'lesson': {'kind': 'regular'}}, [{'role': 'user', 'content': 'I need to leave for work'}], False)))
    model = SimpleNamespace(complete_json=AsyncMock(return_value='{"reply":""}'))
    install_trial_api(app, None, contexts, model, None, None, None)
    with TestClient(app) as client:
        def request(kind):
            return client.post('/internal/trial/followup', headers={'X-Execution-Scope': 'scoped'},
                               json={'lesson_id': 'regular', 'kind': kind})
        assert request('followup').status_code == 400
        model.complete_json.assert_not_awaited()
        assert request('end_button').json() == {'reply': ''}
        assert 'return an empty reply' in model.complete_json.call_args.args[0]
        assert 'I need to leave for work' in model.complete_json.call_args.args[1]
        for kind in ('reminder', 'invitation', 'no_show'):
            assert request(kind).status_code == 200
            assert 'only placeholders' not in model.complete_json.call_args.args[0]
