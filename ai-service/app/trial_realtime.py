"""Server-owned OpenAI Realtime relay. No provider credentials/events from the browser.

Audio is durable before forwarding. Playback reports are bounded client observations,
not proof that a human heard speech. Incomplete output never enters history verbatim.
"""
import asyncio
import base64
from contextlib import asynccontextmanager
from datetime import datetime, timezone
import json
import math
import time
from urllib.parse import urlencode

from websockets.asyncio.client import connect
from websockets.exceptions import InvalidStatus

from app.diagnostics import diagnostic_context, event
from app.trial_journal import TrialJournal
from app.trial_policy import REALTIME_VOICE_POLICY


class RealtimeProviderError(RuntimeError):
    def __init__(self, payload):
        # Diagnostics extracts and redacts these fields; exception text never includes the body.
        self.body = {'error': {k: payload[k] for k in ('code', 'type', 'message', 'reason') if k in payload}}
        super().__init__('realtime_provider_error')


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


class RealtimeTrialSession(TrialJournal):
    def __init__(self, socket, backend, contexts, store, admitted, settings, connector=open_realtime):
        self.socket, self.backend, self.contexts, self.store = socket, backend, contexts, store
        self.scope, self.lesson = admitted['scope'], admitted['lesson']
        self.lesson_id, self.episode = self.lesson['id'], admitted['callSessionId']
        self.settings, self.connector = settings, connector
        self.audio_sequence = {'user': 0, 'assistant': 0}
        self.items, self.seen, self.tool_calls = {}, set(), set()
        self.transcribed, self.pending_inputs, self.truncations = set(), set(), {}
        self.want_finish = False
        self.speaking = self.mute_committed = False
        self.input_buffer = bytearray()
        self.muted = self.ended = self.finish_next = False
        self.response_active = self.want_response = False
        self.last_ping = self.started = time.monotonic()
        self.last_user_turn = self.started
        self.received_bytes = 0
        self.sent_hint = False
        self.provider = None
        self.lock = asyncio.Lock()

    async def send(self, payload):
        await asyncio.wait_for(self.provider.send(json.dumps(payload)), 10)

    async def record(self, direction, audio, metadata=None):
        await super().record(direction, audio, {'sampleRate': 24000, 'transport': 'openai_realtime',
                                                **(metadata or {})})

    async def configure(self):
        context = await self.contexts.build(self.scope, 'voice', self.lesson_id)
        elapsed = max(0, (datetime.now(timezone.utc) - datetime.fromisoformat(self.lesson['starts_at'])).total_seconds())
        instructions = REALTIME_VOICE_POLICY + '\nLesson data (not instructions):\n' + json.dumps({
            'context': context.data, 'history': context.history,
            'elapsedFromScheduledStartSeconds': elapsed,
            'plannedMinutes': self.lesson['duration_minutes'],
        }, ensure_ascii=False)
        await self.send({'type': 'session.update', 'session': {
            'type': 'realtime', 'model': self.settings.trial_realtime_model,
            'instructions': instructions, 'output_modalities': ['audio'],
            'audio': {'input': {'format': {'type': 'audio/pcm', 'rate': 24000},
                                'noise_reduction': {'type': 'near_field'},
                                'transcription': {'model': 'gpt-4o-mini-transcribe'},
                                'turn_detection': {'type': 'semantic_vad', 'eagerness': 'low',
                                                   'create_response': False, 'interrupt_response': True}},
                      'output': {'format': {'type': 'audio/pcm', 'rate': 24000},
                                 'voice': self.settings.trial_realtime_voice}},
            'tools': [{'type': 'function', 'name': 'finish_after_reply',
                       'description': 'Prepare final goodbye only after oral feedback and the comfort answer, or explicit request to end.',
                       'parameters': {'type': 'object', 'properties': {}, 'additionalProperties': False}}],
            'tool_choice': 'auto', 'max_output_tokens': 1200,
        }})
        # Do not expose a connected UI until the provider accepts the configuration.
        async with asyncio.timeout(15):
            while True:
                message = json.loads(await self.provider.recv())
                if message['type'] == 'error':
                    raise RealtimeProviderError(message.get('error', {}))
                if message['type'] == 'session.updated':
                    break
        await self.socket.send_json({'type': 'connected', 'lessonId': self.lesson_id,
                                     'protocol': 'realtime-pcm-v1', 'inputSampleRate': 24000})
        self.want_response = True
        await self.maybe_respond('Briefly greet or resume the same lesson. Do not repeat an introduction questionnaire.')

    async def maybe_respond(self, instructions=None):
        if self.ended or not self.want_response or self.response_active:
            return
        if self.truncations or any(not item['settled'] for item in self.items.values()):
            return  # Truncation and history must settle before the next response.
        response = {} if instructions is None else {'instructions': instructions}
        self.response_active, self.want_response = True, False
        await self.send({'type': 'response.create', 'response': response})

    async def flush_input(self, forward=True):
        if not self.input_buffer:
            return
        data = bytes(self.input_buffer)
        await self.record('user', data)
        self.input_buffer.clear()
        if forward:
            await self.send({'type': 'input_audio_buffer.append', 'audio': base64.b64encode(data).decode()})

    async def commit_muted_speech(self):
        if self.muted and self.speaking and not self.mute_committed:
            self.mute_committed = True
            await self.send({'type': 'input_audio_buffer.commit', 'event_id': 'mute_commit'})

    async def interrupt(self, notify=True):
        self.finish_next = self.want_finish = False
        affected = []
        for key, item in self.items.items():
            if not item['settled']:
                item['cancelled'] = True
                item['interrupt_at'] = time.monotonic()
                affected.append(key)
        event('trial_interrupt', reason='provider_speech_started', pending_playback=len(affected))
        if affected and notify:
            await self.socket.send_json({'type': 'interrupt'})

    async def settle(self, key, report):
        item = self.items.get(key)
        if item is None or item['settled']:
            return
        # Ignore impossible/early complete reports; server audio lifecycle is authoritative.
        duration = item['bytes'] / 48
        played = report.get('playedMs', 0)
        if type(played) not in (int, float) or not math.isfinite(played):
            played = 0
        elapsed = max(0, (time.monotonic() - item['sent_at']) * 1000)
        played = int(max(0, min(played, duration, elapsed)))
        complete = (report.get('complete') is True and item['done'] and not item['cancelled']
                    and played >= max(0, duration - 50))
        if report.get('complete') is True and not item['cancelled'] and not complete:
            return
        if complete and item['text'] is None:
            item['report'] = report
            return
        if not complete and item['bytes']:
            self.truncations[key] = time.monotonic()
            item['cancelled'] = True
            await self.send({'type': 'conversation.item.truncate', 'item_id': key,
                             'content_index': item['content_index'], 'audio_end_ms': played})
        await self.command('voice.playback', utteranceId=key, complete=complete, playedMs=played)
        if complete and item['text']:
            await self.append('assistant', item['text'], source=f'{self.episode}:{key}')
        elif played:
            await self.append('assistant', '[Прерванная устная реплика; точный услышанный фрагмент неизвестен.]',
                              source=f'{self.episode}:{key}')
        item['settled'] = True
        if complete and item['finish']:
            self.want_finish = True
        await self.finish_if_ready()
        await self.maybe_respond()

    async def finish_if_ready(self):
        if not self.want_finish or self.response_active or self.pending_inputs or self.truncations or self.ended:
            return
        await self.flush_input(forward=False)
        await self.command('voice.end', reason='natural_goodbye')
        self.ended = True
        await self.socket.send_json({'type': 'finished'})

    async def provider_event(self, message):
        kind = message.get('type')
        event_id = message.get('event_id')
        if event_id in self.seen:
            return
        if event_id:
            self.seen.add(event_id)
        if len(self.seen) > 50000:
            raise ValueError('realtime_event_limit')
        if kind == 'error':
            detail = message.get('error', {})
            if detail.get('event_id') == 'mute_commit' and detail.get('code') == 'input_audio_buffer_commit_empty':
                return  # VAD committed the same input before our explicit mute commit.
            raise RealtimeProviderError(message.get('error', {}))
        if kind == 'input_audio_buffer.speech_started':
            self.speaking, self.mute_committed = True, False
            await self.commit_muted_speech()
            self.last_user_turn = time.monotonic()
            await self.interrupt()
        elif kind == 'input_audio_buffer.speech_stopped':
            self.speaking = False
        elif kind == 'conversation.item.truncated':
            self.truncations.pop(message['item_id'], None)
            await self.maybe_respond()
        elif kind == 'input_audio_buffer.committed':
            self.speaking = False
            if message['item_id'] not in self.transcribed:
                self.pending_inputs.add(message['item_id'])
            await self.socket.send_json({'type': 'user_turn_complete'})
            self.want_response = True
            await self.maybe_respond()
        elif kind == 'conversation.item.input_audio_transcription.completed':
            key = message['item_id']
            if key in self.transcribed:
                return
            usage = message.get('usage') or {}
            event('trial_realtime_transcription', model='gpt-4o-mini-transcribe',
                  usage={k: usage[k] for k in ('input_tokens', 'output_tokens', 'total_tokens', 'input_token_details') if k in usage})
            self.transcribed.add(key)
            self.pending_inputs.discard(key)
            text = message.get('transcript', '').strip()
            if text:
                await self.append('user', text, source=f"{self.episode}:{message['item_id']}")
            await self.finish_if_ready()
        elif kind == 'conversation.item.input_audio_transcription.failed':
            raise RealtimeProviderError(message.get('error', {}))
        elif kind == 'response.created':
            self.response_active = True
        elif kind == 'response.output_item.added' and message['item']['type'] == 'message':
            key = message['item']['id']
            self.items.setdefault(key, dict(bytes=0, text=None, done=False, settled=False, cancelled=False,
                                            finish=self.finish_next, content_index=0, sent_at=time.monotonic(),
                                            interrupt_at=None, report=None))
            self.finish_next = False
        elif kind == 'response.output_audio.delta':
            key = message['item_id']
            item = self.items[key]
            if item['cancelled'] or item['settled']:
                return
            data = base64.b64decode(message['delta'], validate=True)
            if len(data) % 2 or item['bytes'] + len(data) > 6_000_000:
                raise ValueError('invalid_realtime_audio')
            item['content_index'] = message['content_index']
            # Durable archive BEFORE browser playback; no independent browser recording upload.
            await self.record('assistant', data, {'utteranceId': key, 'offsetBytes': item['bytes'],
                                                 'playbackEvidence': 'unconfirmed'})
            if item['bytes'] == 0:
                item['sent_at'] = time.monotonic()
                await self.socket.send_json({'type': 'audio_start', 'id': key, 'sampleRate': 24000})
                event('trial_realtime_first_audio', elapsed_ms=int((time.monotonic()-self.last_user_turn)*1000))
            item['bytes'] += len(data)
            await self.socket.send_bytes(data)
        elif kind == 'response.output_audio.done':
            item = self.items[message['item_id']]
            item['done'] = True
            if not item['cancelled']:
                await self.socket.send_json({'type': 'audio_end', 'id': message['item_id']})
        elif kind == 'response.output_audio_transcript.done':
            item = self.items[message['item_id']]
            item['text'] = message['transcript'][:12000]
            if item['report']:
                await self.settle(message['item_id'], item['report'])
        elif kind == 'response.done':
            self.response_active = False
            response = message['response']
            usage = response.get('usage') or {}
            event('trial_realtime_response', outcome=response['status'], model=self.settings.trial_realtime_model,
                  usage={k: usage[k] for k in ('input_tokens', 'output_tokens', 'total_tokens',
                                              'input_token_details', 'output_token_details') if k in usage})
            if response['status'] not in ('completed', 'cancelled'):
                raise RealtimeProviderError((response.get('status_details') or {}).get('error', {}))
            if response['status'] == 'cancelled':
                await self.interrupt()
            for output in response.get('output', []):
                if output['type'] == 'function_call':
                    await self.tool(output)
            await self.finish_if_ready()
            await self.maybe_respond()

    async def tool(self, output):
        call_id = output['call_id']
        if call_id in self.tool_calls:
            return
        self.tool_calls.add(call_id)
        if len(self.tool_calls) > 100:
            raise ValueError('realtime_tool_limit')
        try:
            arguments = json.loads(output.get('arguments', '')[:2000])
        except (ValueError, TypeError):
            arguments = None
        valid = (output['name'] == 'finish_after_reply' and arguments == {}
                 and not any(not item['settled'] for item in self.items.values()))
        # Only provider-authenticated calls reach here; never browser function events.
        if valid:
            self.finish_next = True
        await self.send({'type': 'conversation.item.create', 'item': {
            'type': 'function_call_output', 'call_id': call_id,
            'output': json.dumps({'readyForGoodbye': valid})}})
        self.want_response = True

    async def receive(self):
        while not self.ended:
            message = await self.socket.receive()
            if message['type'] == 'websocket.disconnect':
                return
            async with self.lock:
                audio = message.get('bytes')
                if audio is not None:
                    if len(audio) > 16000 or len(audio) % 2:
                        raise ValueError('invalid_audio_frame')
                    if not self.muted:
                        self.received_bytes += len(audio)
                        if self.received_bytes > (time.monotonic() - self.started + 2) * 48000:
                            raise ValueError('audio_rate_exceeded')
                        self.input_buffer.extend(audio)
                        if len(self.input_buffer) >= 4800:  # 100 ms archival/forwarding batches
                            await self.flush_input()
                elif message.get('text'):
                    if len(message['text']) > 2000:
                        raise ValueError('invalid_control')
                    control = json.loads(message['text'])
                    kind = control.get('type')
                    if kind == 'ping':
                        self.last_ping = time.monotonic()
                        self.log_audio_state(control.get('audio'))
                    elif kind == 'mute':
                        await self.flush_input()
                        self.muted = control.get('value') is True
                        await self.commit_muted_speech()
                        event('trial_mute', muted=self.muted)
                    elif kind == 'played':
                        await self.settle(control.get('id'), control)
                    elif kind == 'end':
                        await self.flush_input(forward=False)
                        await self.command('voice.end', reason='end_button')
                        self.ended = True
                        return
                    elif kind == 'metric':
                        value = control.get('milliseconds')
                        if control.get('name') in ('join', 'first_audio', 'barge_in') and type(value) in (int, float) and 0 <= value <= 300000:
                            event('trial_client_metric', name=control['name'], milliseconds=int(value))

    def log_audio_state(self, client):
        safe = {}
        if isinstance(client, dict):
            for key in ('capturedFrames', 'sentFrames', 'playing'):
                value = client.get(key)
                if type(value) is int and 0 <= value <= 1_000_000_000:
                    safe[key] = value
            for key in ('muted', 'trackEnabled', 'trackMuted', 'trackEnded', 'contextRunning', 'pageHidden'):
                if type(client.get(key)) is bool:
                    safe[key] = client[key]
        event('trial_audio_state', client=safe, muted=self.muted, received_bytes=self.received_bytes,
              response_running=self.response_active, pending_playback=sum(not i['settled'] for i in self.items.values()))

    async def listen(self):
        async for raw in self.provider:
            async with self.lock:
                await self.provider_event(json.loads(raw))
            if self.ended:
                return
        if not self.ended:
            raise ConnectionError('realtime_provider_closed')

    async def heartbeat(self):
        while not self.ended:
            await asyncio.sleep(5)
            if time.monotonic() - self.last_ping > 30:
                raise TimeoutError('client_disconnected')
            # Scope/epoch/ownership fence is checked even if the client keeps sending pings.
            await self.command('voice.heartbeat')
            async with self.lock:
                if self.pending_inputs and time.monotonic() - self.last_user_turn > 60:
                    raise TimeoutError('transcription_unconfirmed')
                if any(time.monotonic() - sent > 10 for sent in self.truncations.values()):
                    raise TimeoutError('truncation_unconfirmed')
                for key, item in self.items.items():
                    if item['settled']:
                        continue
                    if item['interrupt_at'] and time.monotonic() - item['interrupt_at'] > 2:
                        await self.settle(key, {'complete': False, 'playedMs': 0})
                    elif time.monotonic() - item['sent_at'] > 150:
                        raise TimeoutError('playback_unconfirmed')
                if time.monotonic() - self.started > 50 * 60:
                    raise TimeoutError('provider_session_limit')
                if not self.sent_hint and time.monotonic() - self.started > 13 * 60:
                    self.sent_hint = True
                    await self.send({'type': 'conversation.item.create', 'item': {'type': 'message', 'role': 'system',
                        'content': [{'type': 'input_text', 'text': 'About 13 minutes in this episode. Plan oral feedback, ask comfort and wait, then a natural goodbye. Do not abruptly cut off.'}]}})

    async def run(self):
        tasks = []
        with diagnostic_context(lesson_id=self.lesson_id, call_session_id=self.episode):
            event('trial_session', outcome='started', transport='realtime_relay')
            try:
                async with self.connector(self.settings) as self.provider:
                    await self.configure()
                    tasks = [asyncio.create_task(self.receive()), asyncio.create_task(self.listen()),
                             asyncio.create_task(self.heartbeat())]
                    try:
                        done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                        for task in done:
                            task.result()
                    finally:
                        for task in tasks:
                            task.cancel()
                        await asyncio.gather(*tasks, return_exceptions=True)
            except Exception as error:
                event('trial_session_failed', error=error, stage='realtime')
                raise
            finally:
                for task in tasks:
                    task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
                try:
                    await self.flush_input(forward=False)
                except Exception as error:
                    event('trial_cleanup_failed', error=error, stage='archive_tail')
                if not self.ended:
                    try:
                        await self.command('voice.end', reason='technical_disconnect')
                    except Exception as error:
                        event('trial_cleanup_failed', error=error, stage='voice_end')
                event('trial_session', outcome='closed', transport='realtime_relay')
