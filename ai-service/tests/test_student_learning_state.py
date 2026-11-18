from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx
import pytest
from pydantic import ValidationError

from app.context_builder import ContextBuilder
from app.foundation_client import BackendFailure, FoundationClient
from app.foundation_tutor import FoundationTutor
from app.memory_tools import RecordMemory
from app.student_learning_state import UpdatePersonalContext


@pytest.mark.parametrize('key', ['goal', 'level', 'self_assessment', 'intensity', 'homework_preferences'])
def test_personal_tools_cannot_write_learning_fields(key):
    with pytest.raises(ValidationError):
        RecordMemory(category='personal', key=key, expectedVersion=0, evidence='B2', value='B2', kind='user_report')
    with pytest.raises(ValidationError):
        UpdatePersonalContext(facts=[dict(memory='personal', key=key, value='B2', evidence='B2', kind='user_report')],
                              trialPreference=None, preferenceEvidence=None, summary=None)


@pytest.mark.asyncio
async def test_active_tutor_rejects_old_learning_writer_without_calling_backend():
    backend = SimpleNamespace(command=AsyncMock(), receipt=AsyncMock())
    tutor = FoundationTutor(backend, None, None)
    result = await tutor.execute('scope', 'request', {'id': 'event', 'body': 'B2'}, 'update_learner_context', {
        'facts': [dict(memory='learning', key='level', value='B2', evidence='B2', kind='self_assessment')],
        'trialPreference': None, 'preferenceEvidence': None, 'summary': None,
    })
    assert result['ok'] is False
    backend.command.assert_not_called()
    backend.receipt.assert_not_called()


@pytest.mark.asyncio
async def test_learning_client_preserves_scope_versions_pagination_and_operation_id():
    requests = []
    def handle(request):
        requests.append(request)
        return httpx.Response(200, json={'ok': True, 'result': {'schemaVersion': 1, 'revision': 2}})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http:
        backend = FoundationClient('http://backend', 'service', http)
        await backend.learning_state('scope')
        await backend.topic_knowledge('scope', 'v1', topic_id='grammar-topic', offset=50, history=True)
        await backend.update_learning_state('scope', 'same-id', expected_revision=1,
            patch={'homework': {'minutes': None}}, component='test', sources=[], goal=None)
    assert all(r.headers['X-Execution-Scope'] == 'scope' for r in requests)
    assert requests[1].url.params['history'] == 'true'
    assert requests[1].url.params['catalogVersion'] == 'v1'
    import json
    payload = json.loads(requests[2].content)
    assert payload['operationId'] == 'same-id'
    assert payload['args']['goal'] is None
    assert payload['args']['patch'] == {'homework': {'minutes': None}}


@pytest.mark.asyncio
async def test_no_legacy_level_or_goal_fallback_and_read_does_not_write():
    snapshot = {'version': 1, 'student': {'id': 's', 'tutor_id': 'sasha', 'tutor_version': 1, 'version': 1, 'data_epoch': 1},
                'facts': [{'category': 'learning', 'fact_key': 'level', 'value': 'C1', 'kind': 'self_assessment'}],
                'goal': {'content': {'value': 'old goal'}}, 'events': []}
    backend = SimpleNamespace(snapshot=AsyncMock(return_value=snapshot), command=AsyncMock())
    context = await ContextBuilder(backend).build('scope', 'text')
    assert context.data['facts'] == []
    assert context.data['goal'] is None
    backend.command.assert_not_called()


def test_bounded_context_marks_omissions_and_does_not_mutate_storage():
    from app.student_learning_state import learning_context
    snapshot = {'learningState': {'profile': {'currentLevels': {'speaky': {
        'overall': {'level': 'B1', 'explanation': 'a' * 500}}}}, 'provenance': {'some': {'sources': []}}},
        'topicKnowledge': {'catalogVersion': 'v1', 'items': [
            {'topic_id': 'first', 'status': 'Known', 'explanation': 'large' * 100}], 'nextOffset': None}}
    state, topics = learning_context(snapshot, 20)
    assert state['profile']['currentLevels']['speaky']['overall']['level'] == 'B1'
    assert state['contextPartial'] is True
    assert 'provenance' not in state
    assert len(snapshot['learningState']['profile']['currentLevels']['speaky']['overall']['explanation']) == 500
    assert topics['items'] == [] and topics['nextOffset'] == 0
    assert topics['missingItemsAreNotUnknown'] is True


@pytest.mark.asyncio
async def test_onboarding_writer_uses_current_source_and_stable_receipt():
    backend = SimpleNamespace(command=AsyncMock(return_value={'revision': 1}))
    tutor = FoundationTutor(backend, None, None)
    args = {'expectedRevision': 0, 'changes': [
        {'field': 'targetLevel', 'value': 'B2', 'explanation': None, 'evidence': 'B2'}]}
    source = {'id': 'current', 'body': 'I want B2'}
    first = await tutor.execute('scope', 'request', source, 'update_onboarding', args)
    await tutor.execute('scope', 'request', source, 'update_onboarding', args)
    assert first == {'ok': True, 'saved': True, 'learningState': {'revision': 1}}
    assert backend.command.call_args_list[0] == backend.command.call_args_list[1]
    assert backend.command.call_args.args[2] == 'onboarding.update'
    assert backend.command.call_args.args[3]['sourceId'] == 'current'


@pytest.mark.asyncio
@pytest.mark.parametrize('field,evidence', [('speaky', 'B2'), ('targetLevel', 'not in message')])
async def test_onboarding_rejects_forbidden_fields_and_unbacked_evidence(field, evidence):
    backend = SimpleNamespace(command=AsyncMock())
    tutor = FoundationTutor(backend, None, None)
    result = await tutor.execute('scope', 'request', {'id': 'current', 'body': 'B2'}, 'update_onboarding', {
        'expectedRevision': 0, 'changes': [
            {'field': field, 'value': 'B2', 'explanation': None, 'evidence': evidence}]})
    assert result['ok'] is False
    backend.command.assert_not_called()

@pytest.mark.asyncio
async def test_onboarding_saves_exact_goal_and_self_report_when_another_field_has_no_source():
    backend = SimpleNamespace(command=AsyncMock(return_value={'revision': 1}))
    tutor = FoundationTutor(backend, None, None)
    goal = 'Хочу переехать в США, устроиться там Go-разработчиком и свободно общаться с людьми.'
    source = {'id': 'current', 'body': f'{goal} Думаю, я B1'}
    changes = [
        {'field': 'goal', 'value': {'goals': ['G05', 'G03', 'G01'], 'mode': 'replace'},
         'explanation': None, 'evidence': goal},
        {'field': 'selfAssessment', 'value': 'B1', 'explanation': 'Думаю, я B1', 'evidence': 'Думаю, я B1'},
        {'field': 'deadline', 'value': '2026-12-31', 'explanation': None, 'evidence': 'к концу декабря'},
    ]
    result = await tutor.execute('scope', 'request', source, 'update_onboarding',
                                 {'expectedRevision': 0, 'changes': changes})
    assert result['saved'] and 'skipped' in result['guidance']
    action, payload = backend.command.call_args.args[2:]
    assert action == 'onboarding.update'
    assert payload == {'sourceId': 'current', 'expectedRevision': 0, 'changes': changes[:2]}
