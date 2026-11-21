"""Lesson timing and completion rules, independent of sockets and audio storage."""
from app.trial_sp24 import CLOSING_MS, Stage, WARMUP_MIN_MS, WARMUP_MS


def regular_phase(remaining_seconds, duration_minutes):
    duration = duration_minutes * 60
    conversation = (15 if duration <= 30 * 60 else
                    20 if duration <= 45 * 60 else 25) * 60
    conversation = min(conversation, max(5 * 60, duration - 3 * 60))
    if remaining_seconds <= 2 * 60:
        return 'wrap_up'
    if remaining_seconds <= duration - conversation:
        return 'practice'
    return 'conversation'


def dialogue_checkpoint(stage, *, closing_offer):
    if stage == Stage.CONVERSATION or (stage == Stage.REGULAR_OFFER and not closing_offer):
        return None
    return stage


def goodbye_played(*, complete, finishing_reply, has_audio_and_text, incomplete, checkpoint, v55, sp24):
    if not complete or not finishing_reply:
        return False
    if v55 and not has_audio_and_text:
        return False
    return not sp24 or (not incomplete and checkpoint == Stage.GOODBYE)


def trial_warmup_progress(*, stage, format_played, starting_english, connected_ms):
    skip_name = stage == Stage.GET_NAME and connected_ms >= WARMUP_MS
    start_english = bool(format_played and not starting_english and connected_ms >= WARMUP_MIN_MS)
    return skip_name, start_english


def trial_offer_timed_out(state, *, offer_sequence, wait_until, latest_source, connected_ms, clock):
    return (offer_sequence is not None and state.get('regularReaction') is None
            and wait_until is not None and clock() >= wait_until
            and not (latest_source and latest_source['sequence'] > offer_sequence)
            and connected_ms >= max(state['plannedSeconds'] * 1000, state['conversationEndMs'] + CLOSING_MS))



def teaching_transition(next_action, *, action_done):
    if next_action in ('switch_action', 'switch_conversation', 'switch', 'stop'):
        return next_action
    return 'complete' if action_done else None
