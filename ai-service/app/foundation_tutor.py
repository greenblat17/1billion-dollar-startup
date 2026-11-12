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
from app.trial_tools import PreliminarySchedule, CancelPreliminarySchedule


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
        context = await self.contexts.build(scope, 'text')
        messages = [{'role': 'system', 'content': SYSTEM},
                    {'role': 'system', 'content': context.instructions('text')}]
        messages.append({'role': 'system', 'content': TEXT_CAPABILITIES})
        messages += context.history
        if start:
            messages.append({'role': 'user', 'content': 'Начать или продолжить наше общение.'})
        changed = None
        degraded = False
        try:
            committed = claim.get('committed', [])
            if committed:
                bookings = [operation['result'] for operation in committed if operation['action'].startswith('lesson.')]
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
                        if result.get('ok') and result.get('booking') and function['name'] != 'get_trial_booking':
                            changed = result['booking']
                        messages.append({'role': 'tool', 'tool_call_id': call['id'], 'content': json.dumps(result, ensure_ascii=False)})
                else:
                    raise ValueError('tool_round_limit')
        except BackendFailure:
            # Storage uncertainty must not be disguised as an ordinary conversational response.
            raise
        except Exception:
            degraded = True
            reply = booking_label(changed) if changed else 'Не удалось подготовить ответ. Напиши ещё раз, пожалуйста.'
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
        try:
            if name in ('save_preliminary_schedule', 'get_preliminary_schedules', 'cancel_preliminary_schedule'):
                if name == 'get_preliminary_schedules':
                    return await self.backend.command(scope, uuid4().hex, 'trial.schedules', {})
                schedule = (CancelPreliminarySchedule if name == 'cancel_preliminary_schedule' else PreliminarySchedule).model_validate(arguments)
                identity = hashlib.sha256(json.dumps([request_id, name, arguments], sort_keys=True).encode()).hexdigest()
                return await self.backend.command(scope, identity, 'trial.schedule_cancel' if name == 'cancel_preliminary_schedule' else 'trial.schedule', {'sourceId': source['id'], **schedule.model_dump()})
            if name not in MODELS:
                raise ValueError('unknown_tool')
            args = MODELS[name].model_validate(arguments)
            identity = hashlib.sha256(json.dumps([request_id, name, arguments], sort_keys=True).encode()).hexdigest()
            previous = await self.backend.receipt(scope, identity)
            if previous is not None:
                if name == 'update_learner_context':
                    return {'ok': True}
                booking = booking_projection(previous)
                return {'ok': True, 'booking': booking, 'confirmation': booking_label(booking)}
            snapshot = await self.backend.snapshot(scope, 'text')
            current = booking_projection(snapshot.get('booking'))
            if name == 'get_trial_booking':
                return {'ok': True, 'booking': current}
            if name == 'update_learner_context':
                await self.backend.command(scope, identity, 'tutor.update_context', {
                    'sourceId': source['id'], **args.model_dump(),
                })
                return {'ok': True}
            if getattr(args, 'evidence', '') not in source['body']:
                raise ValueError('invalid_evidence')
            command = {'sourceId': source['id'], 'evidence': args.evidence}
            if name != 'create_trial_booking':
                if not current or current['id'] != args.bookingId:
                    raise ValueError('booking_not_owned_or_not_active')
                command.update(lessonId=args.bookingId, expectedVersion=args.expectedVersion)
            if name == 'cancel_trial_booking':
                action = 'lesson.cancel'
            else:
                received = datetime.fromisoformat(source['occurred_at']).timestamp()
                starts, zone, timing = resolve_time(args.schedule, received, current, datetime.now(timezone.utc))
                command.update(startsAt=starts, timezone=zone, timing=timing)
                if name == 'create_trial_booking':
                    action = 'lesson.create'
                    command.update(kind='trial', launchMode='executable', durationMinutes=15, timing=timing)
                else:
                    action = 'lesson.reschedule'
                    command['launchMode'] = 'executable'
            lesson = await self.backend.command(scope, identity, action, command)
            booking = booking_projection(lesson)
            return {'ok': True, 'booking': booking, 'confirmation': booking_label(booking)}
        except BackendFailure as error:
            if error.status >= 500:
                raise
            return {'ok': False, 'error': error.code}
        except (ValueError, TypeError, KeyError) as error:
            return {'ok': False, 'error': str(error)[:300]}
