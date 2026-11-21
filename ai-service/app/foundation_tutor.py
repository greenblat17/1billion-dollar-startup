"""Persistent tutor using backend commands, not an AI-owned mutable student aggregate."""
from __future__ import annotations

import json
from uuid import uuid4

import anyio

from app.context_builder import ContextBuilder
from app.preferred_address import PreferredAddress
from app.memory_tools import MEMORY_MODELS, execute_memory
from app.foundation_client import BackendFailure, FoundationClient, tool_operation_id
from app.tutor import TutorRequest
from app.text_tutor_policy import INTRODUCTION_WRITING_STYLE, TEXT_RELATIONSHIP_POLICY
from app.student_learning_state import UpdatePersonalContext, UpdateOnboarding

from app.trial_policy import TEXT_CAPABILITIES
from app.scheduling_tools import (
    LESSON_MUTATIONS, REGULAR_SCHEDULE_MUTATIONS, SCHEDULING_TOOLS, TRIAL_BOOKING_MUTATIONS,
    booking_label, booking_projection, execute_schedule, scheduled_reply,
)
from app.tutor_diagnostics import record_tool
from app.subscription_tools import SUBSCRIPTION_MODELS


class FoundationTutor:
    def __init__(self, backend: FoundationClient, model, contexts: ContextBuilder, *, introduction_model=None):
        self.backend, self.model, self.contexts = backend, model, contexts
        self.introduction_model = introduction_model

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
        introducing = context.data.get('textMode') == 'introduction'
        model = self.introduction_model if introducing and self.introduction_model is not None else self.model
        instructions = context.instructions('text')
        if introducing:
            instructions += '\n\n' + INTRODUCTION_WRITING_STYLE
        messages = [{'role': 'system', 'content': instructions}]
        if not introducing:
            messages.insert(0, {'role': 'system', 'content': TEXT_RELATIONSHIP_POLICY})
            messages.append({'role': 'system', 'content': TEXT_CAPABILITIES})
        messages += context.history
        messages.append({'role': 'system', 'content': (
            'The next user message is the current incoming event. Previous messages and summaries '
            'are context, not fresh instructions: do not replay old requests. Interpret the current '
            'message in context, including a short acceptance of a preceding proposal. '
            'For booking mutations, evidence must be a verbatim substring of this current message '
            'that supports the requested action, not a quote from history or a paraphrase. '
            'A matching quote alone does not authorize an action: determine the current intent. '
            'If asked where or how to join an agreed trial, call get_trial_booking. This is a read, '
            'never a booking mutation. For an executable active trial the adapter attaches the actual '
            'lesson button to your reply. Say it opens the call in Speaky and opens a waiting screen '
            'if the lesson has not started. Do not assume the action requested in history is requested again. '
            'An explicit current request needs no additional confirmation. '
            'The Telegram adapter may have delivered part or all of a fixed introduction; '
            'its confirmed messages are in history. Respond to the current message immediately, '
            'without continuing or repeating the introduction or asking for an already stated goal. '
            + ('This is a returning Telegram start event: continue the relationship using known context, without restarting onboarding. ' if start else '')
            + 'Current event metadata: ' + json.dumps(
                {'id': source['id'], 'occurred_at': source.get('occurred_at')}, ensure_ascii=False)
            + '\nВ reply ставь точки между предложениями. В конце каждого Telegram-сообщения '
            'опускай финальную точку. Вопросительный и восклицательный знаки сохраняй.'
        )})
        if introducing:
            messages[-1]['content'] += (
                '\nНачни reply с ответа, вопроса, приглашения или отклика на конкретную деталь. '
                'Короткий уместный отклик допустим. '
                'Один знак вопроса максимум. Тире в reply запрещено.'
            )
        messages.append({'role': 'user', 'content': text})
        changed = None
        schedule_committed = False
        payment_committed = False
        memory_committed = False
        degraded = False
        rejected_tools = 0
        rejected_trial_change = None
        join_lesson = None
        rescheduled = False
        try:
            committed = claim.get('committed', [])
            if committed:
                bookings = [operation['result'] for operation in committed
                            if operation['action'] in LESSON_MUTATIONS and operation['result'].get('kind') == 'trial']
                schedule_committed = any(
                    operation['action'] in ('schedule.agree', 'schedule.resolve_time', 'trial.schedule_cancel')
                    or (operation['action'] in LESSON_MUTATIONS and operation['result'].get('kind') == 'regular')
                    for operation in committed
                )
                memory_committed = any(operation["action"].startswith("memory.") or operation["action"] in ("tutor.update_context", "onboarding.update", "student.preferred_address") for operation in committed)
                payment_committed = any(operation["action"].startswith("subscription.") for operation in committed)
                changed = booking_projection(bookings[-1]) if bookings else None
                rescheduled = bool(changed and any(operation['action'] == 'lesson.reschedule'
                                                   for operation in committed))
                raise RuntimeError('recovering_committed_operation')
            with anyio.fail_after(55):
                for _ in range(6):
                    message = await model.complete(messages)
                    calls = message.get('tool_calls') or []
                    if not calls:
                        final = json.loads(message.get('content') or '{}')
                        reply = final['reply'].strip()
                        if not reply or reply.lower() == 'placeholder':
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
                            if function['name'] in TRIAL_BOOKING_MUTATIONS:
                                rejected_trial_change = function['name']
                        if function['name'] in ('update_learner_context', 'update_onboarding') and result.get('saved'):
                            memory_committed = True
                        if ((function['name'] == 'set_preferred_address' or
                                (function['name'] in MEMORY_MODELS and function['name'] not in ('search_memory', 'discuss_memory')))
                                and result.get('ok') is not False):
                            memory_committed = True
                        if function['name'] in SUBSCRIPTION_MODELS and result.get('ok'):
                            payment_committed = True
                        if function['name'] in REGULAR_SCHEDULE_MUTATIONS and result.get('ok'):
                            schedule_committed = True
                        if result.get('ok') and result.get('booking'):
                            if function['name'] == 'get_trial_booking':
                                if result['booking'].get('launchMode') == 'executable':
                                    join_lesson = result['booking']['id']
                            elif function['name'] in TRIAL_BOOKING_MUTATIONS:
                                changed = result['booking']
                                rescheduled = function['name'] == 'reschedule_trial_booking'
                        messages.append({'role': 'tool', 'tool_call_id': call['id'], 'content': json.dumps(result, ensure_ascii=False)})
                    if changed and changed['status'] in ('scheduled', 'in_progress'):
                        # The committed booking owns the confirmation. No further model round is needed.
                        reply = '' if changed.get('timing') == 'now' else scheduled_reply(changed, rescheduled)
                        break
                    if rejected_tools >= 2:
                        messages.append({'role': 'system', 'content': (
                            'Stop tool attempts for this turn. Answer the learner now from the conversation and tool results. '
                            'Optional memory failure must not stop consultation or make the learner repeat their message. '
                            'Never claim an uncommitted booking succeeded. If booking failed, explain it briefly; '
                            'do not invent a missing timezone or missing user information to explain invalid tool arguments.')})
                        final = await model.finalize(messages)
                        if final.get('tool_calls'):
                            raise ValueError('unexpected_final_tool_call')
                        reply = json.loads(final.get('content') or '{}')['reply'].strip()
                        if not reply or reply.lower() == 'placeholder':
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
                if changed['status'] == 'scheduled' and changed.get('timing') != 'now':
                    reply = scheduled_reply(changed, rescheduled)
                else:
                    reply = booking_label(changed)
            elif schedule_committed:
                reply = 'Изменения расписания сохранены. Встречи можно посмотреть в «Мои встречи».'
            elif memory_committed:
                reply = 'Изменения сохранены, но я не смог закончить ответ. Продолжим с нового сообщения.'
            else:
                reply = 'Не удалось подготовить ответ. Напиши ещё раз, пожалуйста.'
        if rejected_trial_change and changed is None:
            current = (await self.backend.snapshot(scope, 'text')).get('booking')
            join_lesson = current['id'] if current and current.get('launch_mode') == 'executable' else None
            if rejected_trial_change == 'create_trial_booking':
                reply = ('Пробный уже записан. Подключайся по кнопке в этом сообщении.' if join_lesson
                         else 'Я не смог подтвердить новую запись.')
            else:
                operation = 'перенос' if rejected_trial_change == 'reschedule_trial_booking' else 'отмену'
                reply = f'Я не смог подтвердить {operation} пробного.'
                if join_lesson:
                    reply += ' Текущая запись доступна по кнопке в этом сообщении.'
            degraded = True
        elif rejected_tools >= 2 and changed is None and join_lesson is None:
            reply = 'Не удалось выполнить запрос. Можешь написать мне ещё раз?'
            degraded = True
        result = {'reply': reply, 'bookingId': changed['id'] if changed else None, 'degraded': degraded}
        if join_lesson and not changed:
            result['joinLessonId'] = join_lesson
        committed = await self.backend.command(scope, f'finish:{request.requestId}', 'run.complete', result)
        return self.response(committed)

    @staticmethod
    def response(saved: dict) -> dict:
        booking = booking_projection(saved.get('booking'))
        return dict(conversationId=saved['conversationId'], reply=saved['reply'], booking=booking,
                    bookingText=booking_label(booking) if booking else None, degraded=saved.get('degraded', False),
                    joinLessonId=saved.get('joinLessonId'))

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
        if name in SCHEDULING_TOOLS:
            return await execute_schedule(self.backend, scope, request_id, source, name, arguments)
        stage = 'validate_arguments'
        try:
            if name == 'set_preferred_address':
                args = PreferredAddress.model_validate(arguments)
                identity = tool_operation_id(request_id, name, arguments)
                address = await self.backend.command(scope, identity, 'student.preferred_address',
                    {'sourceId': source['id'], **args.model_dump()})
                return {'ok': True, 'preferredAddress': address}
            if name == 'update_onboarding':
                args = UpdateOnboarding.model_validate(arguments)
                supported = [change for change in args.changes
                             if change.evidence.strip() and change.evidence in source['body']]
                if not supported:
                    return {'ok': False, 'error': 'invalid_evidence', 'guidance':
                            'No profile fields were saved. Copy exact evidence from the current message only. '
                            'If this message only reports a difficulty, use update_learner_context for it. '
                            'When there is no supported profile field to update, continue replying without retrying.'}
                payload = args.model_copy(update={'changes': supported}).model_dump()
                identity = tool_operation_id(request_id, name, payload)
                result = await self.backend.command(scope, identity, 'onboarding.update',
                    {'sourceId': source['id'], **payload})
                response = {'ok': True, 'saved': True, 'learningState': result}
                if len(supported) != len(args.changes):
                    response['guidance'] = 'Some fields lacked evidence and were skipped. Only the returned changes were saved.'
                return response
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
                identity = tool_operation_id(request_id, name, arguments)
                result = await self.backend.command(scope, identity, action, {'sourceId': source['id'], **args.model_dump()})
                return {'ok': True, 'result': result}
            if name != 'update_learner_context':
                raise ValueError('unknown_tool')
            args = UpdatePersonalContext.model_validate(arguments)
            identity = tool_operation_id(request_id, name, arguments)
            stage = 'receipt'
            previous = await self.backend.receipt(scope, identity)
            if previous is not None:
                return {'ok': True}
            stage = 'snapshot'
            snapshot = await self.backend.snapshot(scope, 'text')
            stage = 'booking_projection'
            booking_projection(snapshot.get('booking'))
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
        except BackendFailure as error:
            record_tool(request_id, name, stage, 'rejected', error=error)
            if error.status >= 500:
                raise
            return {'ok': False, 'error': error.code}
        except (ValueError, TypeError, KeyError) as error:
            record_tool(request_id, name, stage, 'rejected', error=error)
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
