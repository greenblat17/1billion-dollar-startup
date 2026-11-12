"""One connected episode. Foundation owns lesson lifetime; disconnect releases provider resources."""
import asyncio
from contextlib import suppress
from datetime import datetime, timezone
import json
import logging
import re
import time
from uuid import uuid4

from app.trial_policy import VOICE_POLICY
from app.diagnostics import diagnostic_context, event as log_event


def timestamp():
    return datetime.now(timezone.utc).isoformat()


class TrialSession:
    def __init__(self, socket, backend, contexts, model, stt, tts, audio_store, admitted):
        self.socket, self.backend, self.contexts, self.model = socket, backend, contexts, model
        self.stt, self.tts, self.store = stt, tts, audio_store
        self.scope = admitted['scope']
        self.lesson = admitted['lesson']
        self.lesson_id, self.episode = self.lesson['id'], admitted['callSessionId']
        self.pending = {}
        self.audio_sequence = {'user': 0, 'assistant': 0}
        self.user_audio = bytearray()
        self.transcript = []
        self.turn_task = None
        self.muted = False
        self.ended = False
        self.last_ping = time.monotonic()
        self.last_audio = time.monotonic()
        self.last_speech = 0.0
        self.generation = 0
        self.user_archive = asyncio.Queue(maxsize=12)

    async def command(self, action, **args):
        return await self.backend.command(self.scope, uuid4().hex, action,
                                          dict(lessonId=self.lesson_id, callSessionId=self.episode, **args))

    async def record(self, direction, audio, metadata=None):
        if not audio:
            return
        sequence = self.audio_sequence[direction]
        self.audio_sequence[direction] += 1
        receipt = await self.command('voice.audio', direction=direction, sequence=sequence,
                                     metadata={'sampleRate': 16000 if direction == 'user' else 24000, 'encoding': 'pcm_s16le', **(metadata or {})})
        key = receipt['objectKey']
        try:
            await asyncio.wait_for(self.store.put(key, bytes(audio)), 30)
            await self.command('voice.audio_stored', objectKey=key)
        except BaseException as error:
            log_event('audio_archive_failed', error=error, stage='store', direction=direction)
            try:
                await self.store.delete(key)
            except Exception as cleanup_error:
                log_event('audio_archive_failed', error=cleanup_error, stage='rollback')
            raise

    async def archive_input(self):
        while True:
            data = await self.user_archive.get()
            try:
                await self.record('user', data)
            finally:
                self.user_archive.task_done()

    async def flush_input(self):
        await asyncio.wait_for(self.user_archive.join(), 30)
        if self.user_audio:
            await self.record('user', self.user_audio)
            self.user_audio.clear()

    async def append(self, role, text, source=None):
        return await self.command('voice.append', sourceId=source or uuid4().hex, role=role,
                                  body=text[:12000], occurredAt=timestamp())

    async def interrupt(self):
        self.generation += 1
        # Stop browser playback BEFORE waiting for TTS cancellation or archive I/O.
        for item in self.pending.values():
            item['cancelled'] = True
        await self.socket.send_json({'type': 'interrupt'})
        if self.turn_task and not self.turn_task.done():
            self.turn_task.cancel()
            with suppress(asyncio.CancelledError):
                await self.turn_task

    def start_turn(self, cue):
        self.turn_task = asyncio.create_task(self.respond(cue, self.generation))

    async def respond(self, cue, generation):
        with diagnostic_context(turn_id=uuid4().hex):
            await self._respond(cue, generation)

    async def _respond(self, cue, generation):
        started = time.monotonic()
        first_audio = True
        stage = 'context'
        try:
            context = await self.contexts.build(self.scope, 'voice', self.lesson_id)
            now = datetime.now(timezone.utc)
            end = datetime.fromisoformat(self.lesson['starts_at'])
            elapsed = (now - end).total_seconds()
            data = dict(context=context.data, history=context.history, cue=cue,
                        elapsedFromScheduledStartSeconds=elapsed,
                        plannedMinutes=self.lesson['duration_minutes'])
            stage = 'llm'
            raw = await self.model.complete_json(VOICE_POLICY, json.dumps(data, ensure_ascii=False),
                                                  temperature=0.6, operation='trial_voice', retry=False)
            stage = 'voice_validation'
            result = json.loads(raw)
            if not isinstance(result.get('reply'), str) or type(result.get('finish')) is not bool or type(result.get('wait')) is not bool:
                raise ValueError('invalid_voice_reply')
            if result['wait'] or generation != self.generation:
                return
            reply = result['reply'].strip()
            if not reply or len(reply) > 3000:
                raise ValueError('invalid_voice_reply')
            # Short sentences let playback acknowledgements identify actually heard speech.
            sentences = re.split(r'(?<=[.!?])\s+', reply)
            for index, sentence in enumerate(sentences):
                if generation != self.generation:
                    return
                audio_id = uuid4().hex
                item = dict(text=sentence, finish=result['finish'] and index == len(sentences) - 1,
                            cancelled=False, delivered=False)
                self.pending[audio_id] = item
                stage = 'audio_start'
                await self.socket.send_json({'type': 'audio_start', 'id': audio_id, 'sampleRate': 24000})
                collected = bytearray()
                try:
                    stage = 'tts'
                    async for chunk in self.tts.stream(sentence):
                        if generation != self.generation:
                            return
                        if first_audio:
                            logging.getLogger(__name__).info('trial_metric first_audio_ms=%.0f', (time.monotonic() - started) * 1000)
                            first_audio = False
                        collected.extend(chunk)
                        if len(collected) > 6_000_000:
                            raise ValueError('audio_too_large')
                        stage = 'socket_send'
                        await self.socket.send_bytes(chunk)
                        stage = 'tts'
                except Exception as error:
                    log_event('trial_audio_stream_failed', error=error, stage=stage)
                    raise
                finally:
                    if collected:
                        stage = 'archive_assistant'
                        await self.record('assistant', collected, {'utteranceId': audio_id, 'generatedText': sentence, 'generation': generation})
                await self.socket.send_json({'type': 'audio_end', 'id': audio_id})
        except asyncio.CancelledError:
            raise
        except Exception as error:
            error_id = log_event('trial_response_failed', error=error, stage=stage)
            await self.socket.send_json({'type': 'error', 'code': 'voice_unavailable', 'errorId': error_id})

    async def acknowledged(self, message):
        item = self.pending.pop(message.get('id'), None)
        if not item:
            return
        played_ms = message.get('playedMs', 0)
        if not isinstance(played_ms, (int, float)) or not 0 <= played_ms <= 300000:
            played_ms = 0
        await self.command('voice.playback', utteranceId=message['id'],
                           complete=message.get('complete') is True and not item['cancelled'], playedMs=played_ms)
        if message.get('complete') is True and not item['cancelled']:
            await self.append('assistant', item['text'])
            if item['finish']:
                await self.flush_input()
                await self.command('voice.end', reason='natural_goodbye')
                self.ended = True
                await self.socket.send_json({'type': 'finished'})
                await self.socket.close()
        elif played_ms > 0:
            # Do not claim the whole generated sentence was heard.
            await self.append('assistant', '[Прерванная устная реплика; точный услышанный фрагмент неизвестен.]')
        if len(self.pending) > 100:
            raise ValueError('unacknowledged_audio')

    async def receive(self):
        while not self.ended:
            message = await self.socket.receive()
            if message['type'] == 'websocket.disconnect':
                log_event('trial_disconnected', stage='receive', close_code=message.get('code'))
                return
            audio = message.get('bytes')
            if audio is not None:
                if len(audio) > 16000 or len(audio) % 2:
                    raise ValueError('invalid_audio_frame')
                if self.muted:
                    continue
                self.last_audio = time.monotonic()
                self.user_audio.extend(audio)
                await self.stt.send(audio)
                if len(self.user_audio) >= 16000 * 2 * 5:
                    data, self.user_audio = self.user_audio, bytearray()
                    self.user_archive.put_nowait(bytes(data))
            elif message.get('text'):
                if len(message['text']) > 2000:
                    raise ValueError('invalid_control')
                control = json.loads(message['text'])
                kind = control.get('type')
                if kind == 'ping':
                    self.last_ping = time.monotonic()
                elif kind == 'metric':
                    value = control.get('milliseconds')
                    if control.get('name') in ('join', 'barge_in') and isinstance(value, (int, float)) and 0 <= value <= 300000:
                        logging.getLogger(__name__).info('trial_client_metric name=%s milliseconds=%.0f', control['name'], value)
                elif kind == 'mute':
                    self.muted = control.get('value') is True
                elif kind == 'played':
                    await self.acknowledged(control)
                elif kind == 'end':
                    await self.interrupt()
                    await self.flush_input()
                    await self.command('voice.end', reason='end_button')
                    self.ended = True
                    return

    async def recognize(self):
        async for event in self.stt.events():
            if event.get('speech_start'):
                self.last_speech = time.monotonic()
                await self.interrupt()
                continue
            text = event['text'].strip()
            if text and event['confidence'] >= 0.5:
                self.last_speech = time.monotonic()
                if any(not item['cancelled'] for item in self.pending.values()) or (self.turn_task and not self.turn_task.done()):
                    await self.interrupt()
                if event['final']:
                    self.transcript.append(text)
            if event['endpoint'] and self.transcript and not getattr(self.stt, 'speaking', False) and not getattr(self.stt, 'pending_transcriptions', 0):
                text = ' '.join(self.transcript)
                self.transcript.clear()
                await self.append('user', text)
                self.start_turn('The learner paused. Decide whether the thought is complete or they need thinking time. Do not rush.')

    async def heartbeat(self):
        silent_checked = 0.0
        while not self.ended:
            await asyncio.sleep(5)
            now = time.monotonic()
            if now - self.last_ping > 30:
                raise TimeoutError('client_disconnected')
            await self.command('voice.heartbeat')
            await self.stt.keepalive()
            if self.turn_task and self.turn_task.done() and not self.turn_task.cancelled():
                self.turn_task.result()
            if (self.last_speech > silent_checked and now - self.last_speech > 8 and not self.muted
                    and not self.pending and not getattr(self.stt, 'speaking', False)
                    and not getattr(self.stt, 'pending_transcriptions', 0)
                    and (not self.turn_task or self.turn_task.done())):
                silent_checked = self.last_speech
                self.start_turn('There is continued silence after the last learner words. Decide whether to keep waiting or gently help; never use a fixed time rule.')

    async def run(self):
        with diagnostic_context(lesson_id=self.lesson_id, call_session_id=self.episode):
            log_event('trial_session', outcome='started')
            try:
                await self._run()
            except Exception as error:
                log_event('trial_session_failed', error=error, stage='run')
                raise
            finally:
                log_event('trial_session', outcome='closed')

    async def _run(self):
        tasks = []
        try:
            async with self.stt:
                await self.socket.send_json({'type': 'connected', 'lessonId': self.lesson_id})
                self.start_turn('Connection established. Briefly greet or resume the same lesson, using previous episodes. No repeated introduction questionnaire.')
                tasks = [asyncio.create_task(self.receive()), asyncio.create_task(self.recognize()), asyncio.create_task(self.heartbeat()), asyncio.create_task(self.archive_input())]
                done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                for task in done:
                    task.result()
        finally:
            try:
                await asyncio.wait_for(self.user_archive.join(), 5)
            except Exception as error:
                log_event('trial_cleanup_failed', error=error, stage='archive_drain')
            for task in tasks:
                task.cancel()
            results = await asyncio.gather(*tasks, return_exceptions=True)
            for result in results:
                if isinstance(result, Exception):
                    log_event('trial_task_failed', error=result, stage='shutdown')
            if self.turn_task:
                self.turn_task.cancel()
                turn_results = await asyncio.gather(self.turn_task, return_exceptions=True)
                for result in turn_results:
                    if isinstance(result, Exception):
                        log_event('trial_task_failed', error=result, stage='turn_shutdown')
            if self.user_audio:
                try:
                    await self.record('user', self.user_audio)
                except Exception as error:
                    log_event('trial_cleanup_failed', error=error, stage='archive_tail')
            if not self.ended:
                try:
                    await self.command('voice.end', reason='technical_disconnect')
                except Exception as error:
                    log_event('trial_cleanup_failed', error=error, stage='voice_end')
