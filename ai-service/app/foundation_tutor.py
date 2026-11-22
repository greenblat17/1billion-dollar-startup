"""Persistent tutor using backend commands, not an AI-owned mutable student aggregate."""
from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from uuid import uuid4

import anyio

from app.context_builder import ContextBuilder
from app.memory_tools import MEMORY_MODELS, execute_memory
from app.foundation_client import BackendFailure, FoundationClient
from app.tutor import TutorRequest
from app.text_tutor_policy import TEXT_RELATIONSHIP_POLICY
from app.tutor_tools import MODELS, resolve_time, zone
from app.trial_policy import TEXT_CAPABILITIES
from app.trial_tools import PreliminarySchedule, CancelPreliminarySchedule, SCHEDULE_MODELS
from app.tutor_diagnostics import record_tool
from app.subscription_tools import SUBSCRIPTION_MODELS


def booking_label(booking):
    if booking['status'] == 'cancelled':
        return 'Пробное занятие отменено.\nЕсли добавлял его в календарь, удали событие там.'
    local = datetime.fromisoformat(booking['startsAtUtc']).astimezone(zone(booking['timezone']))
    weekdays = ('понедельник', 'вторник', 'среда', 'четверг', 'пятница', 'суббота', 'воскресенье')
    months = ('января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря')
    return (f"Пробное занятие · бесплатно\n"
            f"{local.day} {months[local.month - 1]} {local.year}, {weekdays[local.weekday()]}\n"
            f"{local:%H:%M} · {booking['timezone']} · {booking['durationMinutes']} минут\n"
            + ('Звонок в Speaky' if booking.get('launchMode') == 'executable' else 'Подключение пока недоступно'))


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
        messages = [{'role': 'system', 'content': TEXT_RELATIONSHIP_POLICY},
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
            'The Telegram adapter may have delivered part or all of a fixed introduction; '
            'its confirmed messages are in history. Respond to the current message immediately, '
            'without continuing or repeating the introduction or asking for an already stated goal. '
            + ('This is a returning Telegram start event: continue the relationship using known context, without restarting onboarding. ' if start else '')
            + 'Current event metadata: ' + json.dumps(
                {'id': source['id'], 'occurred_at': source.get('occurred_at')}, ensure_ascii=False)
        )})
        messages.append({'role': 'user', 'content': text})
        changed = None
        schedule_committed = False
        payment_committed = False
        memory_committed = False
        degraded = False
        rejected_tools = 0
        try:
            committed = claim.get('committed', [])
            if committed:
                bookings = [operation['result'] for operation in committed if operation['action'] in ('lesson.create', 'lesson.reschedule', 'lesson.cancel') and operation['result'].get('kind') == 'trial']
                schedule_committed = any(operation['action'] in ('schedule.agree', 'schedule.resolve_time', 'trial.schedule_cancel') or (operation['action'] in ('lesson.create', 'lesson.reschedule', 'lesson.cancel') and operation['result'].get('kind') == 'regular') for operation in committed)
                memory_committed = any(operation["action"].startswith("memory.") or operation["action"] == "tutor.update_context" for operation in committed)
                payment_committed = any(operation["action"].startswith("subscription.") for operation in committed)
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
                        if result.get('contextRevoked'):
                            return dict(conversationId=result['conversationId'], reply=result['reply'],
                                        booking=None, bookingText=None, degraded=False)
                        if result.get('ok') is False or (result.get('skipped') and not result.get('saved')):
                            rejected_tools += 1
                        if function['name'] == 'update_learner_context' and result.get('saved'):
                            memory_committed = True
                        if function['name'] in MEMORY_MODELS and function['name'] not in ('search_memory', 'discuss_memory') and result.get('ok') is not False:
                            memory_committed = True
                        if function['name'] in SUBSCRIPTION_MODELS and result.get('ok'):
                            payment_committed = True
                        if function['name'] in SCHEDULE_MODELS and result.get('ok'):
                            schedule_committed = True
                        if result.get('ok') and result.get('booking') and function['name'] != 'get_trial_booking':
                            changed = result['booking']
                        messages.append({'role': 'tool', 'tool_call_id': call['id'], 'content': json.dumps(result, ensure_ascii=False)})
                    if changed and changed.get('timing') == 'now' and changed['status'] in ('scheduled', 'in_progress'):
                        # The durable invitation is the response; no extra model round or goodbye.
                        reply = ''
                        break
                    if rejected_tools >= 2:
                        messages.append({'role': 'system', 'content': (
                            'Stop tool attempts for this turn. Answer the learner now from the conversation and tool results. '
                            'Optional memory failure must not stop consultation or make the learner repeat their message. '
                            'Never claim an uncommitted booking succeeded. If booking failed, explain it briefly; '
                            'do not invent a missing timezone or missing user information to explain invalid tool arguments.')})
                        final = await self.model.finalize(messages)
                        if final.get('tool_calls'):
                            raise ValueError('unexpected_final_tool_call')
                        reply = json.loads(final.get('content') or '{}')['reply'].strip()
                        if not reply:
                            raise ValueError('empty_reply')
                        break
                else:
                    raise ValueError('tool_round_limit')
        except BackendFailure as error:
            record_tool(request.requestId, '', 'turn', 'failed', error=error)
            # Storage uncertainty must not be disguised as an ordinary conversational response.
            raise
        except Exception as error:
            record_tool(request.requestId, '', 'turn', 'degraded', error=error)
            degraded = True
            if payment_committed:
                reply = 'Запрос по подписке сохранён. Тестовая оплата не списывает деньги; статус можно уточнить здесь.'
            elif changed:
                reply = booking_label(changed)
            elif schedule_committed:
                reply = 'Изменения расписания сохранены. Встречи можно посмотреть в «Мои встречи».'
            elif memory_committed:
                reply = 'Изменения памяти сохранены. Продолжим разговор с нового сообщения.'
            else:
                reply = 'Не удалось подготовить ответ. Напиши ещё раз, пожалуйста.'
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
            if name in MEMORY_MODELS:
                return await execute_memory(self.backend, scope, request_id, source, name, arguments)
            if name == 'get_subscription':
                if arguments:
                    raise ValueError('unexpected_arguments')
                return await self.backend.command(scope, uuid4().hex, 'subscription.get', {})
            if name in SUBSCRIPTION_MODELS:
                model, action = SUBSCRIPTION_MODELS[name]
                args = model.model_validate(arguments)
                if args.evidence not in source['body']:
                    raise ValueError('invalid_evidence')
                identity = hashlib.sha256(json.dumps([request_id, name, arguments], sort_keys=True).encode()).hexdigest()
                result = await self.backend.command(scope, identity, action, {'sourceId': source['id'], **args.model_dump()})
                return {'ok': True, 'result': result}
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
                elif name == 'reschedule_lesson':
                    command['launchMode'] = 'executable'
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
                stage = 'update_context'
                payload, skipped = supported_context_update(args, source['body'])
                saved = bool(payload['facts'] or payload['trialPreference'] is not None or payload['summary'] is not None)
                if saved:
                    await self.backend.command(scope, identity, 'tutor.update_context', {
                        'sourceId': source['id'], **payload,
                    })
                return {'ok': True, 'saved': saved, 'skipped': skipped, 'guidance':
                    'Only supported fields were saved. Do not retry skipped optional memory in this turn. '
                    'Continue responding to the learner; do not ask them to repeat information. '
                    'Use null for trialPreference when the current message says nothing about trial readiness.'}
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
            if stage == 'resolve_time' and str(error) in ('conflicting_schedule_fields', 'current_booking_and_shift_required'):
                return {'ok': False, 'error': str(error), 'guidance':
                    'Invalid tool arguments, not a timezone problem. For N minutes from this message use '
                    'after_minutes with delayMinutes. shift_minutes requires an existing booking. '
                    'Use only fields belonging to the chosen mode; no change was committed.'}
            return {'ok': False, 'error': str(error)[:300]}


def supported_context_update(args, text):
    """Keep exact-source validation; omit unsupported optional fields instead of failing the whole turn."""
    payload = args.model_dump()
    facts = []
    skipped = []
    for index, fact in enumerate(payload['facts']):
        if fact['evidence'].strip() and fact['evidence'] in text:
            facts.append(fact)
        else:
            skipped.append(f'facts[{index}]: evidence must quote this message exactly')
    payload['facts'] = facts
    evidence = payload['preferenceEvidence']
    if payload['trialPreference'] is not None and (not evidence or not evidence.strip() or evidence not in text):
        skipped.append('trialPreference: no supporting quote in this message')
        payload['trialPreference'] = None
        payload['preferenceEvidence'] = None
    return payload, skipped
