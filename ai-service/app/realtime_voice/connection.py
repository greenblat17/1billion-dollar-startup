"""Provider WebSocket setup and lifetime. Lesson decisions belong to the session."""
import asyncio
from contextlib import asynccontextmanager
import json
import logging
from time import perf_counter
from urllib.parse import urlencode

from websockets.asyncio.client import connect
from websockets.exceptions import InvalidStatus

from app.diagnostics import EventSpec, diagnostic_context, event, provider_code


_SESSION_STARTED = EventSpec('trial_session', 'realtime_voice', 'trial.voice.session', 'connect', 'started',
                             'The AI service started opening a realtime voice session.',
                             fields=frozenset({'transport'}))
_SESSION_CONNECTED = EventSpec('trial_session', 'realtime_voice', 'trial.voice.session', 'configure', 'completed',
                               'The realtime voice provider connection was configured.',
                               fields=frozenset({'transport', 'duration_ms'}))
_SESSION_FAILURES = {
    'client_disconnected': 'The client heartbeat expired.',
    'trial_stage_update_unconfirmed': 'The provider did not confirm the lesson stage update.',
    'transcription_unconfirmed': 'The provider did not confirm the transcription in time.',
    'truncation_unconfirmed': 'The provider did not confirm the audio truncation in time.',
    'playback_unconfirmed': 'The client did not confirm audio playback in time.',
    'provider_session_limit': 'The voice session reached its provider duration limit.',
    'realtime_provider_closed': 'The voice provider closed the connection before the session ended.',
}

class RealtimeProviderError(RuntimeError):
    def __init__(self, payload):
        # Diagnostics extracts and redacts these fields; exception text never includes the body.
        self.body = {'error': {k: payload[k] for k in ('code', 'type', 'param', 'reason') if k in payload}}
        super().__init__('realtime_provider_error')


def safe_response_status(response):
    """Keep provider status metadata, never response text or audio, in diagnostics."""
    details = response.get('status_details')
    details = details if isinstance(details, dict) else {}
    reason = details.get('reason')
    reason = reason if reason in ('turn_detected', 'client_cancelled', 'max_output_tokens', 'content_filter') else None
    error = details.get('error')
    error = error if isinstance(error, dict) else {}
    code = error.get('code')
    code = provider_code(code)
    usage = response.get('usage')
    usage = usage if isinstance(usage, dict) else {}
    output_tokens = usage.get('output_tokens')
    output_tokens = output_tokens if type(output_tokens) is int and 0 <= output_tokens <= 1_000_000_000 else None
    return {'status_reason': reason, 'status_error_code': code, 'output_tokens': output_tokens}


@asynccontextmanager
async def open_realtime(settings):
    # Deliberately independent of OPENAI_BASE_URL (often OpenRouter for text).
    try:
        async with connect('wss://api.openai.com/v1/realtime?' + urlencode({'model': settings.trial_realtime_model}),
                           additional_headers={'Authorization': 'Bearer ' + settings.openai_realtime_api_key},
                           open_timeout=15, close_timeout=5, max_size=2**20, max_queue=16) as socket:
            yield socket
    except InvalidStatus as error:
        response = error.response
        try:
            body = json.loads(response.body[:8192])
            detail = body.get('error', {}) if isinstance(body, dict) else {}
        except (ValueError, TypeError):
            detail = {}
        failure = RealtimeProviderError(detail if isinstance(detail, dict) else {})
        failure.status_code = response.status_code
        failure.request_id = response.headers.get('x-request-id')
        raise failure from None  # No retry, including 402/429.


async def send_provider(provider, payload):
    await asyncio.wait_for(provider.send(json.dumps(payload)), 10)


async def listen_provider(provider, lock, handle_event, is_ended):
    async for raw in provider:
        async with lock:
            await handle_event(json.loads(raw))
        if is_ended():
            return
    if not is_ended():
        raise ConnectionError('realtime_provider_closed')


async def run_connection(connector, settings, configure, workers, cleanup, *, lesson_id, episode):
    tasks = []
    with diagnostic_context(lesson_id=lesson_id, call_session_id=episode):
        started = perf_counter()
        stage = 'connect'
        event(_SESSION_STARTED, transport='realtime_relay')
        try:
            async with connector(settings) as provider:
                stage = 'configure'
                await configure(provider)
                event(_SESSION_CONNECTED, transport='realtime_relay', duration_ms=int((perf_counter() - started) * 1000))
                stage = 'realtime'
                tasks = [asyncio.create_task(worker) for worker in workers()]
                try:
                    done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                    for task in done:
                        task.result()
                finally:
                    for task in tasks:
                        task.cancel()
                    await asyncio.gather(*tasks, return_exceptions=True)
        except asyncio.CancelledError:
            event(EventSpec('trial_session', 'realtime_voice', 'trial.voice.session', stage, 'cancelled',
                            'The AI service cancelled the realtime voice session.'))
            raise
        except RealtimeProviderError as error:
            event(EventSpec('trial_session_failed', 'realtime_voice', 'realtime', 'provider', 'failed',
                            'The realtime voice provider returned an error.', 'realtime_provider_error',
                            logging.WARNING, frozenset({'provider', 'duration_ms'})),
                  error=error, provider='openai', duration_ms=int((perf_counter() - started) * 1000))
            raise
        except Exception as error:
            reason = str(error) if isinstance(error, (TimeoutError, ConnectionError)) else None
            message = _SESSION_FAILURES.get(reason)
            event(EventSpec('trial_session_failed', 'realtime_voice', 'trial.voice.session', stage, 'failed',
                            message or 'The realtime voice session failed unexpectedly.',
                            reason if message else 'realtime_session_failed',
                            logging.WARNING if message else logging.ERROR, frozenset({'duration_ms'})),
                  error=error, duration_ms=int((perf_counter() - started) * 1000))
            raise
        finally:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            await cleanup()



def response_ready(*, pending_inputs, has_action, sp24, v55, trial_phase, learner_spoke, playback_pending):
    needs_trial_transcript = v55 and (trial_phase == 'wrapup' or (trial_phase == 'warmup' and not learner_spoke))
    return not playback_pending and not (pending_inputs and (has_action or sp24 or needs_trial_transcript))
