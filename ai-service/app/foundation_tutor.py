"""Persistent tutor using backend commands, not an AI-owned mutable student aggregate."""
from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from uuid import uuid4

import anyio

from app.context_builder import ContextBuilder
from app.foundation_client import BackendFailure, FoundationClient
from app.tutor import SYSTEM, TutorRequest
from app.tutor_tools import MODELS, booking_label as legacy_booking_label, resolve_time
from app.trial_policy import TEXT_CAPABILITIES
from app.trial_tools import PreliminarySchedule, CancelPreliminarySchedule, SCHEDULE_MODELS
from app.tutor_diagnostics import record_tool


def booking_label(booking):
    text = legacy_booking_label(booking)
    if booking and booking.get("launchMode") == "executable":
        text = text.replace("Запуск урока пока недоступен.", "")
    return text


def booking_projection(lesson: dict | None) -> dict | None:
    if not lesson:
        return None
    return dict(id=lesson['id'], learnerId=lesson['student_id'], status=lesson['status'],
                startsAtUtc=lesson['starts_at'], timezone=lesson['timezone'], version=lesson['version'],
                durationMinutes=lesson['duration_minutes'], timing=lesson.get('timing', 'scheduled'), launchMode=lesson.get('launch_mode', 'placeholder'))


class FoundationTutor:
    def __init__(self, backend: FoundationClient, model, contexts: ContextBuilder):
        self.backend, self.model, self.contexts = backend, model, contexts

    async def respond(self, request: TutorRequest, scope: str, start: bool = False) -> dict:
        claim = await self.backend.command(scope, 'claim:' + uuid4().hex, 'run.claim', {})
        if claim['status'] == 'ready':
            return self.response(claim['response'])
        source = claim['input']
        # The backend's accepted input is authoritative, not another body supplied by the caller.
        text = source['body']
        if text != request.text:
            raise BackendFailure('input_mismatch', 409)
        context = await self.contexts.build(scope, 'text', exclude_event_id=source['id'])
        messages = [{'role': 'system', 'content': SYSTEM},
                    {'role': 'system', 'content': context.instructions('text')}]
        messages.append({'role': 'system', 'content': TEXT_CAPABILITIES})
        messages += context.history
        messages.append({'role': 'system', 'content': (
            'The next user message is the current incoming event. Previous messages and summaries '
            'are context, not fresh instructions: do not replay old requests. Interpret the current '
            'message in context, including a short acceptance of a preceding proposal. '
            'For booking mutations, evidence must be a verbatim substring of this current message '
            'that supports the requested action, not a quote from history or a paraphrase. '
            'A matching quote alone does not authorize an action: determine the current intent. '
            'An explicit current request needs no additional confirmation. '
            + ('This is a Telegram start event: begin or continue the conversation. ' if start else '')
            + 'Current event metadata: ' + json.dumps(
                {'id': source['id'], 'occurred_at': source.get('occurred_at')}, ensure_ascii=False)
        )})
        messages.append({'role': 'user', 'content': text})
        changed = None
        schedule_committed = False
        degraded = False
        try:
            committed = claim.get('committed', [])
            if committed:
                bookings = [operation['result'] for operation in committed if operation['action'] in ('lesson.create', 'lesson.reschedule', 'lesson.cancel') and operation['result'].get('kind') == 'trial']
                schedule_committed = any(operation['action'] in ('schedule.agree', 'schedule.resolve_time', 'trial.schedule_cancel') or (operation['action'] in ('lesson.create', 'lesson.reschedule', 'lesson.cancel') and operation['result'].get('kind') == 'regular') for operation in committed)
                changed = booking_projection(bookings[-1]) if bookings else None
                raise RuntimeError('recovering_committed_operation')
            with anyio.fail_after(55):
                for _ in range(6):
                    message = await self.model.complete(messages)
                    calls = message.get('tool_calls') or []
                    if not calls:
                        final = json.loads(message.get('content') or '{}')
                        reply = final['reply'].strip()
                        if not reply:
                            raise ValueError('empty_reply')
                        break
                    if len(calls) > 5:
                        raise ValueError('tool_round_limit')
                    messages.append(message)
                    for call in calls:
                        function = call['function']
                        arguments = json.loads(function['arguments'])
                        result = await self.execute(scope, request.requestId, source, function['name'], arguments)
                        if function['name'] in SCHEDULE_MODELS and result.get('ok'):
                            schedule_committed = True
                        if result.get('ok') and result.get('booking') and function['name'] != 'get_trial_booking':
                            changed = result['booking']
                        messages.append({'role': 'tool', 'tool_call_id': call['id'], 'content': json.dumps(result, ensure_ascii=False)})
                else:
                    raise ValueError('tool_round_limit')
        except BackendFailure as error:
            record_tool(request.requestId, '', 'turn', 'failed', error=error)
            # Storage uncertainty must not be disguised as an ordinary conversational response.
            raise
        except Exception as error:
            record_tool(request.requestId, '', 'turn', 'degraded', error=error)
            degraded = True
            reply = booking_label(changed) if changed else ('Изменения расписания сохранены. Встречи можно посмотреть в «Мои встречи».' if schedule_committed else 'Не удалось подготовить ответ. Напиши ещё раз, пожалуйста.')
        committed = await self.backend.command(scope, f'finish:{request.requestId}', 'run.complete', {
            'reply': reply, 'bookingId': changed['id'] if changed else None, 'degraded': degraded,
        })
        return self.response(committed)

    @staticmethod
    def response(saved: dict) -> dict:
        booking = booking_projection(saved.get('booking'))
        return dict(conversationId=saved['conversationId'], reply=saved['reply'], booking=booking,
                    bookingText=booking_label(booking) if booking else None, degraded=saved.get('degraded', False))

    async def execute(self, scope: str, request_id: str, source: dict, name: str, arguments: dict) -> dict:
        record_tool(request_id, name, 'dispatch', 'started')
        try:
            result = await self._execute(scope, request_id, source, name, arguments)
        except Exception as error:
            record_tool(request_id, name, 'dispatch', 'failed', error=error)
            raise
        record_tool(request_id, name, 'dispatch', 'returned', result=result)
        return result

    async def _execute(self, scope: str, request_id: str, source: dict, name: str, arguments: dict) -> dict:
        stage = 'validate_arguments'
        try:
            if name in ('get_schedules', 'get_lessons'):
                if arguments:
                    raise ValueError('unexpected_arguments')
                return await self.backend.command(scope, uuid4().hex,
                    'trial.schedules' if name == 'get_schedules' else 'lesson.list', {})
            if name in SCHEDULE_MODELS:
                model, action = SCHEDULE_MODELS[name]
                args = model.model_validate(arguments)
                if args.evidence not in source['body']:
                    raise ValueError('invalid_evidence')
                command = {'sourceId': source['id'], **args.model_dump()}
                if name == 'create_extra_lesson':
                    command.update(kind='regular', launchMode='executable')
                identity = hashlib.sha256(json.dumps([request_id, name, arguments], sort_keys=True).encode()).hexdigest()
                result = await self.backend.command(scope, identity, action, command)
                return {'ok': True, 'result': result}
            if name in ('save_preliminary_schedule', 'get_preliminary_schedules', 'cancel_preliminary_schedule'):
                if name == 'get_preliminary_schedules':
                    return await self.backend.command(scope, uuid4().hex, 'trial.schedules', {})
                stage = 'validate_arguments'
                schedule = (CancelPreliminarySchedule if name == 'cancel_preliminary_schedule' else PreliminarySchedule).model_validate(arguments)
                identity = hashlib.sha256(json.dumps([request_id, name, arguments], sort_keys=True).encode()).hexdigest()
                stage = 'schedule_command'
                return await self.backend.command(scope, identity, 'trial.schedule_cancel' if name == 'cancel_preliminary_schedule' else 'trial.schedule', {'sourceId': source['id'], **schedule.model_dump()})
            if name not in MODELS:
                raise ValueError('unknown_tool')
            args = MODELS[name].model_validate(arguments)
            identity = hashlib.sha256(json.dumps([request_id, name, arguments], sort_keys=True).encode()).hexdigest()
            stage = 'receipt'
            previous = await self.backend.receipt(scope, identity)
            if previous is not None:
                if name == 'update_learner_context':
                    return {'ok': True}
                booking = booking_projection(previous)
                return {'ok': True, 'booking': booking, 'confirmation': booking_label(booking)}
            stage = 'snapshot'
            snapshot = await self.backend.snapshot(scope, 'text')
            stage = 'booking_projection'
            current = booking_projection(snapshot.get('booking'))
            if name == 'get_trial_booking':
                return {'ok': True, 'booking': current}
            if name == 'update_learner_context':
                await self.backend.command(scope, identity, 'tutor.update_context', {
                    'sourceId': source['id'], **args.model_dump(),
                })
                return {'ok': True}
            stage = 'validate_evidence'
            if getattr(args, 'evidence', '') not in source['body']:
                raise ValueError('invalid_evidence')
            command = {'sourceId': source['id'], 'evidence': args.evidence}
            stage = 'validate_booking'
            if name != 'create_trial_booking':
                if not current or current['id'] != args.bookingId:
                    raise ValueError('booking_not_owned_or_not_active')
                command.update(lessonId=args.bookingId, expectedVersion=args.expectedVersion)
            if name == 'cancel_trial_booking':
                action = 'lesson.cancel'
            else:
                stage = 'resolve_time'
                received = datetime.fromisoformat(source['occurred_at']).timestamp()
                starts, zone, timing = resolve_time(args.schedule, received, current, datetime.now(timezone.utc))
                command.update(startsAt=starts, timezone=zone, timing=timing)
                if name == 'create_trial_booking':
                    action = 'lesson.create'
                    command.update(kind='trial', launchMode='executable', durationMinutes=15, timing=timing)
                else:
                    action = 'lesson.reschedule'
                    command['launchMode'] = 'executable'
            stage = 'booking_command'
            lesson = await self.backend.command(scope, identity, action, command)
            stage = 'result_projection'
            booking = booking_projection(lesson)
            return {'ok': True, 'booking': booking, 'confirmation': booking_label(booking)}
        except BackendFailure as error:
            record_tool(request_id, name, stage, 'rejected', error=error)
            if error.status >= 500:
                raise
            if error.code.startswith('schedule_time_needs_clarification:'):
                return {'ok': False, 'error': error.code, 'guidance':
                    'No change committed. Explain that this local time is missing or occurs twice because clocks change. Ask for another local time for that date; do not guess UTC or claim a server outage.'}
            return {'ok': False, 'error': error.code}
        except (ValueError, TypeError, KeyError) as error:
            record_tool(request_id, name, stage, 'rejected', error=error)
            if stage == 'validate_evidence' and str(error) == 'invalid_evidence':
                return {'ok': False, 'error': 'invalid_evidence', 'guidance': (
                    'No action was performed: evidence is not a verbatim quote from the current '
                    'user message. This is invalid tool input, not a server outage. Reconsider '
                    'whether the current message requests this action. If it does, retry with '
                    'an exact supporting quote; otherwise continue the conversation without '
                    'booking. Do not ask the learner to repeat an already clear request.')}
            return {'ok': False, 'error': str(error)[:300]}
