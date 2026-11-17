#!/usr/bin/env python3
"""Run a synthetic learner through the DEV Mini App voice relay on the DEV host."""
import argparse
import asyncio
import json
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import sys
import tempfile
import time
import uuid
import wave
from urllib.parse import urlencode, urlparse
from urllib.request import HTTPSHandler, ProxyHandler, Request, build_opener
from urllib.error import HTTPError

from websockets.asyncio.client import connect


def local_origin(value):
    parsed = urlparse(value)
    if parsed.scheme not in ('http', 'https') or parsed.hostname not in ('localhost', '127.0.0.1', '::1') or parsed.username or parsed.password or parsed.path not in ('', '/') or parsed.query or parsed.fragment:
        raise ValueError('Only a loopback HTTP(S) origin is allowed')
    return value.rstrip('/')


def request(base, token, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = Request(base + path, data=data, headers={'X-Internal-Token': token, 'Content-Type': 'application/json'})
    context = ssl._create_unverified_context() if base.startswith('https:') else None
    try:
        with build_opener(ProxyHandler({}), HTTPSHandler(context=context)).open(req, timeout=20) as response:
            return json.load(response)
    except HTTPError as error:
        raise RuntimeError(f'Foundation HTTP {error.code}: {error.read(500).decode(errors="replace")}') from None


def pcm_for_turn(turn, directory):
    source = turn.get('audio')
    if source:
        source = Path(source).expanduser().resolve()
        mode = 'prepared_audio'
    else:
        speech = turn.get('text')
        if not isinstance(speech, str) or not speech.strip():
            raise ValueError('Each turn needs text or audio')
        if shutil.which('say'):
            source = directory / 'speech.aiff'
            subprocess.run(['say', '-o', str(source), speech], check=True, timeout=30)
            engine = 'macos_say'
        elif shutil.which('espeak-ng'):
            source = directory / 'speech.wav'
            subprocess.run(['espeak-ng', '-w', str(source), speech], check=True, timeout=30)
            engine = 'espeak_ng'
        else:
            raise RuntimeError('Install say or espeak-ng, or provide a prepared audio file')
        mode = 'synthesized_' + engine
    pcm = None
    if source.suffix.lower() == '.wav':
        with wave.open(str(source), 'rb') as prepared:
            if (prepared.getnchannels(), prepared.getsampwidth(), prepared.getframerate(), prepared.getcomptype()) == (1, 2, 24000, 'NONE'):
                pcm = prepared.readframes(prepared.getnframes())
    if pcm is None:
        if not shutil.which('ffmpeg'):
            raise RuntimeError('ffmpeg is required to convert audio other than PCM WAV 24 kHz mono')
        output = directory / 'input.pcm'
        subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-y', '-i', str(source), '-ac', '1', '-ar', '24000',
                        '-f', 's16le', str(output)], check=True, timeout=30)
        pcm = output.read_bytes()
    if not pcm or len(pcm) % 2 or len(pcm) > 24000 * 2 * 25:
        raise ValueError('Input must be 0–25 seconds of mono speech')
    return pcm + bytes(24000 * 2), mode


def save_wav(path, pcm):
    with wave.open(str(path), 'wb') as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(24000)
        output.writeframes(pcm)
    os.chmod(path, 0o600)


async def state(base, token, root, lesson):
    return await asyncio.to_thread(request, base, token, root + '/voice-state?' + urlencode({'lessonId': lesson}))


async def persisted_reply(base, token, root, lesson, before):
    result = None
    for _ in range(10):
        result = await state(base, token, root, lesson)
        events = result.get('state', {}).get('events', [])
        if any(e.get('role') == 'assistant' and e.get('id') not in before for e in events):
            return result, True
        await asyncio.sleep(1)
    return result, False


async def run(args, scenario, report, token):
    ktor, ai = local_origin(args.ktor), local_origin(args.ai)
    args.output.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(args.output, 0o700)
    root = f'/internal/agent-test/v1/{args.student}'
    ws_url = ai.replace('https:', 'wss:').replace('http:', 'ws:') + '/miniapp/voice'
    context = ssl._create_unverified_context() if ws_url.startswith('wss:') else None
    turns = scenario['turns']
    next_turn = 0
    reconnect_at = scenario.get('reconnectAfterTurn')
    while next_turn < len(turns):
        if len(report['attempts']) >= 5:
            raise RuntimeError('Reconnect attempt limit reached')
        grant = await asyncio.to_thread(request, ktor, token, root + '/voice-grant', {'lessonId': str(args.lesson)})
        attempt = str(uuid.uuid4())
        report['attempts'].append({'id': attempt, 'startedAt': time.time(), 'events': []})
        episode = report['attempts'][-1]
        async with connect(ws_url, ssl=context, additional_headers={'X-Internal-Token': token},
                           max_size=2**20, open_timeout=15, close_timeout=5) as socket:
            await socket.send(json.dumps({'protocol': 'realtime-pcm-v1', 'agentGrant': grant['grant'],
                                          'lessonId': str(args.lesson), 'attemptId': attempt}))
            active = {}
            acked = asyncio.Queue()
            ack_tasks = set()
            last_ping = time.monotonic()
            last_activity = time.monotonic()
            greeting_done = False
            sender = None
            before_events = set()
            async def acknowledge(item_id, milliseconds):
                await asyncio.sleep(milliseconds / 1000)
                await socket.send(json.dumps({'type': 'played', 'id': item_id, 'complete': True,
                                              'playedMs': milliseconds}))
                await acked.put(item_id)
            async def send_turn(index):
                turn = turns[index]
                with tempfile.TemporaryDirectory() as temporary:
                    pcm, mode = await asyncio.to_thread(pcm_for_turn, turn, Path(temporary))
                    report['turns'][index]['inputMode'] = mode
                    report['turns'][index]['inputDurationMs'] = round(len(pcm) / 48)
                    report['turns'][index]['sentAt'] = time.time()
                    for offset in range(0, len(pcm), 4800):
                        await socket.send(pcm[offset:offset + 4800])
                        await asyncio.sleep(.1)
            while next_turn < len(turns):
                while not acked.empty():
                    item_id = acked.get_nowait()
                    if sender is not None and not sender.done():
                        await acked.put(item_id)
                        break
                    if sender is None:
                        greeting_done = True
                    elif sender.done():
                        sender.result()
                        report['turns'][next_turn]['responseAudioIds'].append(item_id)
                        current, confirmed = await persisted_reply(ktor, token, root, str(args.lesson), before_events)
                        report['turns'][next_turn]['readAfterWrite'] = current
                        report['turns'][next_turn]['readAfterWriteConfirmed'] = confirmed
                        next_turn += 1
                        sender = None
                        if reconnect_at == next_turn and next_turn < len(turns):
                            episode['disconnect'] = 'reconnect_scenario'
                            break
                if episode.get('disconnect'):
                    break
                if greeting_done and sender is None and next_turn < len(turns):
                    greeting_done = False
                    prior, _ = await persisted_reply(ktor, token, root, str(args.lesson), set())
                    before_events = {event.get('id') for event in prior.get('state', {}).get('events', [])}
                    sender = asyncio.create_task(send_turn(next_turn))
                if time.monotonic() - last_ping > 5:
                    await socket.send(json.dumps({'type': 'ping', 'audio': {'capturedFrames': 0, 'sentFrames': 0,
                                                                           'muted': False, 'playing': len(active)}}))
                    last_ping = time.monotonic()
                try:
                    raw = await asyncio.wait_for(socket.recv(), timeout=1)
                except asyncio.TimeoutError:
                    if time.monotonic() - last_activity > 90:
                        raise TimeoutError('No relay event for 90 seconds')
                    continue
                last_activity = time.monotonic()
                if isinstance(raw, bytes):
                    if active:
                        item = list(active.values())[-1]
                        item['audio'].extend(raw)
                    continue
                message = json.loads(raw)
                kind = message.get('type')
                episode['events'].append({'at': time.time(), **{k: v for k, v in message.items() if k not in ('agentGrant', 'initData')}})
                if kind == 'error':
                    if message.get('code') == 'active_episode' and len(report['attempts']) < 5:
                        episode['disconnect'] = 'admission_retry'
                        break
                    raise RuntimeError(f"Relay error: {message.get('code')} ({message.get('errorId', '')})")
                if kind == 'connected':
                    if (message.get('protocol') != 'realtime-pcm-v1' or message.get('inputSampleRate') != 24000
                            or message.get('lessonId') != str(args.lesson)):
                        raise RuntimeError('Relay protocol mismatch')
                    episode['lessonId'] = message.get('lessonId')
                elif kind == 'audio_start':
                    if message.get('sampleRate') != 24000:
                        raise RuntimeError('Relay output sample rate mismatch')
                    active[message['id']] = {'audio': bytearray(), 'startedAt': time.monotonic()}
                    if sender is not None and 'firstAudioAt' not in report['turns'][next_turn]:
                        turn_record = report['turns'][next_turn]
                        turn_record['firstAudioAt'] = time.time()
                        turn_record['firstAudioAfterUserTurnMs'] = (round((turn_record['firstAudioAt'] - turn_record['userTurnCompleteAt']) * 1000)
                                                                    if 'userTurnCompleteAt' in turn_record else None)
                elif kind == 'audio_end':
                    item = active.pop(message['id'], None)
                    if item is None:
                        continue
                    pcm = bytes(item['audio'])
                    filename = f"attempt-{len(report['attempts'])}-audio-{len(report['audio']) + 1}.wav"
                    save_wav(args.output / filename, pcm)
                    duration = len(pcm) / 48
                    # A headless client observes the full PCM duration before acknowledging playback.
                    task = asyncio.create_task(acknowledge(message['id'], duration))
                    ack_tasks.add(task)
                    task.add_done_callback(ack_tasks.discard)
                    report['audio'].append({'providerItemId': message['id'], 'file': filename,
                                            'durationMs': duration, 'playbackAck': 'simulated_timing'})
                elif kind == 'interrupt':
                    for key, item in active.items():
                        played = min(len(item['audio']) / 48, (time.monotonic() - item['startedAt']) * 1000)
                        await socket.send(json.dumps({'type': 'played', 'id': key, 'complete': False, 'playedMs': played}))
                        report['audio'].append({'providerItemId': key, 'playbackAck': 'interrupted', 'playedMs': played})
                    active.clear()
                elif kind == 'user_turn_complete' and sender is not None:
                    report['turns'][next_turn]['userTurnCompleteAt'] = time.time()
                elif kind == 'finished':
                    raise RuntimeError('Lesson finished before the scenario ended')
                elif kind == 'reconnect':
                    episode['disconnect'] = 'relay_reconnect'
                    break
            if sender is not None and not sender.done():
                sender.cancel()
                await asyncio.gather(sender, return_exceptions=True)
            for task in ack_tasks:
                task.cancel()
            await asyncio.gather(*ack_tasks, return_exceptions=True)
            # Closing without `end` models a dropped transport and keeps the lesson recoverable.
        if next_turn < len(turns):
            await asyncio.sleep(3)
    report['finalState'] = await state(ktor, token, root, str(args.lesson))


def write_report(args, report):
    args.output.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(args.output, 0o700)
    json_path = args.output / 'dialogue.json'
    with json_path.open('w') as output:
        json.dump(report, output, ensure_ascii=False, indent=2)
    os.chmod(json_path, 0o600)
    events = (report.get('finalState') or {}).get('state', {}).get('events', [])
    lines = ['# Synthetic voice learner on DEV', '', f"Student: `{report['studentId']}`", f"Lesson: `{report['lessonId']}`", '',
             'Input audio is prepared or synthesized. Playback timing is simulated; no microphone or human listening was tested.', '']
    for item in events:
        if item.get('role') in ('user', 'assistant') and item.get('body'):
            lines += [f"**{item['role']}** · source `{item.get('id', '')}`", '', item['body'], '']
    lines += ['## Outcome', '', report['result'], '']
    if report.get('error'):
        lines += [report['error'], '']
    markdown = args.output / 'dialogue.md'
    markdown.write_text('\n'.join(lines))
    os.chmod(markdown, 0o600)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('scenario', type=Path, help='JSON with turns: [{text or audio}], optional reconnectAfterTurn')
    parser.add_argument('--ktor', required=True, help='DEV Ktor loopback origin')
    parser.add_argument('--ai', required=True, help='DEV AI service loopback origin')
    parser.add_argument('--student', required=True, type=uuid.UUID, help='existing agent_test student UUID')
    parser.add_argument('--lesson', required=True, type=uuid.UUID, help='owned executable lesson UUID')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    token = os.environ.get('AI_INTERNAL_TOKEN')
    if not token:
        parser.error('AI_INTERNAL_TOKEN must be set in the environment')
    try:
        local_origin(args.ktor)
        local_origin(args.ai)
        scenario = json.loads(args.scenario.read_text())
        turns = scenario['turns']
        if not isinstance(turns, list) or not 1 <= len(turns) <= 5 or any(not isinstance(t, dict) or not (t.get('text') or t.get('audio')) for t in turns):
            raise ValueError('Scenario needs 1–5 turns with text or audio')
        reconnect_at = scenario.get('reconnectAfterTurn')
        if reconnect_at is not None and (type(reconnect_at) is not int or reconnect_at not in range(1, len(turns))):
            raise ValueError('reconnectAfterTurn must be between turns')
    except (ValueError, KeyError, OSError) as error:
        parser.error(str(error))
    report = {'studentId': str(args.student), 'lessonId': str(args.lesson), 'scenario': str(args.scenario),
              'result': 'incomplete', 'attempts': [], 'turns': [{'input': t, 'responseAudioIds': []} for t in turns], 'audio': []}
    try:
        asyncio.run(run(args, scenario, report, token))
        report['result'] = 'completed_transport'
    except Exception as error:
        report['result'] = 'failed'
        report['error'] = str(error)
    write_report(args, report)
    print(json.dumps({'result': report['result'], 'studentId': report['studentId'], 'lessonId': report['lessonId'],
                      'report': str(args.output / 'dialogue.json')}))
    return 0 if report['result'] == 'completed_transport' else 1


if __name__ == '__main__':
    sys.exit(main())
