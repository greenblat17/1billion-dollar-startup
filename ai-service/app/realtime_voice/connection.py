"""Provider WebSocket setup and lifetime. Lesson decisions belong to the session."""
import asyncio
from contextlib import asynccontextmanager
import json
from urllib.parse import urlencode

from websockets.asyncio.client import connect
from websockets.exceptions import InvalidStatus

from app.diagnostics import diagnostic_context, event, provider_code

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
        event('trial_session', outcome='started', transport='realtime_relay')
        try:
            async with connector(settings) as provider:
                await configure(provider)
                tasks = [asyncio.create_task(worker) for worker in workers()]
                try:
                    done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                    for task in done:
                        task.result()
                finally:
                    for task in tasks:
                        task.cancel()
                    await asyncio.gather(*tasks, return_exceptions=True)
        except RealtimeProviderError as error:
            event('trial_session_failed', error=error, stage='provider', provider='openai',
                  operation='realtime', reason_code='realtime_provider_error')
            raise
        except Exception as error:
            event('trial_session_failed', error=error, stage='realtime')
            raise
        finally:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            await cleanup()



def response_ready(*, pending_inputs, has_action, sp24, v55, trial_phase, learner_spoke, playback_pending):
    needs_trial_transcript = v55 and (trial_phase == 'wrapup' or (trial_phase == 'warmup' and not learner_spoke))
    return not playback_pending and not (pending_inputs and (has_action or sp24 or needs_trial_transcript))
