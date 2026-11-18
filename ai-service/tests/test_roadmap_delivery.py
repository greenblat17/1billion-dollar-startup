from copy import deepcopy

import pytest

from app.roadmap_delivery import render_initial_roadmap


def fixture():
    state = {'goal': {'content': {'value': 'Говорить увереннее на работе'}},
             'profile': {'targetLevel': 'B2', 'currentLevels': {
                 'speaky': {'overall': {'level': 'Unknown'}},
                 'selfAssessment': {'overall': {'level': 'B1'}}}}}
    sequence = [
        {'taskId': 'one', 'topicId': 't1', 'skillId': 'speaking', 'cefr': 'B1',
         'knowledge': 'Unknown', 'provisional': True,
         'reason': 'Проверим разговор в рабочих ситуациях.'},
        {'taskId': 'two', 'topicId': 't2', 'skillId': 'listening', 'cefr': 'B2',
         'knowledge': 'Presumed Not Known', 'provisional': True,
         'reason': 'Проверим понимание коллег.'},
    ]
    saved = {'revision': 1, 'document': {'goal': 'Говорить увереннее на работе',
             'targetLevel': 'B2', 'sequence': sequence}}
    priority = {'roadmapRevision': 1, 'stale': False, 'priority': {'document': {
        'trigger': 'initial', 'roadmapRevision': 1, 'nextTaskId': 'one',
        'orderedTasks': [{'taskId': 'one', 'reason': 'Сейчас важнее разговор.'},
                         {'taskId': 'two', 'reason': 'Затем аудирование.'}]}}}
    catalog = {'topics': [{'id': 't1', 'title': 'Рабочий разговор'},
                          {'id': 't2', 'title': 'Обсуждение с коллегами'}]}
    timeline = {'status': 'insufficient_data', 'roadmapRevision': 1, 'dates': None}
    tasks = {'one': {'lifecycle': 'Identified', 'progress': 0},
             'two': {'lifecycle': 'Identified', 'progress': 0}}
    return state, saved, priority, catalog, timeline, tasks


def test_complete_free_trial_roadmap_does_not_invent_level_gap_or_date():
    output = render_initial_roadmap(*fixture())
    assert 'Говорить увереннее на работе' in output
    assert 'Текущий уровень по наблюдениям Speaky: пока не определён' in output
    assert 'Твоя самооценка: B1' in output
    assert 'Общая цель по CEFR: B2' in output
    assert output.index('Рабочий разговор') < output.index('Обсуждение с коллегами')
    assert 'по теме пока недостаточно наблюдений' in output
    assert 'трудность с темой пока предположительная' in output
    assert 'ещё не начата (0%)' in output
    assert 'Сейчас важнее разговор' in output
    assert 'Ориентир по времени' not in output
    assert 'обсудим здесь удобный ритм' in output


def test_timeline_only_when_saved_revision_matches_and_has_range():
    inputs = list(fixture())
    inputs[4] = {'status': 'provisional', 'roadmapRevision': 1,
                 'dates': {'earliest': '2027-01-01', 'latest': '2027-06-01'}}
    assert 'с 2027-01-01 по 2027-06-01' in render_initial_roadmap(*inputs)
    inputs[4]['roadmapRevision'] = 2
    assert '2027-01-01' not in render_initial_roadmap(*inputs)


def test_reused_task_has_no_fabricated_zero_and_stale_priority_is_blocked():
    inputs = list(fixture())
    inputs[5] = deepcopy(inputs[5])
    inputs[5]['one'] = {'lifecycle': 'In Progress', 'progress': 36}
    output = render_initial_roadmap(*inputs)
    assert output.count('ещё не начата (0%)') == 1
    assert '36%' not in output
    inputs[2] = deepcopy(inputs[2])
    inputs[2]['stale'] = True
    with pytest.raises(ValueError, match='initial_priority_not_ready'):
        render_initial_roadmap(*inputs)


def test_early_deadline_is_explained_without_promising_a_faster_date():
    inputs = list(fixture())
    inputs[4] = {'status': 'provisional', 'roadmapRevision': 1,
                 'dates': {'earliest': '2029-10-03', 'latest': '2030-11-27'},
                 'deadline': {'desired': '2026-12-18', 'relation': 'earlier_than_range'}}
    output = render_initial_roadmap(*inputs)
    assert 'Желаемый срок 2026-12-18 раньше даже самой ранней даты прогноза.' in output
    assert 'обсудить более частые или длинные занятия либо перенести срок' in output
    inputs[4]['roadmapRevision'] = 2
    assert '2026-12-18' not in render_initial_roadmap(*inputs)


def test_saved_priority_reason_is_presented_in_russian():
    inputs = list(fixture())
    inputs[2] = deepcopy(inputs[2])
    inputs[2]['priority']['document']['orderedTasks'][0]['reason'] = (
        'This is proposed coverage; the gap still needs verification. It supports the learner goal.')
    output = render_initial_roadmap(*inputs)
    assert 'сначала проверим, есть ли здесь пробел' in output
    assert 'Это также связано с твоей целью' in output


def test_initial_roadmap_worker_endpoint_serves_only_the_saved_revision():
    from types import SimpleNamespace
    from unittest.mock import AsyncMock

    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    from app.trial_api import install_trial_api

    state, saved, priority, catalog, timeline, tasks = fixture()
    backend = SimpleNamespace(
        learning_roadmap=AsyncMock(return_value={'roadmap': dict(saved, catalog_version='published')}),
        learning_state=AsyncMock(return_value=state),
        learning_roadmap_priority=AsyncMock(return_value=priority),
        catalog=AsyncMock(return_value=catalog),
        roadmap_timeline=AsyncMock(return_value=timeline),
        learning_tasks=AsyncMock(return_value={'items': [dict(task, id=id) for id, task in tasks.items()],
                                              'nextOffset': None}),
    )
    app = FastAPI()
    install_trial_api(app, backend, None, None, None, SimpleNamespace(), None)
    path = '/internal/trial/initial-roadmap'
    headers = {'X-Execution-Scope': 'trial-voice-scope'}
    with TestClient(app) as client:
        rejected = client.post(path, headers=headers, json={'roadmapRevision': 2})
        backend.learning_state.assert_not_awaited()
        accepted = client.post(path, headers=headers, json={'roadmapRevision': 1})

    assert rejected.status_code == 409
    assert rejected.json()['detail'] == 'stale_roadmap'
    assert accepted.status_code == 200
    assert accepted.json() == {'roadmapRevision': 1,
                              'reply': render_initial_roadmap(state, saved, priority, catalog, timeline, tasks)}
    backend.learning_state.assert_awaited_once_with('trial-voice-scope')
