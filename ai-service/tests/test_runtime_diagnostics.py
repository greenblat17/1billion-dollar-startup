import asyncio
import json
import logging
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx
import openai
import pytest

from app.diagnostics import context_fields, diagnostic_context, error_fields, event
from app.foundation_client import FoundationClient
from app.trial_providers import KoraTrialTTS


def records(caplog):
    return [json.loads(r.message.removeprefix('runtime_event ')) for r in caplog.records
            if r.message.startswith('runtime_event ')]


def denied():
    response = httpx.Response(402, request=httpx.Request('POST', 'https://openrouter.ai/api/v1/audio/transcriptions'),
                             headers={'x-request-id': 'provider-request', 'retry-after': '10'})
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
    assert result['provider_request_id'] == 'provider-request'
    assert result['retry_after'] == '10'
    assert 'Credits exhausted' in result['provider_error']['message']
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
    assert failed['operation_id'] == 'operation'
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


def test_diagnostic_export_failure_does_not_raise(monkeypatch):
    monkeypatch.setattr(logging.Logger, 'log', lambda *a, **k: (_ for _ in ()).throw(RuntimeError('sink down')))
    assert event('failed', error=ValueError('safe_code'), stage='test')


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
        response = client.post('/miniapp/diagnostics', json={'initData': 'SECRET_INIT', 'attemptId': 'attempt',
                               'stage': 'microphone', 'code': 'PRIVATE_WORDS'})
        assert response.status_code == 200
        assert records(caplog)[-1]['client_code'] == 'unknown'
        assert records(caplog)[-1]['trace_id'] == 'attempt'
        backend._request.side_effect = BackendFailure('invalid_telegram_auth', 403)
        assert client.post('/miniapp/diagnostics', json={'initData': 'bad'}).status_code == 403
    assert 'PRIVATE_WORDS' not in caplog.text and 'SECRET_INIT' not in caplog.text
