"""Choose the next tutor response after the connection is ready."""

from dataclasses import dataclass

from app import trial_sp24, trial_v55


FINISH_TOOL = {'type': 'function', 'name': 'finish_after_reply',
               'description': 'Prepare final goodbye after a natural wrap-up and opportunity to reply, or immediately on an explicit request to end.',
               'parameters': {'type': 'object', 'properties': {}, 'additionalProperties': False}}
RESUME_TEACHING_TOOL = {'type': 'function', 'name': 'resume_prepared_action',
                        'description': 'Resume the same paused Teaching Action only when the current learner turn explicitly asks to resume or repeat it.',
                        'parameters': {'type': 'object', 'properties': {'sourceId': {'type': 'string'}},
                                       'required': ['sourceId'], 'additionalProperties': False}}


@dataclass(frozen=True)
class ResponseDecision:
    response: dict | None
    teaching: bool = False
    used_regular_recovery: bool = False
    used_trial_recovery: bool = False
    consumed_feedback: bool = False
    started_practice: bool = False
    gave_wrap_up_hint: bool = False
    teaching_instructions: str | None = None


def choose_response(session, instructions, *, allow_tools):
    regular_recovery = session.regular_recovery_pending
    if session.regular and (regular_recovery or session.regular_teaching_instructions is not None):
        if session.speaking or session.pending_inputs:
            return
        if session.regular_paused_source is not None and not session.finish_next and not regular_recovery:
            instructions = (
                'The teaching message was interrupted. Respond directly to the latest learner turn. '
                'Respect a request to stop or change the activity. Do not assess this turn, replay the '
                'interrupted message, or improvise another exercise. Continue outside the paused Action. '
                'Use finish_after_reply if the learner wants to end the lesson. Call resume_prepared_action '
                'with the latest saved sourceId only if the learner explicitly asks to resume or repeat the same Action.'
            )
            tools = [FINISH_TOOL]
            if session.action is not None:
                tools.append(RESUME_TEACHING_TOOL)
            return ResponseDecision({'instructions': instructions, 'tools': tools})
        if (session.regular_teaching_instructions is not None and session.regular_paused_source is None
                and not session.finish_next):
            response = {'instructions': session.regular_teaching_instructions, 'tool_choice': 'none'}
            if regular_recovery:
                response['max_output_tokens'] = 2400
            return ResponseDecision(response, teaching=True, used_regular_recovery=regular_recovery)
        instructions = ('Say a short goodbye in the current language.' if session.finish_next else
                        'Continue naturally from what the learner already heard. Speak one short sentence and pause.')
        if session.regular_paused_source is not None:
            instructions += ' Do not resume the paused Teaching Action.'
        return ResponseDecision({'instructions': instructions, 'tool_choice': 'none'},
                                used_regular_recovery=regular_recovery)
    consumed_feedback = session.action_response is not None
    teaching = session.regular and consumed_feedback
    started_practice = gave_wrap_up_hint = False
    if consumed_feedback:
        if session.action_transition == 'switch_conversation':
            instructions = ('The learner chose free conversation outside the Task. Do not resume the old '
                            'Action, change Stage, or claim Progress. Say exactly: ' + session.action_response)
        elif session.action_transition == 'switch':
            instructions = 'Ask which activity the learner wants next. Do not resume the old Action or claim Progress.'
        elif session.action_transition == 'unavailable':
            instructions = ('The requested replacement could not be safely prepared or confirmed. '
                            'Tell the learner plainly and continue the call outside this Task. '
                            'Do not resume the old Action, invent an exercise, change Stage, or claim Progress. '
                            'Say exactly: ' + session.action_response)
        else:
            instructions = 'Say the exact prepared teaching message aloud, then pause. Do not add to it: ' + session.action_response
    if session.regular and not teaching:
        phase = session.regular_phase()
        if phase == 'practice' and session.action is not None and not session.practice_started:
            started_practice = True
            teaching = True
            instructions = ('Say the exact prepared Roadmap Practice prompt aloud, then wait for the learner. '
                            'Do not change it or add another exercise: ' + session.action.start())
        elif phase == 'wrap_up' and 'wrap_up' not in session.phase_hints:
            gave_wrap_up_hint = True
            instructions = ('At a natural pause, wrap up only what actually happened. Ask for feedback, '
                            'confirm only the backend nextLesson, then say goodbye.')
    teaching_instructions = instructions if teaching else None
    if teaching:
        allow_tools = False
    recovering = session.trial_recovery_pending
    if recovering:
        if session.speaking or session.pending_inputs:
            return ResponseDecision(None, consumed_feedback=consumed_feedback,
                                    started_practice=started_practice, gave_wrap_up_hint=gave_wrap_up_hint,
                                    teaching_instructions=teaching_instructions)
        instructions = ('Say a brief goodbye in Russian in one sentence.' if session.finish_next else
                        'Continue naturally from what the learner already heard. Speak one short sentence and pause.')
        allow_tools = False
    if session.v55 and session.finish_next:
        instructions = trial_v55.GOODBYE
        if recovering:
            instructions += ' Continue from what was already heard.'
        allow_tools = False
    response = {} if instructions is None else {'instructions': instructions}
    if not allow_tools:
        response['tool_choice'] = 'none'
    return ResponseDecision(response, teaching=teaching, used_trial_recovery=recovering,
                            consumed_feedback=consumed_feedback, started_practice=started_practice,
                            gave_wrap_up_hint=gave_wrap_up_hint, teaching_instructions=teaching_instructions)


def trial_waits_for_learner(spec, *, recovering, greeting_saved, offer_sequence):
    return not recovering and (
        (spec.stage == trial_sp24.Stage.GREETING and greeting_saved)
        or (spec.stage == trial_sp24.Stage.REGULAR_OFFER and offer_sequence is not None and not spec.closing_answer)
    )
