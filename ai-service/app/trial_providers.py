"""Call transport adapters around the existing STT and Kora TTS providers."""
import asyncio
from collections import deque
from contextlib import suppress
import io
import time
import wave

import webrtcvad


class GroqTrialSTT:
    """Segment PCM locally; use the existing file-based SpeechToText adapter unchanged.

    VAD only detects speech activity and bounds uploads. The dialogue model decides
    whether a pause means a completed thought. No new provider or credentials.
    """
    FRAME_BYTES = 640  # 20 ms, mono PCM16, 16 kHz
    START_FRAMES = 8  # Reject isolated bursts; not a pedagogical pause rule.
    SILENCE_FRAMES = 40
    MAX_FRAMES = 750

    def __init__(self, speech, detector=None):
        self.speech = speech
        self.detector = detector if detector is not None else webrtcvad.Vad(2)
        self.pending = bytearray()
        self.pre_roll = deque(maxlen=15)
        self.segment = bytearray()
        self.voiced = 0
        self.silent = 0
        self.speaking = False
        self.last_input = time.monotonic()
        self.clips = asyncio.Queue(maxsize=4)
        self.results = asyncio.Queue(maxsize=64)
        self.worker = None
        self.pending_transcriptions = 0

    async def __aenter__(self):
        self.worker = asyncio.create_task(self.transcribe())
        return self

    async def __aexit__(self, *args):
        self.worker.cancel()
        with suppress(asyncio.CancelledError):
            await self.worker

    def emit_clip(self, endpoint):
        if self.segment or (endpoint and self.speaking):
            self.clips.put_nowait((bytes(self.segment), endpoint))
            self.pending_transcriptions += 1
            self.segment.clear()
        if endpoint:
            self.speaking = False
            self.voiced = self.silent = 0
            self.pre_roll.clear()

    async def send(self, pcm):
        self.last_input = time.monotonic()
        self.pending.extend(pcm)
        while len(self.pending) >= self.FRAME_BYTES:
            frame = bytes(self.pending[:self.FRAME_BYTES])
            del self.pending[:self.FRAME_BYTES]
            voiced = self.detector.is_speech(frame, 16000)
            if not self.speaking:
                self.pre_roll.append(frame)
                self.voiced = self.voiced + 1 if voiced else 0
                if self.voiced < self.START_FRAMES:
                    continue
                self.speaking = True
                self.segment.extend(b''.join(self.pre_roll))
                self.pre_roll.clear()
                self.results.put_nowait({'speech_start': True})
            else:
                self.segment.extend(frame)
            self.silent = 0 if voiced else self.silent + 1
            if self.silent >= self.SILENCE_FRAMES:
                self.emit_clip(True)
            elif len(self.segment) >= self.MAX_FRAMES * self.FRAME_BYTES:
                self.emit_clip(False)

    async def keepalive(self):
        # Muting stops PCM frames. Finish the already captured phrase, not the lesson.
        if self.speaking and time.monotonic() - self.last_input >= .8:
            self.emit_clip(True)
        if self.worker.done():
            self.worker.result()

    async def transcribe(self):
        try:
            while True:
                pcm, endpoint = await self.clips.get()
                try:
                    if not pcm:
                        self.results.put_nowait(dict(text='', final=True, endpoint=endpoint, confidence=1.0))
                        continue
                    wav = io.BytesIO()
                    with wave.open(wav, 'wb') as output:
                        output.setnchannels(1)
                        output.setsampwidth(2)
                        output.setframerate(16000)
                        output.writeframes(pcm)
                    result = await asyncio.wait_for(
                        self.speech.transcribe(wav.getvalue(), 'audio/wav', 'trial.wav', language=None), 30)
                    self.results.put_nowait(dict(text='' if result.no_speech else result.text,
                                               final=True, endpoint=endpoint, confidence=1.0))
                finally:
                    self.pending_transcriptions -= 1
                    self.clips.task_done()
        except asyncio.CancelledError:
            raise
        except Exception as error:
            self.results.put_nowait(error)

    async def events(self):
        while True:
            event = await self.results.get()
            if isinstance(event, Exception):
                raise event
            yield event


class KoraTrialTTS:
    def __init__(self, client, model, voice):
        self.client, self.model, self.voice = client, model, voice

    async def stream(self, text):
        # Preserve the selected Kora model/voice. No fallback to a different speaker.
        async with self.client.audio.speech.with_streaming_response.create(
            model=self.model, voice=self.voice, input=text, response_format='pcm',
        ) as response:
            async for chunk in response.iter_bytes(chunk_size=4800):
                yield chunk
