"""PCM buffering, durable archive and browser delivery for the Realtime call."""
import base64
import math

from app.diagnostics import event, provider_id


def decode_assistant_audio(delta, stored_bytes):
    data = base64.b64decode(delta, validate=True)
    if len(data) % 2 or stored_bytes + len(data) > 6_000_000:
        raise ValueError('invalid_realtime_audio')
    return data


def bounded_playback(item, report, clock):
    # Ignore impossible/early complete reports; server audio lifecycle is authoritative.
    duration = item['bytes'] / 48
    played = report.get('playedMs', 0)
    if type(played) not in (int, float) or not math.isfinite(played):
        played = 0
    elapsed = max(0, (clock() - item['sent_at']) * 1000)
    played = int(max(0, min(played, duration, elapsed)))
    complete = (report.get('complete') is True and item['done'] and not item['cancelled']
                and played >= max(0, duration - 50))
    if report.get('complete') is True and not item['cancelled'] and not complete:
        return None
    return played, complete


class RealtimeAudio:
    def __init__(self, archive, send, browser, clock):
        self.archive = archive
        self.send = send
        self.socket = browser
        self.clock = clock
        self.input_buffer = bytearray()
        self.input_audio_ms = 0
        self.archive_timings = {}
        self.received_bytes = 0
        self.forwarded_input_bytes = 0
        self.forwarded_silence_bytes = 0
        self.last_input_forwarded_at = None

    async def receive_input(self, audio, *, muted, started, v55):
        if len(audio) > 16000 or len(audio) % 2:
            raise ValueError('invalid_audio_frame')
        if muted:
            return
        self.received_bytes += len(audio)
        if self.received_bytes > (self.clock() - started + 2) * 48000:
            raise ValueError('audio_rate_exceeded')
        self.input_buffer.extend(audio)
        if len(self.input_buffer) >= 4800:  # 100 ms archival/forwarding batches
            await self.flush_input(forward=True, v55=v55)

    def discard_input(self):
        self.input_buffer.clear()

    def take_archive_timings(self):
        result = {direction: {'count': stats['count'], 'mean_ms': round(stats['total_ms'] / stats['count']),
                             'max_ms': round(stats['max_ms'])}
                  for direction, stats in self.archive_timings.items()}
        self.archive_timings.clear()
        return result

    async def record(self, direction, audio, metadata=None, *, v55):
        started = self.clock()
        metadata = dict(metadata or {})
        if v55 and direction == 'user':
            metadata.update(audioStartMs=self.input_audio_ms, audioEndMs=self.input_audio_ms + len(audio) / 48,
                            audioOffsetBasis="archived_input_pcm")
            self.input_audio_ms += len(audio) / 48
        await self.archive(direction, audio, {'sampleRate': 24000, 'transport': 'openai_realtime', **metadata})
        duration = (self.clock() - started) * 1000
        stats = self.archive_timings.setdefault(direction, {'count': 0, 'total_ms': 0, 'max_ms': 0})
        stats['count'] += 1
        stats['total_ms'] += duration
        stats['max_ms'] = max(stats['max_ms'], duration)

    async def flush_input(self, *, forward, v55):
        if not self.input_buffer:
            return
        data = bytes(self.input_buffer)
        await self.record('user', data, {'forwardRequested': forward} if v55 else None, v55=v55)
        self.input_buffer.clear()
        if forward:
            await self.send({'type': 'input_audio_buffer.append', 'audio': base64.b64encode(data).decode()})
            self.forwarded_input_bytes += len(data)
            trailing_silence_bytes = (len(data) - len(data.rstrip(b'\0'))) & ~1
            if trailing_silence_bytes == len(data):
                self.forwarded_silence_bytes += trailing_silence_bytes
            else:
                self.forwarded_silence_bytes = trailing_silence_bytes
            self.last_input_forwarded_at = self.clock()

    async def forward_assistant(self, key, item, data, first_received_at, *, v55):
        # Durable archive BEFORE browser playback; no independent browser recording upload.
        await self.record('assistant', data, {'utteranceId': key, 'offsetBytes': item['bytes'],
                                             'playbackEvidence': 'unconfirmed'}, v55=v55)
        if item['bytes'] == 0:
            item['sent_at'] = self.clock()
            await self.socket.send_json({'type': 'audio_start', 'id': key, 'sampleRate': 24000})
            timing = item['timing']
            def elapsed(start, end):
                return None if start is None else max(0, round((end - start) * 1000))
            event('trial_realtime_first_audio', item_id=provider_id(key), turn_id=provider_id(timing.get('turn_id')),
                  stop_to_commit_ms=elapsed(timing.get('stopped_at'), timing.get('committed_at', first_received_at)),
                  commit_to_request_ms=elapsed(timing.get('committed_at'), timing.get('requested_at', first_received_at)),
                  request_to_audio_ms=elapsed(timing.get('requested_at'), first_received_at),
                  first_chunk_archive_ms=elapsed(first_received_at, item['sent_at']),
                  commit_to_forward_ms=elapsed(timing.get('committed_at'), item['sent_at']))
        item['bytes'] += len(data)
        await self.socket.send_bytes(data)
