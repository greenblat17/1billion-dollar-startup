"""DEV CLI protocol and artifact boundaries, without a paid provider call."""
import importlib.util
import asyncio
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import shutil
import threading
from types import SimpleNamespace
import uuid
import wave

import pytest
from websockets.asyncio.server import serve


SCRIPT = Path(__file__).resolve().parents[2] / 'infra/dev/agent-voice.py'
spec = importlib.util.spec_from_file_location('agent_voice', SCRIPT)
voice = importlib.util.module_from_spec(spec)
spec.loader.exec_module(voice)


@pytest.mark.parametrize('origin', ['https://example.com', 'http://127.0.0.1.evil.test',
                                   'https://user:pass@localhost', 'http://localhost/path'])
def test_voice_client_rejects_non_loopback_or_credentialed_origin(origin):
    with pytest.raises(ValueError):
        voice.local_origin(origin)


@pytest.mark.skipif(shutil.which('ffmpeg') is None, reason='ffmpeg unavailable')
def test_prepared_audio_is_24khz_pcm_with_vad_silence(tmp_path):
    source = tmp_path / 'prepared.wav'
    with wave.open(str(source), 'wb') as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(16000)
        output.writeframes(b'\0\0' * 16000)
    pcm, mode = voice.pcm_for_turn({'audio': str(source)}, tmp_path)
    assert mode == 'prepared_audio'
    assert len(pcm) == 2 * 24000 * 2
    assert pcm[-48000:] == bytes(48000)


def test_cyrillic_synthetic_turn_selects_russian_voice(tmp_path, monkeypatch):
    commands = []
    monkeypatch.setattr(voice.shutil, 'which', lambda name: 'espeak-ng' if name == 'espeak-ng' else None)

    def synthesize(command, **_):
        commands.append(command)
        with wave.open(command[command.index('-w') + 1], 'wb') as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(24000)
            output.writeframes(b'\0\0' * 2400)

    monkeypatch.setattr(voice.subprocess, 'run', synthesize)
    pcm, mode = voice.pcm_for_turn({'text': 'Привет'}, tmp_path)
    assert commands[0][1:3] == ['-v', 'ru']
    assert mode == 'synthesized_espeak_ng'
    assert len(pcm) > 48000


def test_read_only_voice_snapshot_does_not_request_grant(tmp_path):
    calls = []

    class Foundation(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            calls.append(('GET', self.path))
            data = json.dumps({'state': {'events': []}, 'trialEvents': {'events': []},
                               'jobs': [{'kind': 'trial.observe', 'status': 'failed'}]}).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    http = ThreadingHTTPServer(('127.0.0.1', 0), Foundation)
    thread = threading.Thread(target=http.serve_forever, daemon=True)
    thread.start()
    try:
        args = SimpleNamespace(ktor=f'http://127.0.0.1:{http.server_port}',
                               ai='http://127.0.0.1:18090', student=uuid.uuid4(), lesson=uuid.uuid4(),
                               output=tmp_path / 'snapshot')
        report = {'attempts': [], 'turns': [], 'audio': []}
        asyncio.run(voice.run(args, {'turns': [], 'readOnly': True}, report, 'test-token'))
        assert report['finalState']['jobs'][0]['status'] == 'failed'
        assert len(calls) == 1 and calls[0][0] == 'GET'
    finally:
        http.shutdown()
        thread.join(timeout=2)


def test_report_contains_source_ids_without_grant(tmp_path):
    class Args:
        output = tmp_path
    source = str(uuid.uuid4())
    report = {'studentId': str(uuid.uuid4()), 'lessonId': str(uuid.uuid4()), 'result': 'completed_transport',
              'finalState': {'state': {'events': [{'id': source, 'role': 'user', 'body': 'Hello'}]}}}
    voice.write_report(Args, report)
    assert source in (tmp_path / 'dialogue.md').read_text()
    assert json.loads((tmp_path / 'dialogue.json').read_text())['result'] == 'completed_transport'
    assert (tmp_path / 'dialogue.json').stat().st_mode & 0o777 == 0o600


@pytest.mark.skipif(shutil.which('ffmpeg') is None, reason='ffmpeg unavailable')
@pytest.mark.parametrize('reconnect,expect_finished,early_terminal',
                         [(True, False, None), (True, True, None), (False, False, None),
                          (False, True, None), (False, True, 'open'), (False, True, 'close')])
def test_two_pcm_turns_playback_ack_and_reconnect(tmp_path, reconnect, expect_finished, early_terminal):
    events = []
    grants = []
    acks = []
    source = tmp_path / 'short.wav'
    with wave.open(str(source), 'wb') as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(24000)
        output.writeframes(b'\0\0' * 2400)

    class Foundation(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            self.rfile.read(int(self.headers['Content-Length']))
            grants.append(self.path)
            self.answer({'grant': str(uuid.uuid4())})

        def do_GET(self):
            self.answer({'state': {'events': list(events)}, 'audio': []})

        def answer(self, payload):
            data = json.dumps(payload).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    http = ThreadingHTTPServer(('127.0.0.1', 0), Foundation)
    thread = threading.Thread(target=http.serve_forever, daemon=True)
    thread.start()

    async def relay(socket):
        opening = json.loads(await socket.recv())
        assert opening['protocol'] == 'realtime-pcm-v1'
        assert opening['agentGrant']
        assert socket.request.headers['X-Internal-Token'] == 'test-token'
        attempt = len(grants)
        await socket.send(json.dumps({'type': 'connected', 'protocol': 'realtime-pcm-v1',
                                      'inputSampleRate': 24000, 'lessonId': opening['lessonId']}))
        await socket.send(json.dumps({'type': 'audio_start', 'id': f'greeting-{attempt}', 'sampleRate': 24000}))
        await socket.send(bytes(4800))
        await socket.send(json.dumps({'type': 'audio_end', 'id': f'greeting-{attempt}'}))
        received = 0
        reply_index = 0
        async def send_last_audio_and_finish():
            await socket.send(json.dumps({'type': 'audio_start', 'id': 'reply-2-more', 'sampleRate': 24000}))
            await socket.send(bytes(4800))
            await socket.send(json.dumps({'type': 'audio_end', 'id': 'reply-2-more'}))
            if early_terminal == 'close':
                events.append({'id': str(uuid.uuid4()), 'role': 'assistant', 'body': 'reply-2-more'})
            await socket.send(json.dumps({'type': 'finished'}))
            if early_terminal == 'close':
                await socket.close()

        async for frame in socket:
            if isinstance(frame, bytes):
                received += len(frame)
                if received >= 52800:
                    received = 0
                    reply_index += 1
                    reply_id = f'reply-{attempt if reconnect else reply_index}'
                    await socket.send(json.dumps({'type': 'user_turn_complete'}))
                    await socket.send(json.dumps({'type': 'audio_start', 'id': reply_id, 'sampleRate': 24000}))
                    await socket.send(bytes(4800))
                    if reconnect and attempt == 2:
                        async def delayed_more():
                            await asyncio.sleep(.5)
                            await socket.send(json.dumps({'type': 'audio_start', 'id': 'reply-2-more', 'sampleRate': 24000}))
                            await socket.send(bytes(4800))
                            await socket.send(json.dumps({'type': 'audio_end', 'id': reply_id}))
                            await socket.send(json.dumps({'type': 'audio_end', 'id': 'reply-2-more'}))
                        asyncio.create_task(delayed_more())
                    else:
                        await socket.send(json.dumps({'type': 'audio_end', 'id': reply_id}))
                        if early_terminal == 'open' and reply_index == 2:
                            await send_last_audio_and_finish()
            else:
                control = json.loads(frame)
                if control.get('type') == 'played':
                    acks.append(control)
                    events.append({'id': str(uuid.uuid4()), 'role': 'assistant', 'body': control['id']})
                    if early_terminal == 'close' and control['id'] == 'reply-2':
                        await send_last_audio_and_finish()
                    elif expect_finished and not early_terminal and control['id'] == ('reply-2-more' if reconnect else 'reply-2'):
                        await socket.send(json.dumps({'type': 'finished'}))

    async def exercise():
        async with serve(relay, '127.0.0.1', 0) as server:
            args = SimpleNamespace(ktor=f'http://127.0.0.1:{http.server_port}',
                                   ai=f'http://127.0.0.1:{server.sockets[0].getsockname()[1]}',
                                   student=uuid.uuid4(), lesson=uuid.uuid4(), output=tmp_path / 'report')
            scenario = {'turns': [{'audio': str(source)}, {'audio': str(source)}],
                        'expectFinished': expect_finished}
            if reconnect:
                scenario['reconnectAfterTurn'] = 1
            report = {'attempts': [], 'turns': [{'input': turn, 'responseAudioIds': []} for turn in scenario['turns']],
                      'audio': []}
            await voice.run(args, scenario, report, 'test-token')
            assert len(report['attempts']) == (2 if reconnect else 1)
            assert all(turn['readAfterWriteConfirmed'] for turn in report['turns'])
            assert [turn['responseAudioIds'][0] for turn in report['turns']] == ['reply-1', 'reply-2']
            final_ids = ['reply-2', 'reply-2-more'] if reconnect or early_terminal else ['reply-2']
            assert report['turns'][1]['responseAudioIds'] == final_ids
            wire_acks = [ack for ack in acks if ack['id'].startswith('reply-') and ack['complete']]
            assert len(wire_acks) == (3 if reconnect or early_terminal == 'open' else 2)
            playback = [audio['playbackAck'] for audio in report['audio']]
            if early_terminal == 'close':
                assert playback.pop() == 'unverified_closed_after_finished'
            assert all(ack == 'simulated_timing' for ack in playback)
            if reconnect:
                assert [audio['audioAttribution'] for audio in report['audio'][-2:]] == ['ambiguous_overlap'] * 2
            assert len(list(args.output.glob('*.wav'))) == len(final_ids) + 2 + int(reconnect)
            assert report.get('finished', False) == expect_finished

    try:
        asyncio.run(exercise())
    finally:
        http.shutdown()
        thread.join(timeout=2)
