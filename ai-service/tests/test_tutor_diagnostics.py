import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.foundation_client import BackendFailure
from app.foundation_tutor import FoundationTutor


def tutor():
    backend = SimpleNamespace(receipt=AsyncMock(return_value=None), snapshot=AsyncMock(return_value={'booking': None}),
                              command=AsyncMock())
    return FoundationTutor(backend, None, None)


def booking_args():
    return {'intent': 'explicit_request', 'evidence': 'PRIVATE_MESSAGE', 'schedule': {
        'mode': 'now', 'localDateTime': None, 'timezone': None, 'offset': None, 'shiftMinutes': None, 'date': None}}


def records(caplog):
    return [json.loads(r.message.removeprefix('tutor_tool ')) for r in caplog.records if r.message.startswith('tutor_tool ')]


@pytest.mark.asyncio
async def test_rejected_evidence_logs_stage_and_code_without_changing_tool_result(caplog):
    caplog.set_level('INFO', logger='app.tutor_diagnostics')
    t = tutor()
    result = await t.execute('SECRET_SCOPE', 'PRIVATE_REQUEST', {'id': 'PRIVATE_ID', 'body': '/start'},
                             'create_trial_booking', booking_args())
    assert result == {'ok': False, 'error': 'invalid_evidence'}
    rejected = next(r for r in records(caplog) if r['outcome'] == 'rejected')
    assert rejected['tool'] == 'create_trial_booking'
    assert rejected['stage'] == 'validate_evidence'
    assert rejected['code'] == 'invalid_evidence'
    assert records(caplog)[-1]['ok'] is False
    t.backend.command.assert_not_called()
    for private in ('PRIVATE_MESSAGE', 'PRIVATE_REQUEST', 'PRIVATE_ID', 'SECRET_SCOPE', '/start'):
        assert private not in caplog.text


@pytest.mark.asyncio
async def test_missing_source_field_is_distinguished_from_evidence_rejection(caplog):
    caplog.set_level('INFO', logger='app.tutor_diagnostics')
    result = await tutor().execute('scope', 'request', {'id': 'source', 'body': 'PRIVATE_MESSAGE'},
                                    'create_trial_booking', booking_args())
    assert result == {'ok': False, 'error': "'occurred_at'"}
    rejected = next(r for r in records(caplog) if r['outcome'] == 'rejected')
    assert rejected['stage'] == 'resolve_time'
    assert rejected['code'] == 'missing_field'
    assert rejected['field'] == 'occurred_at'


@pytest.mark.asyncio
async def test_invalid_schema_logs_types_and_field_names_without_values(caplog):
    caplog.set_level('INFO', logger='app.tutor_diagnostics')
    args = booking_args()
    args['intent'] = 'PRIVATE_VALUE'
    args['PRIVATE_EXTRA_FIELD'] = 'PRIVATE_EXTRA_VALUE'
    result = await tutor().execute('scope', 'request', {}, 'create_trial_booking', args)
    assert result['ok'] is False
    rejected = next(r for r in records(caplog) if r['outcome'] == 'rejected')
    assert rejected['code'] == 'invalid_tool_arguments'
    assert {'type': 'literal_error', 'fields': ['intent']} in rejected['validation']
    for private in ('PRIVATE_VALUE', 'PRIVATE_EXTRA_FIELD', 'PRIVATE_EXTRA_VALUE', 'PRIVATE_MESSAGE'):
        assert private not in caplog.text


@pytest.mark.asyncio
async def test_successful_read_logs_absent_booking_as_success(caplog):
    caplog.set_level('INFO', logger='app.tutor_diagnostics')
    result = await tutor().execute('scope', 'request', {}, 'get_trial_booking', {})
    assert result == {'ok': True, 'booking': None}
    assert records(caplog)[-1]['ok'] is True
    assert records(caplog)[-1]['booking_present'] is False
    assert all(r['outcome'] != 'rejected' for r in records(caplog))


@pytest.mark.asyncio
async def test_backend_failure_preserves_status_and_propagation(caplog):
    caplog.set_level('INFO', logger='app.tutor_diagnostics')
    t = tutor()
    t.backend.snapshot.side_effect = BackendFailure('transport_outcome_unknown', 503)
    with pytest.raises(BackendFailure):
        await t.execute('SECRET_SCOPE', 'request', {}, 'get_trial_booking', {})
    failure = next(r for r in records(caplog) if r['outcome'] == 'rejected')
    assert failure['stage'] == 'snapshot'
    assert failure['code'] == 'transport_outcome_unknown'
    assert failure['http_status'] == 503
    assert 'SECRET_SCOPE' not in caplog.text
