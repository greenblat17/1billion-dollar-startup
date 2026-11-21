"""Execute native voice tools against the live session and authoritative backend."""

import json

from app import trial_sp24, trial_v55
from app.diagnostics import event
from app.foundation_client import BackendFailure
from app.memory_tools import MEMORY_MODELS, execute_memory
from app.preferred_address import VoicePreferredAddress


async def execute_voice_tool(session, output):
    call_id = output['call_id']
    if call_id in session.tool_calls:
        return
    session.tool_calls.add(call_id)
    if len(session.tool_calls) > 100:
        raise ValueError('realtime_tool_limit')
    try:
        arguments = json.loads(output.get('arguments', '')[:16000])
    except (ValueError, TypeError):
        arguments = None
    if session.sp24:
        if output['name'] not in session.sp24_tools:
            await sp24_tool_result(session, output, {'ok': False, 'error': 'tool_unavailable_in_trial_state'})
            return
        if output['name'] in ('finish_after_reply', 'decline_preferred_address'):
            await sp24_dialogue_decision(session, output, arguments)
            return
    if (session.sp24 or session.regular) and output['name'] == 'set_preferred_address':
        await save_preferred_address(session, output, arguments)
        return
    if session.v55 and output['name'] in ('set_trial_phase', 'get_trial_preferences', 'update_trial_preferences', 'record_trial_reaction'):
        await trial_tool(session, output, arguments)
        return
    if session.regular and output['name'] in ('decide_learning_problem', 'get_learning_catalog'):
        await regular_tool(session, output, arguments)
        return
    if session.regular and output['name'] == 'resume_prepared_action':
        allowed = (session.action is not None and session.regular_teaching_instructions is not None
                   and session.regular_paused_source is not None
                   and arguments == {'sourceId': session.regular_paused_source}
                   and not session.speaking and not session.pending_inputs)
        if allowed:
            session.regular_paused_source = None
        await session.send({'type': 'conversation.item.create', 'item': {'type': 'function_call_output',
            'call_id': call_id, 'output': json.dumps({'resumingPreparedAction': allowed})}})
        session.want_response = True
        return
    if output['name'] in MEMORY_MODELS:
        try:
            if not isinstance(arguments, dict):
                raise ValueError('invalid_arguments')
            source = session.memory_sources.get(arguments.pop('sourceId', None))
            result = await execute_memory(session.backend, session.scope, session.episode + ':' + call_id,
                                          source, output['name'], arguments)
        except BackendFailure as error:
            if error.status >= 500:
                raise
            result = {'ok': False, 'error': error.code}
        except (ValueError, TypeError):
            result = {'ok': False, 'error': 'invalid_memory_arguments_or_unavailable_transcript'}
        if result.get('contextRevoked'):
            session.ended = True
            session.want_response = False
            session.audio.discard_input()
            session.memory_sources.clear()
            await session.socket.send_json({'type': 'ended', 'reason': 'memory_forgotten', 'message': result['reply']})
            await session.provider.close()
            return
        await session.send({'type': 'conversation.item.create', 'item': {'type': 'function_call_output',
            'call_id': call_id, 'output': json.dumps(result, ensure_ascii=False)}})
        session.want_response = True
        return
    pending_playback_count = sum(not item['settled'] for item in session.items.values())
    valid = False
    if output['name'] == 'finish_after_reply':
        valid = arguments == {}
        if session.v55:
            valid = (
                arguments == {'reason': 'learner_requested'} and bool(session.memory_sources or session.pending_inputs)
                or arguments == {'reason': 'wrapup_complete'} and session.trial_state.get('phase') == 'wrapup'
                and session.trial_state.get('connectedSeconds', 0) >= 1500
            )
        event('trial_finish_tool', outcome='accepted' if valid else 'rejected',
              valid=valid, pending_playback_count=pending_playback_count)
    # Only provider-authenticated calls reach here; never browser function events.
    next_lesson = await session.next_lesson() if valid and session.regular else None
    if valid:
        session.finish_next = True
    await session.send({'type': 'conversation.item.create', 'item': {
        'type': 'function_call_output', 'call_id': call_id,
        'output': json.dumps({'readyForGoodbye': valid, **({'nextLesson': next_lesson} if session.regular else {})})}})
    session.want_response = True

async def regular_tool(session, output, arguments):
    try:
        if output['name'] == 'get_learning_catalog':
            if arguments != {}:
                raise ValueError('invalid_arguments')
            response = {'ok': True, 'version': session.catalog['version'],
                        'topics': [{'id': t['id'], 'title': t['title'], 'skillId': t['skillId']}
                                   for t in session.catalog['topics']],
                        'problems': [{'id': p['id'], 'title': p['title'], 'skillId': p['skillId']}
                                     for p in session.catalog['problems']]}
            await session.send({'type': 'conversation.item.create', 'item': {'type': 'function_call_output',
                'call_id': output['call_id'], 'output': json.dumps(response, ensure_ascii=False)}})
            session.want_response = True
            return
        if not isinstance(arguments, dict) or set(arguments) != {
                'topicId', 'problemId', 'skillId', 'decision', 'source', 'sourceId',
                'learnerWords', 'reason'}:
            raise ValueError('invalid_arguments')
        source_id = arguments.pop('sourceId')
        if source_id not in session.memory_sources or source_id != next(reversed(session.memory_sources)):
            raise ValueError('saved_learner_answer_required')
        if (not isinstance(arguments['learnerWords'], str) or
                not 1 <= len(arguments['learnerWords']) <= 500 or
                arguments['learnerWords'] not in session.memory_sources[source_id]['body']):
            raise ValueError('learner_words_required')
        if arguments['decision'] not in ('accepted', 'declined') or arguments['source'] not in (
                'learner_response', 'learner_request'):
            raise ValueError('invalid_decision')
        if arguments['source'] == 'learner_request' and arguments['decision'] != 'accepted':
            raise ValueError('invalid_decision')
        if arguments['source'] == 'learner_response' and (
                session.proposal is None or
                (arguments['topicId'], arguments['problemId']) !=
                (session.proposal['topicId'], session.proposal['problemId'])):
            raise ValueError('proposal_not_current')
        result = await session.backend.command(session.scope, session.episode + ':' + output['call_id'],
            'learning.roadmap.problem.decide', {**arguments, 'decisionId': source_id})
        if arguments['decision'] == 'accepted':
            session.active_problem_pairs.add((arguments['topicId'], arguments['problemId']))
        session.proposal = None
        response = {'ok': True, 'decision': arguments['decision'],
                    'roadmapReviewPending': True, 'result': result}
    except BackendFailure as error:
        if error.status >= 500:
            raise
        response = {'ok': False, 'error': error.code}
    except (ValueError, TypeError, KeyError):
        response = {'ok': False, 'error': 'invalid_learning_decision'}
    await session.send({'type': 'conversation.item.create', 'item': {'type': 'function_call_output',
        'call_id': output['call_id'], 'output': json.dumps(response, ensure_ascii=False)}})
    session.want_response = True

async def save_preferred_address(session, output, arguments):
    try:
        parsed = VoicePreferredAddress.model_validate(arguments)
        source = session.memory_sources.get(parsed.sourceId)
        if source is None or (session.sp24 and source != session.latest_trial_source):
            raise ValueError('transcript_not_available')
        if session.sp24 and trial_sp24.unspecified_address(source['body']):
            raise ValueError('address_not_provided')
        address = await session.backend.command(session.scope, session.episode + ':' + output['call_id'],
            'student.preferred_address', dict(lessonId=session.lesson_id, callSessionId=session.episode,
                                              **parsed.model_dump()))
        session.address_confirmed = True
        if session.sp24:
            session.trial_context = {**session.trial_context, 'preferredAddress': address}
        result = {'ok': True, 'preferredAddress': address}
    except BackendFailure as error:
        if error.status >= 500:
            raise
        result = {'ok': False, 'error': error.code}
    except (ValueError, TypeError):
        result = {'ok': False, 'error': 'invalid_arguments_or_unavailable_transcript'}
    await session.send({'type': 'conversation.item.create', 'item': {'type': 'function_call_output',
        'call_id': output['call_id'], 'output': json.dumps(result, ensure_ascii=False)}})
    session.want_response = True

async def sp24_tool_result(session, output, result):
    await session.send({'type': 'conversation.item.create', 'item': {'type': 'function_call_output',
        'call_id': output['call_id'], 'output': json.dumps(result, ensure_ascii=False)}})
    session.want_response = True

async def sp24_dialogue_decision(session, output, arguments):
    early = output['name'] == 'finish_after_reply'
    try:
        parsed = (trial_sp24.Finish if early else trial_sp24.TurnEvidence).model_validate(arguments)
        source = session.latest_trial_source or {}
        if (parsed.sourceId != source.get('id') or not parsed.evidence.strip()
                or parsed.evidence not in source['body'] or trial_sp24.unspecified_address(source['body'])):
            raise ValueError('invalid_evidence')
        decision = 'early_exit' if early else 'name_refused'
        await session.backend.command(session.scope, session.episode + ':' + output['call_id'], 'trial.dialogue',
            dict(lessonId=session.lesson_id, callSessionId=session.episode, decision=decision,
                 sourceId=parsed.sourceId, evidence=parsed.evidence))
        session.dialogue[decision] = source['sequence']
        if early:
            session.trial_finish_reason = 'learner_requested'
        result = {'ok': True}
    except BackendFailure as error:
        if error.status >= 500:
            raise
        result = {'ok': False, 'error': error.code}
    except (ValueError, TypeError):
        result = {'ok': False, 'error': 'invalid_evidence'}
    await sp24_tool_result(session, output, result)

async def trial_tool(session, output, arguments):
    try:
        if not isinstance(arguments, dict):
            raise ValueError('invalid_arguments')
        name = output['name']
        operation = session.episode + ':' + output['call_id']
        if name == 'get_trial_preferences':
            if arguments:
                raise ValueError('invalid_arguments')
            result = await session.backend.learning_state(session.scope)
        elif name == 'record_trial_reaction':
            if not session.sp24:
                raise ValueError('invalid_arguments')
            parsed = trial_sp24.Reaction.model_validate(arguments)
            if parsed.sourceId != (session.latest_trial_source or {}).get('id'):
                raise ValueError('transcript_not_available')
            result = await session.backend.command(session.scope, operation, 'trial.reaction',
                dict(lessonId=session.lesson_id, callSessionId=session.episode, **parsed.model_dump()))
            session.trial_state['regularReaction'] = result['regularReaction']
        elif name == 'set_trial_phase':
            if set(arguments) != {'phase'} or arguments['phase'] not in ('conversation', 'wrapup'):
                raise ValueError('invalid_arguments')
            if session.trial_state.get('phase') == 'warmup' and not session.trial_learner_has_spoken:
                result = {'ok': False, 'error': 'learner_turn_required'}
            else:
                result = await session.backend.command(session.scope, operation, 'trial.phase',
                    dict(lessonId=session.lesson_id, callSessionId=session.episode, **arguments))
                session.set_trial_state(result)
        else:
            parsed = trial_v55.Preferences.model_validate(arguments).model_dump()
            if parsed['sourceId'] not in session.memory_sources:
                raise ValueError('transcript_not_available')
            result = await session.backend.command(session.scope, operation, 'trial.preferences',
                dict(lessonId=session.lesson_id, callSessionId=session.episode, **parsed))
    except BackendFailure as error:
        if error.status >= 500:
            raise
        result = {'ok': False, 'error': error.code}
    except (ValueError, TypeError):
        result = {'ok': False, 'error': 'invalid_arguments_or_unavailable_transcript'}
    await session.send({'type': 'conversation.item.create', 'item': {'type': 'function_call_output',
        'call_id': output['call_id'], 'output': json.dumps(result, ensure_ascii=False)}})
    if result.get('error') != 'learner_turn_required':
        session.want_response = True
