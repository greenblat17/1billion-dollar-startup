import asyncio
import json
import logging
import re
from types import SimpleNamespace
from unittest.mock import AsyncMock
from pathlib import Path

import httpx
import openai
import pytest

from app.diagnostics import EventSpec, context_fields, diagnostic_context, error_fields, event, request_trace
from app.foundation_client import FoundationClient
from app.trial_providers import KoraTrialTTS


def records(caplog):
    return [json.loads(r.message.removeprefix('runtime_event ')) for r in caplog.records
            if r.message.startswith('runtime_event ')]


def denied():
    response = httpx.Response(402, request=httpx.Request('POST', 'https://openrouter.ai/api/v1/audio/transcriptions'),
                             headers={'x-request-id': 'req_1234567890', 'retry-after': '10'})
    return openai.APIStatusError('private exception repr', response=response, body={'error': {
        'code': 402, 'message': 'Credits exhausted. token=SECRET_VALUE input "PRIVATE_WORDS"',
        'metadata': {'reason': 'in_flight_budget_exhausted', 'limit_source': 'openrouter_in_flight_budget',
                     'raw': 'PRIVATE_RAW'}}})


def test_provider_reason_survives_without_payloads_or_secret(monkeypatch):
    monkeypatch.setenv('TEST_API_KEY', 'SECRET_VALUE')
    result = error_fields(denied())
    assert result['provider_error']['reason'] == 'in_flight_budget_exhausted'
    assert result['provider_error']['limit_source'] == 'openrouter_in_flight_budget'
    assert result['provider_error']['code'] == '402'
    assert result['provider_request_id'] == 'req_1234567890'
    assert result['retry_after'] == 10
    assert 'message' not in result['provider_error']
    for secret in ('PRIVATE_WORDS', 'PRIVATE_RAW', 'SECRET_VALUE', 'private exception repr'):
        assert secret not in json.dumps(result)


@pytest.mark.asyncio
async def test_parallel_contexts_do_not_mix_and_reset(caplog):
    caplog.set_level(logging.INFO)
    async def work(trace):
        with diagnostic_context(trace_id=trace, lesson_id='lesson-'+trace):
            await asyncio.sleep(0)
            event('check', stage='test')
    await asyncio.gather(work('one'), work('two'))
    assert {(r['trace_id'], r['lesson_id']) for r in records(caplog)} == {('one', 'lesson-one'), ('two', 'lesson-two')}
    assert context_fields() == {}


@pytest.mark.asyncio
async def test_backend_trace_propagates_and_transport_failure_has_stage(caplog):
    caplog.set_level(logging.INFO)
    seen = []
    def handler(request):
        seen.append(request)
        raise httpx.ConnectError('PRIVATE_URL', request=request)
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        backend = FoundationClient('http://backend', 'SECRET_SCOPE', http)
        with diagnostic_context(trace_id='trace', lesson_id='lesson', call_session_id='episode'):
            with pytest.raises(Exception):
                await backend.command('SECRET_SCOPE', 'operation', 'voice.end', {})
    assert seen[0].headers['X-Diagnostic-Trace'] == 'trace'
    failed = next(r for r in records(caplog) if r['event'] == 'backend_failed')
    assert failed['operation_id'] == request_trace('operation')
    assert failed['error_type'] == 'ConnectError'
    assert failed['stage'] == 'transport'
    assert failed['stack']
    assert 'PRIVATE_URL' not in caplog.text and 'SECRET_SCOPE' not in caplog.text


@pytest.mark.asyncio
@pytest.mark.parametrize('partial', [False, True])
async def test_tts_errors_before_and_during_stream_are_logged(partial, caplog):
    caplog.set_level(logging.INFO)
    class Stream:
        async def __aenter__(self):
            if not partial:
                raise denied()
            return self
        async def __aexit__(self, *args): pass
        async def iter_bytes(self, **kwargs):
            yield b'12'
            raise denied()
    client = SimpleNamespace(audio=SimpleNamespace(speech=SimpleNamespace(
        with_streaming_response=SimpleNamespace(create=lambda **kwargs: Stream()))))
    with diagnostic_context(trace_id='trace', call_session_id='episode'):
        with pytest.raises(openai.APIStatusError):
            async for _ in KoraTrialTTS(client, 'model', 'voice').stream('PRIVATE_INPUT'):
                pass
    failed = next(r for r in records(caplog) if r.get('outcome') == 'failed')
    assert failed['bytes_sent'] == (2 if partial else 0)
    assert failed['trace_id'] == 'trace' and failed['call_session_id'] == 'episode'
    assert failed['http_status'] == 402
    assert 'PRIVATE_INPUT' not in caplog.text


def test_diagnostic_export_failure_does_not_raise_and_reports_loss_after_recovery(monkeypatch, caplog):
    caplog.set_level(logging.INFO)
    with monkeypatch.context() as patch:
        patch.setattr(logging.Logger, 'log',
                      lambda *a, **k: (_ for _ in ()).throw(RuntimeError('sink down')))
        assert event('failed', error=ValueError('safe_code'), stage='test')
    event('recovered')
    event('next')
    first, second = records(caplog)
    assert first['diagnostic_losses'] == 1 and 'diagnostic_losses' not in second


def test_checked_event_uses_its_template_and_trusted_runtime_metadata(caplog, monkeypatch):
    monkeypatch.setenv('SPEAKY_ENV', 'dev')
    monkeypatch.setenv('SPEAKY_RELEASE', '8e6a272d')
    caplog.set_level(logging.INFO)
    spec = EventSpec('roadmap_decision', 'roadmap', 'roadmap.generate', 'plan', 'skipped',
                     'Roadmap generation was skipped because the learner target level is missing.',
                     'missing_target_level', fields=frozenset({'duration_ms'}))
    event(spec, duration_ms=5, outcome='completed', message='PRIVATE_WORDS', release='untrusted',
          model='PRIVATE_TOKEN')
    [row] = records(caplog)
    schema_path = Path(__file__).resolve().parents[2] / 'contracts/logging/v1/envelope.json'
    schema = json.loads(schema_path.read_text())
    json_types = {'integer': int, 'string': str, 'null': type(None)}
    for name in schema['required']:
        rule, value = schema['properties'][name], row[name]
        property_types = rule['type']
        types = property_types if isinstance(property_types, list) else [property_types]
        assert type(value) in {json_types[kind] for kind in types}, name
        if 'const' in rule:
            assert value == rule['const']
        if 'enum' in rule:
            assert value in rule['enum']
        if 'pattern' in rule:
            assert re.fullmatch(rule['pattern'], value)
        if 'maxLength' in rule:
            assert rule['minLength'] <= len(value) <= rule['maxLength']
    assert (row['schema_version'], row['environment'], row['release']) == (1, 'dev', '8e6a272d')
    assert type(row['schema_version']) is int
    assert (row['component'], row['operation'], row['stage']) == ('roadmap', 'roadmap.generate', 'plan')
    assert row['message'] == spec.message and row['outcome'] == 'skipped'
    assert row['reason_code'] == 'missing_target_level' and row['duration_ms'] == 5
    assert row['timestamp'].endswith('Z') and row['level'] == 'INFO'
    assert 'model' not in row and 'PRIVATE_WORDS' not in caplog.text


def test_client_diagnostics_authenticates_and_rejects_free_text(caplog):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from app.trial_api import install_trial_api
    from app.foundation_client import BackendFailure
    caplog.set_level(logging.INFO)
    backend = SimpleNamespace(_request=AsyncMock(return_value={}))
    app = FastAPI()
    install_trial_api(app, backend, None, None, None, None, None)
    with TestClient(app) as client:
        response = client.post('/miniapp/diagnostics', json={'initData': 'SECRET_INIT', 'attemptId': 'b036917a-1c72-45a4-a483-e085bc075da3',
                               'stage': 'microphone', 'code': 'PRIVATE_WORDS'})
        assert response.status_code == 200
        assert records(caplog)[-1]['client_code'] == 'unknown'
        assert records(caplog)[-1]['client_attempt_id'] == 'b036917a-1c72-45a4-a483-e085bc075da3'
        assert records(caplog)[-1]['trace_id'] != records(caplog)[-1]['client_attempt_id']
        report_count = len(records(caplog))
        assert client.post('/miniapp/diagnostics', content='PRIVATE_OVERSIZED' * 2000).status_code == 413
        assert backend._request.await_count == 1
        assert len(records(caplog)) == report_count
        backend._request.side_effect = BackendFailure('invalid_telegram_auth', 403)
        assert client.post('/miniapp/diagnostics', json={'initData': 'bad'}).status_code == 403
    assert 'PRIVATE_' not in caplog.text and 'SECRET_INIT' not in caplog.text


@pytest.mark.parametrize(('action', 'stage', 'code', 'outcome'), [
    ('progress', 'transport', 'request_failed', 'unknown'),
    ('meetings', 'parse', 'invalid_response', 'failed'),
    ('demo_pay', 'response', 'response_rejected', 'rejected'),
    ('calendar', 'export', 'share_cancelled', 'cancelled'),
    ('voice', 'render', 'render_failed', 'failed'),
])
def test_client_report_keeps_only_valid_action_and_server_link(caplog, action, stage, code, outcome):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from app.trial_api import install_trial_api

    caplog.set_level(logging.INFO)
    backend = SimpleNamespace(_request=AsyncMock(return_value={}))
    app = FastAPI()
    install_trial_api(app, backend, None, None, None, None, None)
    server_id = 'b' * 32
    payload = {'initData': 'SECRET_INIT', 'action': action, 'stage': stage, 'code': code,
               'serverErrorId': server_id, 'message': 'PRIVATE_TRANSCRIPT', 'attemptId': 'PRIVATE_WORDS'}
    with TestClient(app) as client:
        assert client.post('/miniapp/diagnostics', json=payload).status_code == 200
        assert client.post('/miniapp/diagnostics', json=payload | {'code': 'PRIVATE_CODE'}).status_code == 400
    [row] = records(caplog)
    assert (row['operation'], row['stage'], row['reason_code'], row['outcome']) == (
        f'miniapp.{action}', stage, code, outcome)
    assert row['server_error_id'] == server_id and 'client_attempt_id' not in row
    assert 'PRIVATE_' not in caplog.text and 'SECRET_INIT' not in caplog.text


@pytest.mark.parametrize(('stage', 'code', 'outcome', 'level', 'message'), [
    ('audio_context', 'audio_activated', 'completed', 'INFO',
     'Lesson voice resumed audio after the learner activated it.'),
    ('close', 'end_queued', 'unknown', 'INFO',
     'Lesson voice queued the end request on the client socket. Delivery is unconfirmed.'),
    ('close', 'end_not_sent', 'cancelled', 'INFO',
     'Lesson voice closed after an end request with no open socket.'),
    ('close', 'end_send_failed', 'failed', 'WARNING',
     'Lesson voice could not queue the end request on the client socket.'),
    ('close', 'page_hidden', 'cancelled', 'INFO',
     'Lesson voice stopped the client lesson attempt when the browser hid the page.'),
])
def test_voice_lifecycle_report_keeps_only_the_client_observation(caplog, stage, code, outcome, level, message):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from app.trial_api import install_trial_api

    caplog.set_level(logging.INFO)
    backend = SimpleNamespace(_request=AsyncMock(return_value={}))
    app = FastAPI()
    install_trial_api(app, backend, None, None, None, None, None)
    attempt_id = 'a' * 32
    payload = {'initData': 'SECRET_INIT', 'action': 'voice', 'stage': stage, 'code': code,
               'attemptId': attempt_id, 'lessonId': 'PRIVATE_LESSON', 'message': 'PRIVATE_TRANSCRIPT'}
    with TestClient(app) as client:
        assert client.post('/miniapp/diagnostics', json=payload).status_code == 200
        assert client.post('/miniapp/diagnostics', json=payload | {'code': 'PRIVATE_CODE'}).status_code == 400
    [row] = records(caplog)
    assert (row['event'], row['operation'], row['stage'], row['reason_code'], row['outcome'], row['level']) == (
        'miniapp_voice_lifecycle', 'miniapp.voice', stage, code, outcome, level)
    assert row['message'] == message and row['client_attempt_id'] == attempt_id
    assert row['trace_id'] != attempt_id and 'lesson_id' not in row
    assert 'PRIVATE_' not in caplog.text and 'SECRET_INIT' not in caplog.text


@pytest.mark.parametrize(('action', 'result', 'writer', 'reason'), [
    ('calendar', {'lesson': {}}, 'miniapp_calendar_export', 'calendar_export_failed'),
    ('audio', {'objects': [{'object_key': 'PRIVATE_OBJECT'}]}, 'miniapp_audio_links', 'audio_signing_failed'),
])
def test_miniapp_calendar_and_audio_failures_link_to_one_primary_record(
        monkeypatch, caplog, action, result, writer, reason):
    from fastapi.testclient import TestClient
    from app import speaky_main

    monkeypatch.setenv('SPEAKY_ENV', 'local')
    monkeypatch.setenv('AI_INTERNAL_TOKEN', 'private-test-token')
    monkeypatch.setenv('OPENAI_API_KEY', 'private-model-token')
    monkeypatch.setenv('FOUNDATION_BACKEND_URL', 'http://127.0.0.1:1')
    monkeypatch.setenv('TRIAL_AUDIO_BUCKET', 'placeholder')
    monkeypatch.setattr(speaky_main.FoundationClient, '_request', AsyncMock(return_value=result))
    storage = SimpleNamespace(cleanup=AsyncMock(), link=AsyncMock(side_effect=RuntimeError('PRIVATE_SIGNING')))
    monkeypatch.setattr(speaky_main, 'TrialAudioStore', lambda *_: storage)

    caplog.set_level(logging.INFO)
    with TestClient(speaky_main.create_app()) as client:
        response = client.post('/miniapp/api', json={'action': action, 'initData': 'PRIVATE_INIT'})
    assert response.status_code == 500
    [primary, boundary] = [row for row in records(caplog) if row['trace_id'] == response.headers['X-Diagnostic-Trace']]
    assert (primary['event'], primary['outcome'], primary['reason_code']) == (writer, 'failed', reason)
    assert boundary['event'] == 'http_failed'
    assert primary['error_id'] == boundary['error_id'] == response.headers['X-Error-ID']
    assert 'stack' in primary and 'stack' not in boundary
    assert 'PRIVATE_' not in caplog.text


@pytest.mark.parametrize('configured', [True, False])
def test_miniapp_audio_links_log_counts_or_unavailable_without_keys(caplog, configured):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from app.trial_api import install_trial_api

    object_key = 'PRIVATE_OBJECT'
    signed_url = 'https://recording.invalid/PRIVATE_SIGNED'
    backend = SimpleNamespace(_request=AsyncMock(return_value={'objects': [{'object_key': object_key}]}))
    storage = SimpleNamespace(link=AsyncMock(return_value=signed_url)) if configured else None
    app = FastAPI()
    install_trial_api(app, backend, None, None, None, None, storage)
    caplog.set_level(logging.INFO)
    with TestClient(app) as client:
        response = client.post('/miniapp/api', json={'action': 'audio', 'initData': 'PRIVATE_INIT'})
    [row] = records(caplog)
    assert (row['event'], row['stage']) == ('miniapp_audio_links', 'sign')
    if configured:
        assert response.status_code == 200 and response.json()['objects'][0]['url'] == signed_url
        assert row['outcome'] == 'completed' and row['count'] == 1
        storage.link.assert_awaited_once_with(object_key)
    else:
        assert response.status_code == 503 and response.json()['detail'] == 'recording_unavailable'
        assert row['outcome'] == 'failed' and row['reason_code'] == 'recording_unavailable'
        assert row['error_id'] == response.headers['X-Error-ID']
    assert 'PRIVATE_' not in caplog.text


def test_voice_socket_uses_one_server_trace_through_rejection(caplog):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from app.trial_api import install_trial_api
    from app.foundation_client import BackendFailure

    caplog.set_level(logging.INFO)
    backend = SimpleNamespace(_request=AsyncMock(side_effect=[BackendFailure('not_owned', 403) for _ in range(2)]))
    store = SimpleNamespace(check_credentials=AsyncMock())
    app = FastAPI()
    install_trial_api(app, backend, None, None, None,
                      SimpleNamespace(openai_realtime_api_key='configured'), store)
    attempt_id = 'a' * 32
    with TestClient(app) as client:
        traces = set()
        for _ in range(2):
            caplog.clear()
            with client.websocket_connect('/miniapp/voice') as socket:
                socket.send_json({'protocol': 'realtime-pcm-v1', 'attemptId': attempt_id,
                                  'lessonId': 'lesson', 'initData': 'PRIVATE_INIT'})
                response = socket.receive_json()
            [start, finish] = records(caplog)
            assert start['trace_id'] == finish['trace_id'] != attempt_id
            traces.add(start['trace_id'])
            assert start['request_id'] == finish['request_id']
            assert finish['client_attempt_id'] == attempt_id and 'client_attempt_id' not in start
            assert finish['outcome'] == 'rejected' and finish['reason_code'] == 'backend_rejected'
            assert finish['error_id'] == response['errorId']
            assert 'PRIVATE_INIT' not in caplog.text
        assert len(traces) == 2


@pytest.mark.asyncio
@pytest.mark.parametrize('detailed', [True, False])
async def test_provider_400_records_only_allowlisted_details_and_schema(detailed, caplog):
    from app.explanation_exercises import ASSESSMENT_FORMAT
    from app.llm import OpenAiChatModel

    detail = {'code': 400, 'message': 'private_learner_name', 'type': 'private_token',
              'param': 'private_prompt', 'metadata': {'raw': {'token': 'private_payload'},
                                                    'provider_name': 'private_upstream'}}
    if detailed:
        detail.update(code='invalid_json_schema', type='invalid_request_error',
                      param='response_format.json_schema.schema')
        detail['metadata']['provider_name'] = 'OpenAI'
    response = httpx.Response(400, request=httpx.Request('POST', 'https://openrouter.ai/api/v1/chat/completions'),
                             headers={'x-request-id': 'req_1234567890' if detailed else 'private_header'})
    failure = openai.APIStatusError('private_exception', response=response, body={'error': detail})
    complete = AsyncMock(side_effect=failure)
    client = SimpleNamespace(base_url='https://openrouter.ai/api/v1',
                             chat=SimpleNamespace(completions=SimpleNamespace(create=complete)))
    trace = 'acfe7df0352849799b985376a26d3032'
    with diagnostic_context(trace_id=trace):
        with pytest.raises(openai.APIStatusError):
            await OpenAiChatModel(client, 'configured-model').complete_json(
                'private_prompt', 'private_transcript', operation='sp14_teaching_action', retry=False,
                response_format=ASSESSMENT_FORMAT)
        error_id = event('sp14_assessment_rejected', error=failure, stage='provider', reason_code='assessment_provider_error')

    complete.assert_awaited_once()
    assert complete.await_args.kwargs['response_format'] == ASSESSMENT_FORMAT
    [provider] = [json.loads(r.message.partition('provider_call ')[2]) for r in caplog.records
                  if r.message.startswith('provider_call ')]
    [boundary] = records(caplog)
    assert provider['trace_id'] == boundary['trace_id'] == trace
    assert provider['error_id'] == boundary['error_id'] == error_id
    assert provider['http_status'] == 400 and provider['failure_class'] == 'expected'
    assert provider['provider'] == 'openrouter' and provider['operation'] == 'sp14_teaching_action'
    assert provider['schema_name'] == ASSESSMENT_FORMAT['json_schema']['name']
    assert len(provider['schema_hash']) == 64
    assert provider['stack'] and 'stack' not in boundary
    if detailed:
        assert provider['provider_error'] == {
            'code': 'invalid_json_schema', 'type': 'invalid_request_error',
            'field': 'response_format.json_schema.schema',
        }
        assert provider['upstream'] == 'openai' and provider['provider_request_id'] == 'req_1234567890'
        assert provider['provider_details_missing'] == []
    else:
        assert provider['provider_error'] == {'code': '400'}
        assert provider['provider_details_missing'] == ['type', 'field', 'upstream', 'request_id']
    assert 'private_' not in caplog.text


def test_nested_runtime_fields_and_correlation_headers_exclude_external_values(caplog):
    from app.diagnostics import correlation_headers
    allowed = 'a' * 32
    assert correlation_headers(httpx.Headers({
        'X-Diagnostic-Trace': allowed, 'X-Diagnostic-Lesson': 'private_name',
        'X-Diagnostic-Call': 'private_token', 'X-Diagnostic-Job': '123456789',
        'X-Request-ID': 'private_request', 'X-Diagnostic-Fence': '1234', 'X-Diagnostic-Attempt': '2',
    })) == {'trace_id': allowed, 'fence': 1234, 'attempt': 2}
    assert correlation_headers(httpx.Headers({
        'X-Diagnostic-Fence': '1000000000000', 'X-Diagnostic-Attempt': '+1',
    })) == {}
    caplog.set_level(logging.INFO)
    event('schema_check', usage={'input_tokens': 12, 'input_token_details': {
        'audio_tokens': 10, 'cached_tokens': 'private_token', 'body': 'private_transcript'}},
        fields=[{'field': 'topic', 'length': 12, 'body': {'token': 'private_token'}}],
        payload={'initData': 'private_init'})
    [row] = records(caplog)
    assert row['usage'] == {'input_tokens': 12, 'input_token_details': {'audio_tokens': 10}}
    assert row['fields'] == [{'field': 'topic', 'length': 12}]
    assert 'private_' not in caplog.text


def test_dev_consumer_reads_old_and_new_provider_records(monkeypatch, capsys):
    import runpy
    import subprocess
    import sys
    from pathlib import Path

    error_id, trace_id = 'c' * 32, 'd' * 32
    data = {'service': 'ai', 'event': 'provider_failed', 'trace_id': trace_id, 'http_status': 400}
    lines = [
        'tutor_tool ' + json.dumps({'tool': 'get_trial_booking', 'stage': 'apply', 'outcome': 'completed'}),
        'runtime_event ' + json.dumps(data | {'error_id': 'e' * 32}),
        'provider_call ' + json.dumps(data | {'error_id': error_id, 'provider_details_missing': [], 'provider_error': {
            'code': 'invalid_json_schema', 'field': 'response_format', 'message': 'private_response'}}),
    ]
    monkeypatch.setattr(subprocess, 'run',
                        lambda *args, **kwargs: SimpleNamespace(returncode=0, stdout='\n'.join(lines)))
    monkeypatch.setattr(sys, 'argv', ['diagnose-tutor.py', error_id])
    script = Path(__file__).resolve().parents[2] / 'infra/dev/diagnose-tutor.py'
    with pytest.raises(SystemExit) as exit:
        runpy.run_path(str(script))
    assert exit.value.code == 0
    output = capsys.readouterr().out
    result = json.loads(output)
    assert result['found'] and result['match']['provider_error']['field'] == 'response_format'
    assert result['related'][0]['error_id'] == 'e' * 32
    assert 'private_response' not in output



def test_wrapped_upstream_error_extracts_structured_fields_without_raw_payload():
    response = httpx.Response(400, request=httpx.Request('POST', 'https://openrouter.ai/api/v1/chat/completions'))
    error = openai.APIStatusError('private_exception', response=response, body={'error': {
        'code': 400,
        'metadata': {'provider_name': 'OpenAI', 'raw': json.dumps({'error': {
            'code': 'invalid_json_schema', 'type': 'invalid_request_error', 'param': 'response_format',
            'message': 'private_transcript', 'input': {'authorization': 'private_token'},
        }})},
    }})
    fields = error_fields(error)
    assert fields['provider_error'] == {'code': 'invalid_json_schema', 'gateway_code': '400',
                                        'type': 'invalid_request_error', 'field': 'response_format'}
    assert fields['provider_details_missing'] == ['request_id']
    assert 'private_' not in json.dumps(fields)


@pytest.mark.asyncio
async def test_audio_batch_success_is_quiet_and_failure_keeps_correlation(caplog):
    caplog.set_level(logging.INFO, logger='app.diagnostics')
    status = 200

    def handle(request):
        return httpx.Response(status, json={'ok': True, 'result': {}} if status == 200 else {
            'ok': False, 'error': 'storage_unavailable'})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http:
        client = FoundationClient('http://backend', 'private_token', http)
        with diagnostic_context(trace_id='f' * 32):
            await client.command('private_scope', 'test-operation', 'voice.audio', {})
            assert not records(caplog)
            status = 503
            from app.foundation_client import BackendFailure
            with pytest.raises(BackendFailure):
                await client.command('private_scope', 'test-operation', 'voice.audio_stored', {})
    [row] = records(caplog)
    assert (row['operation'], row['stage'], row['trace_id']) == ('voice.audio_stored', 'response', 'f' * 32)
    assert row['failure_class'] == 'transient'
    assert 'private_' not in caplog.text


def test_dev_backend_consumer_accepts_old_and_new_job_events():
    import runpy
    from pathlib import Path

    path = Path(__file__).resolve().parents[2] / 'infra/dev/diagnose-tutor-cmp.py'
    diagnostic = runpy.run_path(str(path))
    job = '2ea6996c-f323-40b7-a722-ece6921d9c28'
    records = [
        {'event': 'foundation_job_failed', 'http_status': '503', 'error_id': '1' * 32},
        {'event': 'foundation_job_ai_call', 'http_status': 503, 'upstream_error_id': '2' * 32},
        {'event': 'foundation_job', 'stage': 'failure_persisted', 'job_status': 'retry_wait'},
    ]
    logs = '\n'.join('2026-11-18T03:58:17Z runtime_event ' + json.dumps({
        'service': 'backend', 'job_id': job, 'provider_message': 'private_telegram_message', **record,
    }) for record in records)
    rows = diagnostic['safe_events'](logs, job)
    assert [r['event'] for r in rows] == [r['event'] for r in records]
    assert [r['http_status'] for r in rows[:2]] == ['503', 503]
    assert rows[1]['upstream_error_id'] == '2' * 32 and rows[2]['job_status'] == 'retry_wait'
    assert 'private_telegram_message' not in json.dumps(rows)
