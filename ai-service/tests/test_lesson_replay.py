"""Local relay replay. The Foundation protocol boundary is a test double."""
from datetime import datetime, timezone
from types import SimpleNamespace

import pytest

from tests.lesson_replay import FIRST, HISTORY, replay


@pytest.mark.parametrize('kind', ('trial', 'regular'))
async def test_lesson_relay_replays_across_two_connections(monkeypatch, kind):
    lesson_id = 'replay-lesson'
    lesson = {'id': lesson_id, 'kind': kind, 'starts_at': datetime.now(timezone.utc).isoformat(),
              'duration_minutes': 20 if kind == 'trial' else 30}
    if kind == 'trial':
        lesson['trial_version'] = 'v5.5'
    trial_state = {'phase': 'warmup', 'connectedMs': 0, 'connectedSeconds': 0}
    journal, ends, admissions = [], [], []

    async def request(method, path, scope, *, json):
        assert (method, path, scope) == ('POST', '/trial/access', '')
        assert json['action'] == 'open' and json['lessonId'] == lesson_id
        admissions.append(json['attemptId'])
        episode = f'connection-{len(admissions)}'
        return {'lesson': lesson, 'scope': episode, 'callSessionId': episode, 'trialExecution': trial_state}

    async def command(scope, _operation_id, action, args):
        if action == 'lesson.next':
            return {'lesson': None}
        assert args['lessonId'] == lesson_id and args['callSessionId'] == scope
        if action == 'voice.audio':
            return {'objectKey': f'{scope}/{args["direction"]}/{args["sequence"]}.pcm'}
        if action == 'voice.append':
            journal.append((args['role'], args['body']))
            return {'eventId': f'turn-{len(journal)}', 'sequence': len(journal)}
        if action == 'voice.end':
            ends.append((scope, args['reason']))
        if action == 'trial.ready':
            return trial_state
        assert action in {'voice.audio_stored', 'voice.playback', 'voice.end', 'trial.dialogue'}, action
        return {}

    async def context(_scope, purpose, target):
        assert (purpose, target) == ('voice', lesson_id)
        sources = [{'id': f'turn-{i}', 'sequence': i, 'body': body}
                   for i, (role, body) in enumerate(journal, 1) if role == 'user']
        return SimpleNamespace(data={'voiceSources': sources},
                               history=[{'role': role, 'content': body} for role, body in journal])

    async def empty(*_, **__):
        return {}

    async def disconnected():
        assert ends == [('connection-1', 'technical_disconnect')]
        assert journal == [('user', FIRST)]

    backend = SimpleNamespace(_request=request, command=command, catalog=empty,
                              learning_tasks=empty, learning_problems=empty)
    await replay(monkeypatch, backend, SimpleNamespace(build=context), lesson_id, 'replay-auth', kind, disconnected)
    assert len(set(admissions)) == 2
    assert journal == HISTORY
    assert ends == [('connection-1', 'technical_disconnect'),
                    ('connection-2', 'learner_requested' if kind == 'trial' else 'natural_goodbye')]
