import asyncio
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch


MODULE_PATH = Path(__file__).with_name("agent-voice.py")
SPEC = importlib.util.spec_from_file_location("agent_voice", MODULE_PATH)
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


class FakeRelay:
    def __init__(self, trial):
        self.now = 0
        self.trial_seconds = 0
        self.first_pcm = None
        connected = {"type": "connected", "lessonId": "lesson",
                     "protocol": "realtime-pcm-v1", "inputSampleRate": 24000}
        if trial:
            connected["trialExecution"] = {"connectedSeconds": 0}
        self.messages = [
            (0, connected),
            (0, {"type": "audio_start", "id": "greeting", "sampleRate": 24000}),
            (0, {"type": "audio_end", "id": "greeting"}),
            (1, None),
        ]
        if trial:
            self.messages.extend([
                (910, {"type": "trial_clock", "trialExecution": {"connectedSeconds": 899}}),
                (925, {"type": "trial_clock", "trialExecution": {"connectedSeconds": 915}}),
            ])
        else:
            self.messages.extend([(80, None), (450, None), (914, None), (915, None)])
        self.finish = asyncio.Event()
        self.greeting_played = asyncio.Event()
        self.reply = [
            {"type": "audio_start", "id": "reply", "sampleRate": 24000},
            {"type": "audio_end", "id": "reply"},
            {"type": "finished"},
        ]

    async def __aenter__(self):
        return self

    async def __aexit__(self, *ignored):
        pass

    async def send(self, data):
        if isinstance(data, bytes):
            self.first_pcm = (self.now, self.trial_seconds)
            self.finish.set()
        elif json.loads(data).get('id') == 'greeting':
            self.greeting_played.set()

    async def recv(self):
        if self.messages:
            at, message = self.messages.pop(0)
            if at >= 80:
                await self.greeting_played.wait()
            self.now = at
            if message is None:
                raise asyncio.TimeoutError
            if message["type"] == "trial_clock":
                self.trial_seconds = message["trialExecution"]["connectedSeconds"]
            return json.dumps(message)
        await self.finish.wait()
        if self.reply:
            return json.dumps(self.reply.pop(0))
        await asyncio.Future()


class AgentVoiceTimingTest(unittest.IsolatedAsyncioTestCase):
    async def run_delayed_turn(self, trial):
        relay = FakeRelay(trial)
        clock = SimpleNamespace(monotonic=lambda: relay.now, time=lambda: relay.now)
        scenario = {"turns": [{"text": "Ready to practise", "atConnectedSeconds": 915}],
                    "expectFinished": True}
        report = {"attempts": [], "audio": [], "turns": [{"responseAudioIds": []}]}
        with tempfile.TemporaryDirectory() as output:
            args = SimpleNamespace(ktor="http://127.0.0.1:8080", ai="http://127.0.0.1:8081",
                                   student="student", lesson="lesson", output=Path(output))
            with (patch.object(RUNNER, "time", clock),
                  patch.object(RUNNER, "connect", return_value=relay),
                  patch.object(RUNNER, "request", return_value={"grant": "test"}),
                  patch.object(RUNNER, "pcm_for_turn", return_value=(b"\0\0", "fake")),
                  patch.object(RUNNER, "persisted_reply", new=AsyncMock(return_value=({}, True))),
                  patch.object(RUNNER, "state", new=AsyncMock(return_value={}))):
                await asyncio.wait_for(RUNNER.run(args, scenario, report, "test"), timeout=3)
        self.assertTrue(report["finished"])
        return relay.first_pcm

    async def test_regular_uses_local_elapsed_and_waits_through_relay_silence(self):
        self.assertEqual((915, 0), await self.run_delayed_turn(trial=False))

    async def test_trial_uses_relay_connected_seconds(self):
        self.assertEqual((925, 915), await self.run_delayed_turn(trial=True))



class AgentVoiceMuteBoundaryTest(unittest.IsolatedAsyncioTestCase):
    async def test_boundary_follows_last_pcm_and_next_pcm_follows_unmute(self):
        socket = SimpleNamespace(send=AsyncMock())
        practice, answer = {}, {}
        first_chunk, final_chunk, answer_pcm = b'a' * 4800, b'bc', b'de'
        with patch.object(RUNNER.asyncio, 'sleep', new=AsyncMock()):
            await RUNNER.send_turn_pcm(socket, first_chunk + final_chunk, practice, mute_after=True)
            await RUNNER.send_turn_pcm(socket, answer_pcm, answer, resume_input=True)
        frames = [item.args[0] for item in socket.send.await_args_list]
        self.assertEqual([first_chunk, final_chunk, {'type': 'mute', 'value': True},
                          {'type': 'mute', 'value': False}, answer_pcm],
                         [json.loads(frame) if isinstance(frame, str) else frame for frame in frames])
        self.assertLessEqual(practice['sentAt'], practice['muteBoundarySentAt'])
        self.assertLessEqual(practice['muteBoundarySentAt'], answer['unmutedAt'])
        self.assertLessEqual(answer['unmutedAt'], answer['sentAt'])

    async def test_ordinary_turn_sends_only_pcm(self):
        socket = SimpleNamespace(send=AsyncMock())
        turn = {}
        with patch.object(RUNNER.asyncio, 'sleep', new=AsyncMock()):
            await RUNNER.send_turn_pcm(socket, b'12', turn)
        socket.send.assert_awaited_once_with(b'12')
        self.assertEqual({'sentAt'}, set(turn))

    async def test_boundary_skips_mute_after_auto_vad_commits_during_pcm(self):
        turn = {}

        async def auto_vad(frame):
            if isinstance(frame, bytes):
                turn['userTurnCompleteAt'] = 1.0

        socket = SimpleNamespace(send=AsyncMock(side_effect=auto_vad))
        with patch.object(RUNNER.asyncio, 'sleep', new=AsyncMock()):
            muted = await RUNNER.send_turn_pcm(socket, b'12', turn, mute_after=True)
        socket.send.assert_awaited_once_with(b'12')
        self.assertFalse(muted)
        self.assertEqual('relay_already_committed', turn['muteBoundarySkipped'])
        self.assertNotIn('muteBoundarySentAt', turn)

    async def test_native_boundary_across_successive_turns(self):
        socket = SimpleNamespace(send=AsyncMock())
        records = [{}, {}, {}]
        muted = False
        with patch.object(RUNNER.asyncio, 'sleep', new=AsyncMock()):
            for pcm, record in zip((b'12', b'34', b'56'), records):
                muted = await RUNNER.send_turn_pcm(socket, pcm, record, resume_input=muted, mute_after=True)
        frames = [item.args[0] for item in socket.send.await_args_list]
        mute, unmute = {'type': 'mute', 'value': True}, {'type': 'mute', 'value': False}
        self.assertEqual([b'12', mute, unmute, b'34', mute, unmute, b'56', mute],
                         [json.loads(frame) if isinstance(frame, str) else frame for frame in frames])
        self.assertTrue(all('muteBoundarySentAt' in record for record in records))
