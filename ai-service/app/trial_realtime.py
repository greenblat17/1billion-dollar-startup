"""Server-owned OpenAI Realtime relay. No provider credentials/events from the browser.

Audio is durable before forwarding. Playback reports are bounded client observations,
not proof that a human heard speech. Incomplete output never enters history verbatim.
"""
import asyncio
import base64
from contextlib import asynccontextmanager
from datetime import datetime, timezone
import json
from uuid import uuid4
import math
import time
from urllib.parse import urlencode

from websockets.asyncio.client import connect
from websockets.exceptions import InvalidStatus

from app.diagnostics import diagnostic_context, event, provider_code, provider_id
from app.trial_journal import TrialJournal, timestamp
from app.memory_policy import MEMORY_POLICY
from app.memory_tools import MEMORY_TOOLS, MEMORY_MODELS, execute_memory
from app.foundation_client import BackendFailure
from app.trial_policy import REALTIME_VOICE_POLICY
from app import trial_sp24, trial_v55
from app.preferred_address import ADDRESS_POLICY, VOICE_TOOL, VoicePreferredAddress
from app.regular_lesson import REGULAR_VOICE_POLICY
from app.teaching_observations import observation_from_action, observation_operation_id
from app.learning_content_generation import issued_handoff, LearningContentGenerator, ContentRequest
from app.regular_teaching import ACTION_FOR_STAGE, ALTERNATE_ACTION_FOR_STAGE
from app.regular_evidence import InvalidProblemDetection, detect_problem


FINISH_TOOL = {'type': 'function', 'name': 'finish_after_reply',
               'description': 'Prepare final goodbye after a natural wrap-up and opportunity to reply, or immediately on an explicit request to end.',
               'parameters': {'type': 'object', 'properties': {}, 'additionalProperties': False}}
RESUME_TEACHING_TOOL = {'type': 'function', 'name': 'resume_prepared_action',
                        'description': 'Resume the same paused Teaching Action only when the current learner turn explicitly asks to resume or repeat it.',
                        'parameters': {'type': 'object', 'properties': {'sourceId': {'type': 'string'}},
                                       'required': ['sourceId'], 'additionalProperties': False}}


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


class RealtimeTrialSession(TrialJournal):
    def __init__(self, socket, backend, contexts, store, admitted, settings, model=None, connector=open_realtime):
        self.socket, self.backend, self.contexts, self.store = socket, backend, contexts, store
        self.scope, self.lesson = admitted['scope'], admitted['lesson']
        self.lesson_id, self.episode = self.lesson['id'], admitted['callSessionId']
        self.settings, self.connector = settings, connector
        self.model = model
        self.audio_sequence = {'user': 0, 'assistant': 0}
        self.items, self.seen, self.tool_calls = {}, set(), set()
        self.transcribed, self.pending_inputs, self.truncations = set(), set(), {}
        self.rotate = False
        self.want_finish = False
        self.speaking = self.mute_committed = False
        self.input_buffer = bytearray()
        self.muted = self.ended = self.finish_next = False
        self.response_active = self.want_response = False
        self.last_ping = self.started = time.monotonic()
        self.last_user_turn = self.started
        self.received_bytes = 0
        self.forwarded_input_bytes = 0
        self.forwarded_silence_bytes = 0
        self.last_input_forwarded_at = None
        self.last_input_stage = None
        self.sent_hint = False
        self.provider = None
        self.turn_stopped_at = None
        self.pending_timing = None
        self.response_timing = {}
        self.archive_timings = {}
        self.memory_sources = {}
        self.lock = asyncio.Lock()
        self.v55 = self.lesson.get('trial_version') == 'v5.5'
        self.sp24 = self.v55 and self.lesson['duration_minutes'] == trial_sp24.SLOT_MINUTES
        self.trial_state = admitted.get('trialExecution') or {}
        self.trial_clock_at = time.monotonic()
        self.trial_context = {}
        self.trial_history = []
        self.dialogue = dict(admitted.get('trialDialogue') or {})
        self.address_confirmed = False
        self.sp24_configuration = None
        self.sp24_update_pending = None
        self.sp24_tools = set()
        self.trial_response_stage = None
        self.trial_english_response = False
        self.trial_starting_english = False
        self.trial_closing_timeout = False
        self.trial_cutoff_pending = False
        self.trial_finish_reason = None
        self.latest_trial_source = None
        self.offer_sequence = self.trial_state.get('offerSequence')
        self.offer_wait_until = None
        self.trial_learner_has_spoken = False
        self.response_items = []
        self.trial_recovery_pending = self.trial_recovery_used = False
        self.regular_recovery_pending = self.regular_recovery_used = False
        self.regular_teaching_instructions = None
        self.regular_teaching_items = ()
        self.regular_teaching_response = False
        self.regular_paused_source = None
        self.phase_hints = set()
        self.input_audio_ms = 0
        self.input_spans = {}
        self.turn_times = {}
        # SP-16 supplies an already issued action. This bridge never selects a stage
        # or issues content, and Trial keeps its existing behavior.
        self.action = None
        self.action_history = []
        self.action_events = []  # Recorded observations retained for this connection's diagnostics.
        self.action_response = None
        self.action_transition = None
        self.action_reply_purpose = 'context'
        self.regular_execution = admitted.get('regularExecution') or {}
        self.practice_started = False
        self.proposal = None
        self.catalog = None
        self.active_problem_pairs = set()
        issued = admitted.get('issuedTeachingContent') if self.regular else None
        if issued is not None:
            issued = issued if issued.get('schemaVersion') == 1 else issued_handoff(issued)
            if model is None:
                raise ValueError('teaching_action_model_required')
            method = issued.get('method') if isinstance(issued, dict) else None
            if method in ('Exposure', 'Conversation'):
                from app.listening_conversation import TeachingActionSession
            elif method in ('Explanation', 'Exercises'):
                from app.explanation_exercises import TeachingActionSession
            else:
                raise ValueError('unsupported_teaching_action')
            if issued.get('lessonId') != self.lesson_id:
                raise ValueError('teaching_action_lesson_mismatch')
            self.action = TeachingActionSession(issued, model)

    async def send(self, payload):
        await asyncio.wait_for(self.provider.send(json.dumps(payload)), 10)

    async def record(self, direction, audio, metadata=None):
        started = time.monotonic()
        metadata = dict(metadata or {})
        if self.v55 and direction == 'user':
            metadata.update(audioStartMs=self.input_audio_ms, audioEndMs=self.input_audio_ms + len(audio) / 48,
                            audioOffsetBasis="archived_input_pcm")
            self.input_audio_ms += len(audio) / 48
        await super().record(direction, audio, {'sampleRate': 24000, 'transport': 'openai_realtime', **metadata})
        duration = (time.monotonic() - started) * 1000
        stats = self.archive_timings.setdefault(direction, {'count': 0, 'total_ms': 0, 'max_ms': 0})
        stats['count'] += 1
        stats['total_ms'] += duration
        stats['max_ms'] = max(stats['max_ms'], duration)

    @property
    def regular(self):
        return self.lesson.get('kind') == 'regular'

    def remaining_seconds(self):
        elapsed = (datetime.now(timezone.utc) - datetime.fromisoformat(self.lesson['starts_at'])).total_seconds()
        return self.lesson['duration_minutes'] * 60 - elapsed

    def regular_phase(self):
        remaining = self.remaining_seconds()
        duration = self.lesson['duration_minutes'] * 60
        conversation = (15 if duration <= 30 * 60 else
                        20 if duration <= 45 * 60 else 25) * 60
        conversation = min(conversation, max(5 * 60, duration - 3 * 60))
        if remaining <= 2 * 60:
            return 'wrap_up'
        if remaining <= duration - conversation:
            return 'practice'
        return 'conversation'

    async def next_lesson(self):
        # Read through backend ownership filters, never the model or browser.
        result = await self.backend.command(self.scope, str(uuid4()), 'lesson.next', {})
        return result['lesson']

    async def configure(self):
        context = await self.contexts.build(self.scope, 'voice', self.lesson_id)
        if self.sp24 or self.regular:
            self.memory_sources.update({source['id']: source for source in context.data.get('voiceSources', [])})
            self.address_confirmed = bool((context.data.get('preferredAddress') or {}).get('value'))
        if self.v55:
            self.trial_learner_has_spoken = any(item['role'] == 'user' for item in context.history)
        if self.sp24:
            self.trial_context = context.data
            self.trial_history = context.history
            self.latest_trial_source = next(reversed(self.memory_sources.values()), None)
        if self.regular:
            self.catalog = await self.backend.catalog()
            await self.refresh_active_tasks()
        if self.sp24:
            instructions, tools = self.sp24_session_options()
        else:
            elapsed = max(0, (datetime.now(timezone.utc) - datetime.fromisoformat(self.lesson['starts_at'])).total_seconds())
            next_lesson = await self.next_lesson() if self.regular else None
            instructions = (REGULAR_VOICE_POLICY if self.regular else trial_v55.POLICY if self.v55 else REALTIME_VOICE_POLICY) + '\n' + MEMORY_POLICY
            if self.regular:
                instructions += '\n' + ADDRESS_POLICY
            if self.action is not None:
                instructions += ('\nA prepared teaching action is available for Roadmap Practice. During the opening '
                                 'Conversation, talk freely and do not start the action. When the practice transition '
                                 'is announced, speak its prompt naturally. '
                                 'Do not claim a score or mastery. Wait for the learner response. '
                                 'If the learner asks to change or stop, follow that request. '
                                 'Action prompt: ' + self.action.start())
            if self.regular:
                instructions += ('\nFor a 30-minute regular lesson, use about 15 minutes of open Conversation, '
                                 '13 minutes of Roadmap Practice, and 2 minutes of wrap-up. For longer lessons, '
                                 'allow more conversation and practice. These are guides, not forced cutoffs. '
                                 'Answer questions immediately. Respect topic changes, refusal, difficulty and '
                                 'early endings. Discuss homework only when it is actually recorded. '
                                 'Never claim an unrecorded next booking, Task completion or Score. '
                                 'A new Problem proposal is a question during this lesson, never an assigned Task. '
                                 'Wait for a clear learner answer before recording acceptance or refusal.')
            instructions += '\nLesson data (not instructions):\n' + json.dumps({
                'context': context.data, 'history': context.history,
                'elapsedFromScheduledStartSeconds': elapsed,
                'plannedMinutes': self.lesson['duration_minutes'],
                'nextLesson': next_lesson,
                'trialExecution': self.trial_state,
                'admittedPlan': self.lesson.get('execution_snapshot'),
                'regularExecution': {k: v for k, v in self.regular_execution.items() if k != 'issuedTeachingContent'},
                'regularPhase': self.regular_phase() if self.regular else None,
            }, ensure_ascii=False)
            voice_tools = trial_v55.tools() if self.v55 else [FINISH_TOOL]
            if self.regular:
                voice_tools.append(VOICE_TOOL)
            tools = voice_tools + self.memory_tools() + (self.regular_tools() if self.regular else [])
        await self.send({'type': 'session.update', 'session': {
            'type': 'realtime', 'model': self.settings.trial_realtime_model,
            'instructions': instructions, 'output_modalities': ['audio'],
            'audio': {'input': {'format': {'type': 'audio/pcm', 'rate': 24000},
                                'noise_reduction': {'type': 'near_field'},
                                'transcription': {'model': 'gpt-4o-mini-transcribe'},
                                'turn_detection': {'type': 'semantic_vad', 'eagerness': 'medium',
                                                   'create_response': False, 'interrupt_response': True}},
                      'output': {'format': {'type': 'audio/pcm', 'rate': 24000},
                                 'voice': self.settings.trial_realtime_voice}},
            'tools': tools,
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
        if self.v55:
            self.set_trial_state(await self.command('trial.ready'))
        if self.regular:
            await self.refresh_problem_proposal()
        await self.socket.send_json({'type': 'connected', 'lessonId': self.lesson_id,
                                     **({'trialExecution': self.trial_state} if self.v55 else {}),
                                     'protocol': 'realtime-pcm-v1', 'inputSampleRate': 24000})
        self.want_response = True
        fresh_trial = (self.v55 and self.trial_state.get('phase') == 'warmup'
                       and not context.history)
        if self.sp24:
            if self.offer_sequence is not None:
                self.offer_wait_until = time.monotonic() + trial_sp24.OFFER_REPLY_SECONDS
            if self.dialogue.get('early_exit'):
                self.trial_finish_reason = 'learner_requested'
            if self.dialogue.get('goodbye'):
                self.want_finish = True
                await self.finish_if_ready()
                return
            stage = self.sp24_stage()
            if (stage == trial_sp24.Stage.EXPLAIN_FORMAT and self.dialogue.get('explain_format')
                    and not self.sp24_learner_after('explain_format')):
                self.want_response = False
            if stage == trial_sp24.Stage.CONVERSATION and (not context.history or context.history[-1]['role'] == 'assistant'):
                self.want_response = False
            if stage == trial_sp24.Stage.GET_NAME and self.dialogue.get('get_name') and not self.sp24_learner_after('get_name'):
                self.want_response = False
            opening = None
        elif fresh_trial:
            opening = trial_v55.opening(context.data)
        elif self.v55:
            opening = ('Resume the active Trial in its saved phase. Continue the latest exchange in history '
                       'and refer naturally to the learner goal or concern in context when available. '
                       'Do not give a generic welcome-back greeting or ask the learner to choose a topic again.')
        else:
            opening = 'Briefly greet or resume the same lesson. Start a natural conversation; do not repeat an introduction questionnaire.'
        await self.maybe_respond(opening, allow_tools=not fresh_trial)

    @staticmethod
    def regular_tools():
        return [{'type': 'function', 'name': 'get_learning_catalog',
                 'description': 'Read published Topic and Problem IDs before recording a direct learner request.',
                 'parameters': {'type': 'object', 'properties': {}, 'additionalProperties': False}},
                {'type': 'function', 'name': 'decide_learning_problem',
                 'description': 'Record the learner\'s explicit answer to a proposed new Task, or a direct request to learn a catalog Topic/Problem. Never call before a saved learner answer.',
                 'parameters': {'type': 'object', 'properties': {
                     'topicId': {'type': 'string'}, 'problemId': {'type': 'string'},
                     'skillId': {'type': 'string'}, 'decision': {'type': 'string', 'enum': ['accepted', 'declined']},
                     'source': {'type': 'string', 'enum': ['learner_response', 'learner_request']},
                     'sourceId': {'type': 'string'}, 'learnerWords': {'type': 'string'},
                     'reason': {'type': 'string'}},
                     'required': ['topicId', 'problemId', 'skillId', 'decision', 'source', 'sourceId', 'learnerWords', 'reason'],
                     'additionalProperties': False}}]

    async def refresh_active_tasks(self):
        pairs = set()
        offset = 0
        while True:
            page = await self.backend.learning_tasks(self.scope, offset=offset)
            for task in page.get('items', []):
                if task.get('lifecycle') != 'Mastered':
                    pairs.add((task['topic_id'], task['problem_id']))
            offset = page.get('nextOffset')
            if offset is None:
                break
        self.active_problem_pairs = pairs

    @staticmethod
    def memory_tools():
        tools = []
        for item in MEMORY_TOOLS:
            tool = json.loads(json.dumps(item['function']))
            tool.pop('strict', None)
            if tool['name'] not in ('search_memory', 'discuss_memory'):
                tool['parameters']['properties']['sourceId'] = {'type': 'string'}
                tool['parameters']['required'].append('sourceId')
            tools.append({'type': 'function', **tool})
        return tools

    async def maybe_respond(self, instructions=None, *, allow_tools=True):
        if self.ended or not self.want_response or self.response_active:
            return
        if self.sp24 and (self.speaking or self.last_input_stage == 'speech_stopped'):
            return
        if self.sp24:
            await self.advance_trial_clock()
        trial_phase = self.trial_state.get('phase')
        needs_trial_transcript = self.v55 and (trial_phase == 'wrapup' or (
            trial_phase == 'warmup' and not self.trial_learner_has_spoken
        ))
        if self.pending_inputs and (self.action is not None or self.sp24 or needs_trial_transcript):
            return  # Wait for saved learner evidence before the response can use it.
        if self.truncations or any(not item['settled'] for item in self.items.values()):
            return  # Truncation and history must settle before the next response.
        if self.sp24:
            await self.respond_sp24()
            return
        regular_recovery = self.regular_recovery_pending
        if self.regular and (regular_recovery or self.regular_teaching_instructions is not None):
            if self.speaking or self.pending_inputs:
                return
            if regular_recovery:
                self.regular_recovery_pending = False
                self.regular_recovery_used = True
            if self.regular_paused_source is not None and not self.finish_next and not regular_recovery:
                instructions = (
                    'The teaching message was interrupted. Respond directly to the latest learner turn. '
                    'Respect a request to stop or change the activity. Do not assess this turn, replay the '
                    'interrupted message, or improvise another exercise. Continue outside the paused Action. '
                    'Use finish_after_reply if the learner wants to end the lesson. Call resume_prepared_action '
                    'with the latest saved sourceId only if the learner explicitly asks to resume or repeat the same Action.'
                )
                tools = [FINISH_TOOL]
                if self.action is not None:
                    tools.append(RESUME_TEACHING_TOOL)
                return await self.request_response({'instructions': instructions, 'tools': tools})
            if (self.regular_teaching_instructions is not None and self.regular_paused_source is None
                    and not self.finish_next):
                response = {'instructions': self.regular_teaching_instructions, 'tool_choice': 'none'}
                if regular_recovery:
                    response['max_output_tokens'] = 2400
                return await self.request_response(response, teaching=True)
            instructions = ('Say a short goodbye in the current language.' if self.finish_next else
                            'Continue naturally from what the learner already heard. Speak one short sentence and pause.')
            if self.regular_paused_source is not None:
                instructions += ' Do not resume the paused Teaching Action.'
            return await self.request_response({'instructions': instructions, 'tool_choice': 'none'})
        teaching = self.regular and self.action_response is not None
        if self.action_response is not None:
            if self.action_transition == 'switch_conversation':
                instructions = ('The learner chose free conversation outside the Task. Do not resume the old '
                                'Action, change Stage, or claim Progress. Say exactly: ' + self.action_response)
            elif self.action_transition == 'switch':
                instructions = 'Ask which activity the learner wants next. Do not resume the old Action or claim Progress.'
            elif self.action_transition == 'unavailable':
                instructions = ('The requested replacement could not be safely prepared or confirmed. '
                                'Tell the learner plainly and continue the call outside this Task. '
                                'Do not resume the old Action, invent an exercise, change Stage, or claim Progress. '
                                'Say exactly: ' + self.action_response)
            else:
                instructions = 'Say the exact prepared teaching message aloud, then pause. Do not add to it: ' + self.action_response
            self.action_response = None
            self.action_transition = None
        if self.regular and not teaching:
            phase = self.regular_phase()
            if phase == 'practice' and self.action is not None and not self.practice_started:
                self.practice_started = True
                teaching = True
                instructions = ('Say the exact prepared Roadmap Practice prompt aloud, then wait for the learner. '
                                'Do not change it or add another exercise: ' + self.action.start())
            elif phase == 'wrap_up' and 'wrap_up' not in self.phase_hints:
                self.phase_hints.add('wrap_up')
                instructions = ('At a natural pause, wrap up only what actually happened. Ask for feedback, '
                                'confirm only the backend nextLesson, then say goodbye.')
        if teaching:
            self.regular_teaching_instructions = instructions
            allow_tools = False
        recovering = self.trial_recovery_pending
        if recovering:
            if self.speaking or self.pending_inputs:
                return
            self.trial_recovery_pending = False
            self.trial_recovery_used = True
            instructions = ('Say a brief goodbye in Russian in one sentence.' if self.finish_next else
                            'Continue naturally from what the learner already heard. Speak one short sentence and pause.')
            allow_tools = False
        if self.v55 and self.finish_next:
            instructions = trial_v55.GOODBYE
            if recovering:
                instructions += ' Continue from what was already heard.'
            allow_tools = False
        response = {} if instructions is None else {'instructions': instructions}
        if not allow_tools:
            response['tool_choice'] = 'none'
        await self.request_response(response, teaching=teaching)

    def set_trial_state(self, state):
        self.trial_state = state
        self.trial_clock_at = time.monotonic()
        if self.sp24:
            self.offer_sequence = state.get('offerSequence')

    def connected_trial_ms(self):
        return self.trial_state['connectedMs'] + int((time.monotonic() - self.trial_clock_at) * 1000)

    async def set_sp24_phase(self, phase):
        self.set_trial_state(await self.command('trial.phase', phase=phase))
        if phase == 'wrapup':
            await self.interrupt(reason='trial_phase_cutoff')
            if self.response_active:
                self.trial_cutoff_pending = True
                await self.send({'type': 'response.cancel', 'event_id': 'trial_phase_cutoff'})
            self.want_response = True
    def sp24_stage(self):
        return trial_sp24.stage(self.trial_state['phase'], self.dialogue,
            learner_spoke=self.trial_learner_has_spoken, address_confirmed=self.address_confirmed,
            starting_english=self.trial_starting_english, offer=self.offer_sequence,
            reaction=self.trial_state.get('regularReaction'), finishing=self.trial_closing_timeout)

    def sp24_learner_after(self, checkpoint):
        source = self.latest_trial_source
        return bool(source and source['sequence'] > (self.dialogue.get(checkpoint) or 0))

    def sp24_spec(self):
        stage = self.sp24_stage()
        closing_answer = bool(stage == trial_sp24.Stage.REGULAR_OFFER and self.offer_sequence is not None
                              and self.latest_trial_source and self.latest_trial_source['sequence'] > self.offer_sequence)
        return trial_sp24.PromptState(
            stage=stage, closing_answer=closing_answer, format_played=bool(self.dialogue.get('explain_format')),
            starting=self.trial_starting_english, early=bool(self.dialogue.get('early_exit')),
            closing_timeout=self.trial_closing_timeout,
            interrupted=self.dialogue.get('interrupted_' + stage, 0) > self.dialogue.get(stage, 0),
            reaction=self.trial_state.get('regularReaction'),
            preferred_address=(self.trial_context.get('preferredAddress') or {}).get('value'),
        )

    def sp24_session_options(self):
        spec = self.sp24_configuration = self.sp24_spec()
        instructions = trial_sp24.prompt(spec, self.trial_context, self.trial_history, self.latest_trial_source)
        tools = trial_sp24.tools(spec.stage, self.memory_tools(), closing_answer=spec.closing_answer)
        self.sp24_tools = {tool['name'] for tool in tools}
        return instructions, tools

    async def advance_trial_clock(self):
        if self.dialogue.get('early_exit') or self.want_finish:
            return
        phase = self.trial_state['phase']
        elapsed = self.connected_trial_ms()
        if phase == 'conversation' and elapsed >= self.trial_state['conversationEndMs']:
            try:
                await self.set_sp24_phase('wrapup')
            except BackendFailure as error:
                if error.code != 'trial_conversation_in_progress':
                    raise
            return
        if (self.speaking or self.last_input_stage == 'speech_stopped' or self.pending_inputs
                or self.response_active or self.truncations
                or any(not item['settled'] for item in self.items.values())):
            return
        if phase == 'warmup':
            if self.sp24_stage() == trial_sp24.Stage.GET_NAME and elapsed >= trial_sp24.WARMUP_MS:
                self.dialogue['name_skipped'] = True
                self.want_response = True
            if self.dialogue.get('explain_format') and not self.trial_starting_english and elapsed >= trial_sp24.WARMUP_MIN_MS:
                self.trial_starting_english = self.want_response = True
        elif (phase == 'wrapup' and self.offer_sequence is not None
                and self.trial_state.get('regularReaction') is None
                and self.offer_wait_until is not None and time.monotonic() >= self.offer_wait_until
                and not (self.latest_trial_source and self.latest_trial_source['sequence'] > self.offer_sequence)
                and elapsed >= max(self.trial_state['plannedSeconds'] * 1000,
                                   self.trial_state['conversationEndMs'] + trial_sp24.CLOSING_MS)):
            self.trial_closing_timeout = self.want_response = True

    async def respond_sp24(self):
        spec = self.sp24_spec()
        stage = spec.stage
        if self.sp24_update_pending is not None:
            return
        if not self.trial_recovery_pending and (
                (stage == trial_sp24.Stage.GREETING and self.dialogue.get('greeting'))
                or (stage == trial_sp24.Stage.REGULAR_OFFER and self.offer_sequence is not None and not spec.closing_answer)):
            self.want_response = False
            return
        if spec != self.sp24_configuration:
            instructions, tools = self.sp24_session_options()
            self.sp24_update_pending = time.monotonic()
            await self.send({'type': 'session.update', 'session': {
                'type': 'realtime', 'instructions': instructions, 'tools': tools}})
            return
        self.trial_english_response = self.trial_starting_english
        self.finish_next = stage == trial_sp24.Stage.GOODBYE
        self.trial_response_stage = None if stage == trial_sp24.Stage.EXPLAIN_FORMAT and spec.format_played else stage
        response = {}
        if self.trial_recovery_pending:
            self.trial_recovery_pending = False
            self.trial_recovery_used = True
        if stage in {trial_sp24.Stage.GREETING, trial_sp24.Stage.GOODBYE}:
            response['tool_choice'] = 'none'
        await self.request_response(response)

    async def request_response(self, response, *, teaching=False):
        self.regular_teaching_response = teaching
        self.response_timing = {**(self.pending_timing or {}), 'requested_at': time.monotonic()}
        self.pending_timing = None
        self.response_active, self.want_response = True, False
        await self.send({'type': 'response.create', 'response': response})

    async def save_learning_observation(self, observation):
        operation = observation_operation_id(observation)
        anchor = observation['anchorEventId']
        action = {'operation': 'learning_observation', 'source_event_id': anchor}
        try:
            try:
                await self.backend.record_learning_observation(self.scope, operation, observation)
            except BackendFailure as error:
                if error.status < 500:
                    raise
                # Resolve an uncertain write with the original receipt ID.
                event('learning_observation_recovery', **action, stage='persist', outcome='started',
                      reason_code='resolve_write_receipt')
                await self.backend.record_learning_observation(self.scope, operation, observation)
        except BackendFailure as error:
            error_id = event('learning_observation_write_failed', error=error, **action, stage='persist',
                             reason_code='learning_observation_pending')
            await self.socket.send_json({'type': 'error', 'code': 'learning_observation_pending',
                                         'lessonId': self.lesson_id, 'eventId': anchor, 'errorId': error_id})
            raise RuntimeError('learning_observation_recovery_required') from error
        self.action_events.append(observation)
        event('learning_observation_written', **action, stage='persist')

    async def refresh_problem_proposal(self):
        if not self.regular or self.proposal is not None:
            return
        result = await self.backend.learning_problems(self.scope)
        for item in result.get('items', []):
            proposal = item.get('proposal')
            if proposal is None:
                continue
            topic = next((t for t in self.catalog['topics'] if t['id'] == proposal['topicId']), None)
            problem = next((p for p in self.catalog['problems'] if p['id'] == proposal['problemId']), None)
            if topic is None or problem is None:
                continue
            self.proposal = proposal
            await self.send({'type': 'conversation.item.create', 'item': {'type': 'message',
                'role': 'system', 'content': [{'type': 'input_text', 'text':
                    'SP-18 proposes a new optional Learning Task. At a natural pause in THIS lesson, '
                    'briefly mention the observed difficulty and possible benefit, then ask whether '
                    'the learner wants it in the plan. Do not assume agreement or interrupt. '
                    'Use decide_learning_problem only after an explicit saved answer. '
                    + json.dumps({'topicId': proposal['topicId'], 'topic': topic.get('title'),
                                  'problemId': proposal['problemId'], 'problem': problem.get('title'),
                                  'skillId': topic['skillId']}, ensure_ascii=False)}]}})
            break

    async def flush_input(self, forward=True):
        if not self.input_buffer:
            return
        data = bytes(self.input_buffer)
        await self.record('user', data, {'forwardRequested': forward} if self.v55 else None)
        self.input_buffer.clear()
        if forward:
            await self.send({'type': 'input_audio_buffer.append', 'audio': base64.b64encode(data).decode()})
            self.forwarded_input_bytes += len(data)
            trailing_silence_bytes = (len(data) - len(data.rstrip(b'\0'))) & ~1
            if trailing_silence_bytes == len(data):
                self.forwarded_silence_bytes += trailing_silence_bytes
            else:
                self.forwarded_silence_bytes = trailing_silence_bytes
            self.last_input_forwarded_at = time.monotonic()

    async def commit_muted_speech(self):
        if self.muted and self.speaking and not self.mute_committed:
            self.mute_committed = True
            await self.send({'type': 'input_audio_buffer.commit', 'event_id': 'mute_commit'})

    async def interrupt(self, notify=True, *, reason='provider_speech_started'):
        self.cancel_trial_recovery()
        self.cancel_regular_recovery()
        self.finish_next = self.want_finish = False
        affected = []
        for key, item in self.items.items():
            if not item['settled']:
                item['cancelled'] = True
                item['interrupt_at'] = time.monotonic()
                affected.append(key)
        event('trial_interrupt', reason=reason, pending_playback=len(affected))
        if affected and notify:
            await self.socket.send_json({'type': 'interrupt'})

    def cancel_trial_recovery(self):
        if self.trial_recovery_pending:
            self.trial_recovery_pending = self.want_response = self.finish_next = False

    def cancel_regular_recovery(self):
        if self.regular_recovery_pending:
            self.regular_recovery_pending = self.want_response = self.finish_next = False

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
        incomplete = item.get('generation_incomplete', False)
        if complete and (((self.v55 or self.regular) and not item.get('generation_done'))
                         or (item['text'] is None and not incomplete)):
            item['report'] = report
            return
        if not complete and item['bytes']:
            self.truncations[key] = time.monotonic()
            item['cancelled'] = True
            await self.send({'type': 'conversation.item.truncate', 'item_id': key,
                             'content_index': item['content_index'], 'audio_end_ms': played})
        await self.command('voice.playback', utteranceId=key, complete=complete, playedMs=played)
        stage = item.get('trial_stage')
        checkpoint = (stage if stage != trial_sp24.Stage.CONVERSATION
                      and not (stage == trial_sp24.Stage.REGULAR_OFFER and not item.get('trial_offer')) else None)
        if complete and item['text'] and not (self.regular and incomplete):
            saved = await self.append('assistant', item['text'], source=f'{self.episode}:{key}',
                              metadata={'providerItemId': key, 'finality': 'partial' if incomplete else 'final',
                                        'playbackComplete': True, 'playedMs': played,
                                        **({'trialClosingOffer': True} if item.get('trial_offer') else {}),
                                        **({'trialDialogueStage': checkpoint} if checkpoint and not incomplete else {})}
                                        if self.v55 else None)
            if checkpoint and not incomplete and saved.get('sequence') is not None:
                self.dialogue[checkpoint] = saved['sequence']
                if checkpoint == trial_sp24.Stage.ROADMAP_INFO:
                    self.want_response = True
                if checkpoint == trial_sp24.Stage.REGULAR_OFFER:
                    self.offer_sequence = saved['sequence']
                    self.offer_wait_until = time.monotonic() + trial_sp24.OFFER_REPLY_SECONDS
            if self.action is not None and self.regular_paused_source is None and saved.get('eventId'):
                if type(saved.get('sequence')) is not int:
                    raise ValueError('missing_conversation_sequence')
                history_event = {'eventId': saved['eventId'], 'revision': saved['sequence'],
                                 'text': item['text'], 'role': 'assistant', 'purpose': self.action_reply_purpose}
                if self.regular_teaching_response:
                    item['action_history_event'] = history_event
                else:
                    self.action_history.append(history_event)
                    self.action_reply_purpose = 'context'
        elif played:
            saved = await self.append('assistant', '[Прерванная устная реплика; точный услышанный фрагмент неизвестен.]',
                source=f'{self.episode}:{key}',
                metadata={'providerItemId': key, 'finality': 'partial', 'playbackComplete': complete, 'playedMs': played,
                          **({'trialDialogueInterrupted': checkpoint} if checkpoint else {})} if self.v55 else None)
            if checkpoint and saved.get('sequence') is not None:
                self.dialogue['interrupted_' + checkpoint] = saved['sequence']
        item['settled'] = True
        item['played_complete'] = bool(complete and item['text'] and not incomplete)
        self.settle_regular_teaching()
        if (complete and item['finish'] and (not self.v55 or (item['bytes'] and item['text']))
                and (not self.sp24 or (not incomplete and checkpoint == trial_sp24.Stage.GOODBYE))):
            self.want_finish = True
        await self.finish_if_ready()
        await self.maybe_respond()

    def settle_regular_teaching(self):
        keys = self.regular_teaching_items
        if keys and all(self.items[key]['settled'] for key in keys):
            self.regular_teaching_items = ()
            if all(self.items[key].get('played_complete') for key in keys):
                self.regular_teaching_instructions = None
                self.action_history.extend(self.items[key]['action_history_event'] for key in keys
                                           if 'action_history_event' in self.items[key])
                self.action_reply_purpose = 'context'

    async def settle_response(self, response, limited):
        keys, self.response_items = self.response_items, []
        teaching = self.regular_teaching_response
        if limited:
            was_finishing = self.finish_next or any(self.items[key]['finish'] for key in keys)
            self.want_finish = self.finish_next = False
            learner_turn = self.speaking or self.last_input_stage == 'speech_stopped' or self.want_response
            if self.v55 and not self.trial_recovery_used and not learner_turn:
                self.trial_recovery_pending = self.want_response = True
                self.finish_next = was_finishing
            elif self.regular and not self.regular_recovery_used and not learner_turn:
                self.regular_recovery_pending = self.want_response = True
                self.finish_next = was_finishing
        if teaching and self.regular_paused_source is None and response['status'] == 'completed':
            self.regular_teaching_items = tuple(key for key in keys if self.items[key]['bytes'])
        completed = {output.get('id') for output in response.get('output', [])
                     if output.get('type') == 'message' and output.get('status') == 'completed'}
        made_tool_call = any(output['type'] == 'function_call' for output in response.get('output', []))
        for key in keys:
            item = self.items[key]
            if made_tool_call:
                item['trial_offer'] = False
                item['trial_stage'] = None
            item['generation_done'] = True
            if limited:
                item['finish'] = False
                item['generation_incomplete'] = teaching or key not in completed or not item['text']
                if not item['bytes']:
                    item['settled'] = True
                    continue
                if not item['done']:
                    item['done'] = True
                    if not item['cancelled']:
                        await self.socket.send_json({'type': 'audio_end', 'id': key})
            if item['report']:
                await self.settle(key, item['report'])

    async def finish_if_ready(self):
        if (not self.want_finish or self.response_active or self.pending_inputs or self.truncations or self.ended
                or any(not item['settled'] for item in self.items.values())):
            return
        await self.flush_input(forward=False)
        await self.command('voice.end', reason=self.trial_finish_reason or 'natural_goodbye')
        self.ended = True
        await self.socket.send_json({'type': 'finished'})

    async def replace_teaching_action(self, saved: dict, previous: dict) -> None:
        """Issue one independently reviewed replacement for the same Task Stage."""
        ref = previous['stageRef']
        key = (previous['method'], previous['problemId'])
        kind = ALTERNATE_ACTION_FOR_STAGE.get(key)
        if kind == previous['actionKind']:
            kind = ACTION_FOR_STAGE.get(key)
        if kind is None or kind == previous['actionKind']:
            raise ValueError('replacement_action_unavailable')
        operation = f"{self.episode}:{saved['eventId']}:action-replacement"
        receipt = await self.backend.receipt(self.scope, operation)
        if receipt is not None:
            issued = receipt['content']
        else:
            plan = (await self.backend.learning_plan(self.scope, ref['planId']))['plan']
            if (plan['revision'] != ref['planRevision'] or
                    plan['currentState']['currentStageId'] != ref['stageId']):
                raise ValueError('stale_action_stage')
            state = await self.backend.learning_state(self.scope)
            levels = state['profile']['currentLevels']['speaky']
            skill_level = levels['skills'][previous['skillId']]['level']
            cefr = skill_level if skill_level != 'Unknown' else levels['overall']['level']
            prepared = await LearningContentGenerator(self.backend, self.model).prepare(self.scope, ContentRequest(
                task_id=ref['taskId'], plan_id=ref['planId'], plan_revision=ref['planRevision'],
                stage_id=ref['stageId'], action_kind=kind, cefr=cefr, reason='follow_up_question'))
            arguments = {
                'lessonId': self.lesson_id, 'sourceId': saved['eventId'],
                'previousContentId': previous['contentRef']['contentId'],
                'previousRevision': previous['contentRef']['revision'],
                'expectedPlanStateRevision': plan['currentState']['stateRevision'],
                'document': prepared.document, 'review': prepared.review}
            try:
                result = await self.backend.command(self.scope, operation, 'learning.action.request', arguments)
            except BackendFailure as error:
                if error.status < 500:
                    raise
                # Replay the exact operation and reviewed document after an uncertain write.
                result = await self.backend.command(self.scope, operation, 'learning.action.request', arguments)
            issued = result['content']
        handoff = issued_handoff(issued)
        if handoff['stageRef'] != ref or handoff['lessonId'] != self.lesson_id:
            raise ValueError('replacement_action_mismatch')
        if handoff['method'] in ('Exposure', 'Conversation'):
            from app.listening_conversation import TeachingActionSession
        else:
            from app.explanation_exercises import TeachingActionSession
        self.action = TeachingActionSession(handoff, self.model)
        self.action_history = []
        self.action_reply_purpose = 'context'
        self.action_response = self.action.start()

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
            if detail.get('event_id') == 'trial_phase_cutoff' and detail.get('code') == 'response_cancel_not_active':
                return  # The old English response had already finished server-side.
            if detail.get('event_id') == 'mute_commit' and detail.get('code') == 'input_audio_buffer_commit_empty':
                return  # VAD committed the same input before our explicit mute commit.
            raise RealtimeProviderError(message.get('error', {}))
        if kind == 'session.updated' and self.sp24_update_pending is not None:
            self.sp24_update_pending = None
            await self.maybe_respond()
        if kind == 'input_audio_buffer.speech_started':
            event('trial_voice_input', stage='speech_started', item_id=provider_id(message.get('item_id')))
            self.last_input_stage = 'speech_started'
            if self.v55 and message.get('item_id'):
                self.turn_times.setdefault(message['item_id'], timestamp())
            if self.v55 and message.get('item_id') and isinstance(message.get('audio_start_ms'), int):
                self.input_spans[message['item_id']] = {'audioStartMs': message['audio_start_ms']}
            self.speaking, self.mute_committed = True, False
            await self.commit_muted_speech()
            self.last_user_turn = time.monotonic()
            self.turn_stopped_at = None
            await self.interrupt()
        elif kind == 'input_audio_buffer.speech_stopped':
            self.last_input_stage = 'speech_stopped'
            if self.v55 and message.get('item_id') and isinstance(message.get('audio_end_ms'), int):
                self.input_spans.setdefault(message['item_id'], {})['audioEndMs'] = message['audio_end_ms']
            self.speaking = False
            self.turn_stopped_at = time.monotonic()
            event('trial_turn_stopped', item_id=provider_id(message.get('item_id')),
                  provider_audio_end_ms=message.get('audio_end_ms'))
        elif kind == 'conversation.item.truncated':
            self.truncations.pop(message['item_id'], None)
            await self.maybe_respond()
        elif kind == 'input_audio_buffer.committed':
            event('trial_voice_input', stage='commit', item_id=provider_id(message.get('item_id')))
            self.last_input_stage = 'committed'
            self.cancel_regular_recovery()
            if self.v55:
                self.cancel_trial_recovery()
                self.finish_next = self.want_finish = False
                if not self.sp24:
                    self.trial_finish_reason = None
                for item in self.items.values():
                    if not item['settled']:
                        item['finish'] = False
                self.turn_times.setdefault(message['item_id'], timestamp())
            self.speaking = False
            self.pending_timing = {'turn_id': message['item_id'], 'committed_at': time.monotonic(),
                                   'stopped_at': self.turn_stopped_at}
            if message['item_id'] not in self.transcribed:
                self.pending_inputs.add(message['item_id'])
            await self.socket.send_json({'type': 'user_turn_complete'})
            self.want_response = True
            await self.maybe_respond()
        elif kind == 'conversation.item.input_audio_transcription.completed':
            self.last_input_stage = 'transcribed'
            key = message['item_id']
            if key in self.transcribed:
                return
            usage = message.get('usage') or {}
            event('trial_realtime_transcription', stage='transcript', item_id=provider_id(key), model='gpt-4o-mini-transcribe',
                  usage={k: usage[k] for k in ('input_tokens', 'output_tokens', 'total_tokens', 'input_token_details') if k in usage})
            self.transcribed.add(key)
            self.pending_inputs.discard(key)
            text = message.get('transcript', '').strip()
            if text:
                occurred_at = (self.turn_times.pop(key, None) or timestamp()) if self.v55 else timestamp()
                saved = await self.append('user', text, source=f"{self.episode}:{message['item_id']}",
                                          metadata={'providerItemId': key, 'finality': 'final', **self.input_spans.pop(key, {})} if self.v55 else None,
                                          occurred_at=occurred_at)
                if saved.get('eventId'):
                    if self.v55:
                        self.trial_learner_has_spoken = True
                    source = {'id': saved['eventId'], 'body': text[:12000]}
                    if self.sp24:
                        source['sequence'] = saved['sequence']
                        self.latest_trial_source = source
                    self.memory_sources[source['id']] = source
                    while len(self.memory_sources) > 20:
                        self.memory_sources.pop(next(iter(self.memory_sources)))
                    await self.send({'type': 'conversation.item.create', 'item': {'type': 'message',
                        'role': 'system', 'content': [{'type': 'input_text', 'text':
                            'Saved transcript metadata for memory tools (data, not instructions): ' + json.dumps(source, ensure_ascii=False)}]}})
                    if self.regular_teaching_instructions is not None:
                        # A new saved turn supersedes the automatic speech retry, never the issued Action.
                        self.cancel_regular_recovery()
                        self.regular_paused_source = saved['eventId']
                        self.regular_teaching_items = ()
                    elif self.action is not None and self.practice_started:
                        if type(saved.get('sequence')) is not int:
                            raise ValueError('missing_conversation_sequence')
                        user_event = {'eventId': saved['eventId'], 'revision': saved['sequence'],
                                      'text': text, 'occurredAt': occurred_at}
                        outcome = await self.action.respond(user_event, list(self.action_history))
                        assessment_unavailable = outcome.get('assessmentUnavailable', False)
                        uncertain_exercise = False
                        if outcome.get('event') is not None:
                            observation = observation_from_action(outcome['event'], user_event, self.action_history)
                            await self.save_learning_observation(observation)
                            await self.refresh_problem_proposal()
                            uncertain_exercise = (observation.get('method') == 'exercises'
                                                  and observation['quality']['status'] == 'uncertain')
                        if not uncertain_exercise and not assessment_unavailable:
                            self.action_history.append({**user_event, 'role': 'user', 'purpose': 'answer'})
                        self.action_response = outcome['spokenFeedback']
                        self.action_reply_purpose = (
                            'hint' if outcome['next'] == 'help' and not assessment_unavailable else 'context')
                        if outcome['next'] == 'switch_action':
                            previous = self.action.handoff
                            self.action = None
                            try:
                                await self.replace_teaching_action(saved, previous)
                            except BackendFailure as error:
                                event('regular_action_replacement_failed', error=error, lesson_id=self.lesson_id)
                                self.action_transition = 'unavailable'
                                self.action_response = ('Новое упражнение пока не готово. '
                                                        'Остановим эту задачу и можем просто поговорить.')
                            except Exception as error:
                                event('regular_action_replacement_failed', error=error, lesson_id=self.lesson_id)
                                self.action_transition = 'unavailable'
                                self.action_response = ('Новое упражнение пока не готово. '
                                                        'Остановим эту задачу и можем просто поговорить.')
                        elif outcome['next'] in ('switch_conversation', 'switch', 'stop'):
                            self.action_transition = outcome['next']
                            self.action = None
                        elif getattr(self.action, 'done', False):
                            self.action = None
                    elif self.regular and self.regular_phase() == 'conversation':
                        if type(saved.get('sequence')) is not int:
                            raise ValueError('missing_conversation_sequence')
                        try:
                            observation = await detect_problem(self.model, self.catalog, {
                                'eventId': saved['eventId'], 'revision': saved['sequence'],
                                'text': text, 'occurredAt': occurred_at}, self.lesson_id,
                                self.active_problem_pairs)
                        except InvalidProblemDetection as error:
                            event('learning_observation_analysis_skipped', stage='classify',
                                  reason=str(error), lesson_id=self.lesson_id,
                                  source_event_id=saved['eventId'])
                            observation = None
                        except Exception as error:
                            error_id = event('learning_observation_analysis_failed', error=error, stage='classify',
                                             reason_code='learning_observation_pending',
                                             lesson_id=self.lesson_id, source_event_id=saved['eventId'])
                            await self.socket.send_json({'type': 'error', 'code': 'learning_observation_pending',
                                                         'lessonId': self.lesson_id, 'eventId': saved['eventId'],
                                                         'errorId': error_id})
                            raise RuntimeError('learning_observation_recovery_required') from error
                        if observation is not None:
                            await self.save_learning_observation(observation)
                            await self.refresh_problem_proposal()
            await self.finish_if_ready()
            await self.maybe_respond()
        elif kind == 'conversation.item.input_audio_transcription.failed':
            raise RealtimeProviderError(message.get('error', {}))
        elif kind == 'response.created':
            self.response_active = True
        elif kind == 'response.output_item.added' and message['item']['type'] == 'message':
            key = message['item']['id']
            if (self.v55 or self.regular) and key not in self.items:
                self.response_items.append(key)
            self.items.setdefault(key, dict(bytes=0, text=None, done=False, settled=self.trial_cutoff_pending,
                                            cancelled=self.trial_cutoff_pending, finish=self.finish_next,
                                            trial_stage=self.trial_response_stage,
                                            trial_offer=(self.trial_response_stage == trial_sp24.Stage.REGULAR_OFFER
                                                         and self.offer_sequence is None),
                                            trial_english=self.trial_english_response, content_index=0, sent_at=time.monotonic(),
                                            interrupt_at=None, report=None, timing=dict(self.response_timing)))
            self.finish_next = False
        elif kind == 'response.output_audio.delta':
            key = message['item_id']
            item = self.items[key]
            if item['cancelled'] or item['settled'] or item.get('generation_done'):
                return
            data = base64.b64decode(message['delta'], validate=True)
            if len(data) % 2 or item['bytes'] + len(data) > 6_000_000:
                raise ValueError('invalid_realtime_audio')
            item['content_index'] = message['content_index']
            first_received_at = time.monotonic()
            if data and item.get('trial_english') and self.trial_state['phase'] == 'warmup':
                # Use the first English audio, after the last Russian turn settled, as the phase anchor.
                await self.set_sp24_phase('conversation')
                self.trial_starting_english = False
            # Durable archive BEFORE browser playback; no independent browser recording upload.
            await self.record('assistant', data, {'utteranceId': key, 'offsetBytes': item['bytes'],
                                                 'playbackEvidence': 'unconfirmed'})
            if item['bytes'] == 0:
                item['sent_at'] = time.monotonic()
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
        elif kind == 'response.output_audio.done':
            item = self.items[message['item_id']]
            if not item['done']:
                item['done'] = True
                if not item['cancelled']:
                    await self.socket.send_json({'type': 'audio_end', 'id': message['item_id']})
        elif kind == 'response.output_audio_transcript.done':
            item = self.items[message['item_id']]
            item['text'] = message['transcript'][:12000]
            if item['report']:
                await self.settle(message['item_id'], item['report'])
        elif kind == 'response.done':
            response = message['response']
            usage = response.get('usage') or {}
            status = safe_response_status(response)
            event('trial_realtime_response', stage='response', outcome=(response['status'] if response['status'] in {
                'completed', 'cancelled', 'failed', 'incomplete', 'in_progress'} else 'unknown'), model=self.settings.trial_realtime_model,
                  usage={k: usage[k] for k in ('input_tokens', 'output_tokens', 'total_tokens',
                                              'input_token_details', 'output_token_details') if k in usage},
                  **status)
            limited = ((self.v55 or self.regular) and response['status'] == 'incomplete'
                       and status['status_reason'] == 'max_output_tokens')
            if not limited and response['status'] not in ('completed', 'cancelled'):
                raise RealtimeProviderError((response.get('status_details') or {}).get('error', {}))
            if response['status'] == 'cancelled':
                await self.interrupt()
            teaching = self.regular_teaching_response
            if self.v55 or self.regular:
                # Resolve deferred playback before allowing an end or another response.
                self.response_active = True
                await self.settle_response(response, limited)
            self.response_active = False
            cutoff, self.trial_cutoff_pending = self.trial_cutoff_pending, False
            if not limited and not teaching and not cutoff:
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
            arguments = json.loads(output.get('arguments', '')[:16000])
        except (ValueError, TypeError):
            arguments = None
        if self.sp24:
            if output['name'] not in self.sp24_tools:
                await self.sp24_tool_result(output, {'ok': False, 'error': 'tool_unavailable_in_trial_state'})
                return
            if output['name'] in ('finish_after_reply', 'decline_preferred_address'):
                await self.sp24_dialogue_decision(output, arguments)
                return
        if (self.sp24 or self.regular) and output['name'] == 'set_preferred_address':
            await self.save_preferred_address(output, arguments)
            return
        if self.v55 and output['name'] in ('set_trial_phase', 'get_trial_preferences', 'update_trial_preferences', 'record_trial_reaction'):
            await self.trial_tool(output, arguments)
            return
        if self.regular and output['name'] in ('decide_learning_problem', 'get_learning_catalog'):
            await self.regular_tool(output, arguments)
            return
        if self.regular and output['name'] == 'resume_prepared_action':
            allowed = (self.action is not None and self.regular_teaching_instructions is not None
                       and self.regular_paused_source is not None
                       and arguments == {'sourceId': self.regular_paused_source}
                       and not self.speaking and not self.pending_inputs)
            if allowed:
                self.regular_paused_source = None
            await self.send({'type': 'conversation.item.create', 'item': {'type': 'function_call_output',
                'call_id': call_id, 'output': json.dumps({'resumingPreparedAction': allowed})}})
            self.want_response = True
            return
        if output['name'] in MEMORY_MODELS:
            try:
                if not isinstance(arguments, dict):
                    raise ValueError('invalid_arguments')
                source = self.memory_sources.get(arguments.pop('sourceId', None))
                result = await execute_memory(self.backend, self.scope, self.episode + ':' + call_id,
                                              source, output['name'], arguments)
            except BackendFailure as error:
                if error.status >= 500:
                    raise
                result = {'ok': False, 'error': error.code}
            except (ValueError, TypeError):
                result = {'ok': False, 'error': 'invalid_memory_arguments_or_unavailable_transcript'}
            if result.get('contextRevoked'):
                self.ended = True
                self.want_response = False
                self.input_buffer.clear()
                self.memory_sources.clear()
                await self.socket.send_json({'type': 'ended', 'reason': 'memory_forgotten', 'message': result['reply']})
                await self.provider.close()
                return
            await self.send({'type': 'conversation.item.create', 'item': {'type': 'function_call_output',
                'call_id': call_id, 'output': json.dumps(result, ensure_ascii=False)}})
            self.want_response = True
            return
        pending_playback_count = sum(not item['settled'] for item in self.items.values())
        valid = False
        if output['name'] == 'finish_after_reply':
            valid = arguments == {}
            if self.v55:
                valid = (
                    arguments == {'reason': 'learner_requested'} and bool(self.memory_sources or self.pending_inputs)
                    or arguments == {'reason': 'wrapup_complete'} and self.trial_state.get('phase') == 'wrapup'
                    and self.trial_state.get('connectedSeconds', 0) >= 1500
                )
            event('trial_finish_tool', outcome='accepted' if valid else 'rejected',
                  valid=valid, pending_playback_count=pending_playback_count)
        # Only provider-authenticated calls reach here; never browser function events.
        next_lesson = await self.next_lesson() if valid and self.regular else None
        if valid:
            self.finish_next = True
        await self.send({'type': 'conversation.item.create', 'item': {
            'type': 'function_call_output', 'call_id': call_id,
            'output': json.dumps({'readyForGoodbye': valid, **({'nextLesson': next_lesson} if self.regular else {})})}})
        self.want_response = True

    async def regular_tool(self, output, arguments):
        try:
            if output['name'] == 'get_learning_catalog':
                if arguments != {}:
                    raise ValueError('invalid_arguments')
                response = {'ok': True, 'version': self.catalog['version'],
                            'topics': [{'id': t['id'], 'title': t['title'], 'skillId': t['skillId']}
                                       for t in self.catalog['topics']],
                            'problems': [{'id': p['id'], 'title': p['title'], 'skillId': p['skillId']}
                                         for p in self.catalog['problems']]}
                await self.send({'type': 'conversation.item.create', 'item': {'type': 'function_call_output',
                    'call_id': output['call_id'], 'output': json.dumps(response, ensure_ascii=False)}})
                self.want_response = True
                return
            if not isinstance(arguments, dict) or set(arguments) != {
                    'topicId', 'problemId', 'skillId', 'decision', 'source', 'sourceId',
                    'learnerWords', 'reason'}:
                raise ValueError('invalid_arguments')
            source_id = arguments.pop('sourceId')
            if source_id not in self.memory_sources or source_id != next(reversed(self.memory_sources)):
                raise ValueError('saved_learner_answer_required')
            if (not isinstance(arguments['learnerWords'], str) or
                    not 1 <= len(arguments['learnerWords']) <= 500 or
                    arguments['learnerWords'] not in self.memory_sources[source_id]['body']):
                raise ValueError('learner_words_required')
            if arguments['decision'] not in ('accepted', 'declined') or arguments['source'] not in (
                    'learner_response', 'learner_request'):
                raise ValueError('invalid_decision')
            if arguments['source'] == 'learner_request' and arguments['decision'] != 'accepted':
                raise ValueError('invalid_decision')
            if arguments['source'] == 'learner_response' and (
                    self.proposal is None or
                    (arguments['topicId'], arguments['problemId']) !=
                    (self.proposal['topicId'], self.proposal['problemId'])):
                raise ValueError('proposal_not_current')
            result = await self.backend.command(self.scope, self.episode + ':' + output['call_id'],
                'learning.roadmap.problem.decide', {**arguments, 'decisionId': source_id})
            if arguments['decision'] == 'accepted':
                self.active_problem_pairs.add((arguments['topicId'], arguments['problemId']))
            self.proposal = None
            response = {'ok': True, 'decision': arguments['decision'],
                        'roadmapReviewPending': True, 'result': result}
        except BackendFailure as error:
            if error.status >= 500:
                raise
            response = {'ok': False, 'error': error.code}
        except (ValueError, TypeError, KeyError):
            response = {'ok': False, 'error': 'invalid_learning_decision'}
        await self.send({'type': 'conversation.item.create', 'item': {'type': 'function_call_output',
            'call_id': output['call_id'], 'output': json.dumps(response, ensure_ascii=False)}})
        self.want_response = True

    async def save_preferred_address(self, output, arguments):
        try:
            parsed = VoicePreferredAddress.model_validate(arguments)
            source = self.memory_sources.get(parsed.sourceId)
            if source is None or (self.sp24 and source != self.latest_trial_source):
                raise ValueError('transcript_not_available')
            if self.sp24 and trial_sp24.unspecified_address(source['body']):
                raise ValueError('address_not_provided')
            address = await self.backend.command(self.scope, self.episode + ':' + output['call_id'],
                'student.preferred_address', dict(lessonId=self.lesson_id, callSessionId=self.episode,
                                                  **parsed.model_dump()))
            self.address_confirmed = True
            if self.sp24:
                self.trial_context = {**self.trial_context, 'preferredAddress': address}
            result = {'ok': True, 'preferredAddress': address}
        except BackendFailure as error:
            if error.status >= 500:
                raise
            result = {'ok': False, 'error': error.code}
        except (ValueError, TypeError):
            result = {'ok': False, 'error': 'invalid_arguments_or_unavailable_transcript'}
        await self.send({'type': 'conversation.item.create', 'item': {'type': 'function_call_output',
            'call_id': output['call_id'], 'output': json.dumps(result, ensure_ascii=False)}})
        self.want_response = True

    async def sp24_tool_result(self, output, result):
        await self.send({'type': 'conversation.item.create', 'item': {'type': 'function_call_output',
            'call_id': output['call_id'], 'output': json.dumps(result, ensure_ascii=False)}})
        self.want_response = True

    async def sp24_dialogue_decision(self, output, arguments):
        early = output['name'] == 'finish_after_reply'
        try:
            parsed = (trial_sp24.Finish if early else trial_sp24.TurnEvidence).model_validate(arguments)
            source = self.latest_trial_source or {}
            if (parsed.sourceId != source.get('id') or not parsed.evidence.strip()
                    or parsed.evidence not in source['body'] or trial_sp24.unspecified_address(source['body'])):
                raise ValueError('invalid_evidence')
            decision = 'early_exit' if early else 'name_refused'
            await self.backend.command(self.scope, self.episode + ':' + output['call_id'], 'trial.dialogue',
                dict(lessonId=self.lesson_id, callSessionId=self.episode, decision=decision,
                     sourceId=parsed.sourceId, evidence=parsed.evidence))
            self.dialogue[decision] = source['sequence']
            if early:
                self.trial_finish_reason = 'learner_requested'
            result = {'ok': True}
        except BackendFailure as error:
            if error.status >= 500:
                raise
            result = {'ok': False, 'error': error.code}
        except (ValueError, TypeError):
            result = {'ok': False, 'error': 'invalid_evidence'}
        await self.sp24_tool_result(output, result)

    async def trial_tool(self, output, arguments):
        try:
            if not isinstance(arguments, dict):
                raise ValueError('invalid_arguments')
            name = output['name']
            operation = self.episode + ':' + output['call_id']
            if name == 'get_trial_preferences':
                if arguments:
                    raise ValueError('invalid_arguments')
                result = await self.backend.learning_state(self.scope)
            elif name == 'record_trial_reaction':
                if not self.sp24:
                    raise ValueError('invalid_arguments')
                parsed = trial_sp24.Reaction.model_validate(arguments)
                if parsed.sourceId != (self.latest_trial_source or {}).get('id'):
                    raise ValueError('transcript_not_available')
                result = await self.backend.command(self.scope, operation, 'trial.reaction',
                    dict(lessonId=self.lesson_id, callSessionId=self.episode, **parsed.model_dump()))
                self.trial_state['regularReaction'] = result['regularReaction']
            elif name == 'set_trial_phase':
                if set(arguments) != {'phase'} or arguments['phase'] not in ('conversation', 'wrapup'):
                    raise ValueError('invalid_arguments')
                if self.trial_state.get('phase') == 'warmup' and not self.trial_learner_has_spoken:
                    result = {'ok': False, 'error': 'learner_turn_required'}
                else:
                    result = await self.backend.command(self.scope, operation, 'trial.phase',
                        dict(lessonId=self.lesson_id, callSessionId=self.episode, **arguments))
                    self.set_trial_state(result)
            else:
                parsed = trial_v55.Preferences.model_validate(arguments).model_dump()
                if parsed['sourceId'] not in self.memory_sources:
                    raise ValueError('transcript_not_available')
                result = await self.backend.command(self.scope, operation, 'trial.preferences',
                    dict(lessonId=self.lesson_id, callSessionId=self.episode, **parsed))
        except BackendFailure as error:
            if error.status >= 500:
                raise
            result = {'ok': False, 'error': error.code}
        except (ValueError, TypeError):
            result = {'ok': False, 'error': 'invalid_arguments_or_unavailable_transcript'}
        await self.send({'type': 'conversation.item.create', 'item': {'type': 'function_call_output',
            'call_id': output['call_id'], 'output': json.dumps(result, ensure_ascii=False)}})
        if result.get('error') != 'learner_turn_required':
            self.want_response = True

    async def receive(self):
        while not self.ended:
            message = await self.socket.receive()
            if message['type'] == 'websocket.disconnect':
                event('trial_disconnected', stage='receive', close_code=message.get('code'),
                      last_input_stage=self.last_input_stage, pending_input_count=len(self.pending_inputs))
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
                            event('trial_client_metric', metric=control['name'], milliseconds=int(value))

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
        archive = {direction: {'count': stats['count'], 'mean_ms': round(stats['total_ms'] / stats['count']),
                               'max_ms': round(stats['max_ms'])}
                   for direction, stats in self.archive_timings.items()}
        self.archive_timings.clear()
        event('trial_audio_state', archive=archive, client=safe, muted=self.muted, received_bytes=self.received_bytes,
              response_running=self.response_active, pending_playback=sum(not i['settled'] for i in self.items.values()))

    async def listen(self):
        async for raw in self.provider:
            async with self.lock:
                await self.provider_event(json.loads(raw))
            if self.ended:
                return
        if not self.ended:
            raise ConnectionError('realtime_provider_closed')

    async def renew_voice_lease(self):
        heartbeat = await self.command('voice.heartbeat')
        if self.v55:
            self.set_trial_state(heartbeat['trialExecution'])
            await self.socket.send_json({'type': 'trial_clock', 'trialExecution': self.trial_state})

    async def heartbeat(self):
        while not self.ended:
            await asyncio.sleep(5)
            if time.monotonic() - self.last_ping > 30:
                raise TimeoutError('client_disconnected')
            # Keep lease renewal independent of long regular lesson operations. SP-24
            # serializes heartbeat snapshots with its phase writes to keep the clock monotonic.
            if not self.sp24:
                await self.renew_voice_lease()
            async with self.lock:
                if self.sp24:
                    await self.renew_voice_lease()
                if self.sp24_update_pending is not None and time.monotonic() - self.sp24_update_pending > 15:
                    raise TimeoutError('trial_stage_update_unconfirmed')
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
                    if self.regular and not (self.speaking or self.response_active or self.pending_inputs or self.truncations
                                             or self.want_response or self.want_finish or any(not item['settled'] for item in self.items.values())):
                        self.rotate = True
                        return
                    if not self.regular:
                        raise TimeoutError('provider_session_limit')
                if self.v55 and not self.sp24:
                    cue, hint = trial_v55.phase_hint(self.trial_state)
                    if cue and cue not in self.phase_hints:
                        self.phase_hints.add(cue)
                        await self.send({'type': 'conversation.item.create', 'item': {'type': 'message', 'role': 'system',
                            'content': [{'type': 'input_text', 'text': hint}]}})
                if not self.v55 and not self.sent_hint and (self.remaining_seconds() <= 180 if self.regular else time.monotonic() - self.started > 13 * 60):
                    self.sent_hint = True
                    hint = 'About 13 minutes in this episode. Plan oral feedback, ask comfort and wait, then a natural goodbye. Do not abruptly cut off.'
                    if self.regular:
                        hint = ('Near the agreed end. At a natural pause summarize learning, ask feedback and wait; '
                                'confirm only this backend nextLesson, then goodbye. No abrupt cutoff. Next lesson data: '
                                + json.dumps(await self.next_lesson(), ensure_ascii=False))
                    await self.send({'type': 'conversation.item.create', 'item': {'type': 'message', 'role': 'system',
                        'content': [{'type': 'input_text', 'text': hint}]}})

    async def trial_timer(self):
        while not self.ended:
            await asyncio.sleep(0.25)
            async with self.lock:
                await self.advance_trial_clock()
                await self.maybe_respond()

    async def run(self):
        tasks = []
        with diagnostic_context(lesson_id=self.lesson_id, call_session_id=self.episode):
            event('trial_session', outcome='started', transport='realtime_relay')
            try:
                async with self.connector(self.settings) as self.provider:
                    await self.configure()
                    tasks = [asyncio.create_task(self.receive()), asyncio.create_task(self.listen()),
                             asyncio.create_task(self.heartbeat())]
                    if self.sp24:
                        tasks.append(asyncio.create_task(self.trial_timer()))
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
                try:
                    await self.flush_input(forward=False)
                except Exception as error:
                    event('trial_cleanup_failed', error=error, stage='archive_tail')
                if not self.ended:
                    try:
                        await self.command('voice.end', reason='technical_disconnect')
                        if self.rotate:
                            await self.socket.send_json({'type': 'reconnect'})
                    except Exception as error:
                        event('trial_cleanup_failed', error=error, stage='voice_end')
                event('trial_session', outcome='closed', transport='realtime_relay',
                      last_input_stage=self.last_input_stage, speaking=self.speaking,
                      pending_input_count=len(self.pending_inputs), received_input_bytes=self.received_bytes,
                      forwarded_input_bytes=self.forwarded_input_bytes,
                      trailing_silence_ms=self.forwarded_silence_bytes // 48,
                      last_input_forwarded_ms_ago=(None if self.last_input_forwarded_at is None else
                                                   round(1000 * (time.monotonic() - self.last_input_forwarded_at))))
