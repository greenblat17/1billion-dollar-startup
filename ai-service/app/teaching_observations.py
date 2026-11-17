"""Map saved SP-14/15 action results to the SP-17A evidence contract."""
from __future__ import annotations

from datetime import datetime, timezone
from hashlib import sha256


def observation_from_action(proposal: dict, saved_turn: dict, prior_turns: list[dict]) -> dict:
    """Return a wire observation. IDs derive from the saved answer, never a retry."""
    answer_id = saved_turn['eventId']
    source = {'eventId': answer_id, 'revision': saved_turn['revision'],
              'role': 'user', 'purpose': 'answer'}
    if 'anchorEventId' in proposal:  # SP-14 already supplies the outcome classification.
        sources = proposal['sources']
        outcome, support = proposal['outcome'], proposal['support']
        initial = proposal['initialOutcome']
        quality = proposal['quality']
        slot = proposal['evidenceSlot']
        attempt = proposal['attemptId']
        route = proposal['route']
    else:  # SP-15's phase and decision are mapped without making a score claim.
        if proposal['answerSource'] != {'eventId': answer_id, 'revision': saved_turn['revision']}:
            raise ValueError('action_source_mismatch')
        quality_status = proposal['quality']
        support = proposal['independence']
        decision = proposal['decision']
        outcome = ('unresolved' if quality_status != 'usable' or decision in ('partial', 'uncertain')
                   else 'self_corrected_error' if decision == 'demonstrated' and support == 'independent'
                   and proposal.get('selfCorrected') and any(item.get('role') == 'user' and
                   item.get('purpose') == 'answer' for item in prior_turns)
                   else 'independent_correct' if decision == 'demonstrated' and support == 'independent'
                   else 'hinted_correct' if decision == 'demonstrated' and support == 'hinted'
                   else 'uncorrected_error' if decision == 'not_demonstrated'
                   else 'unresolved')
        if outcome == 'unresolved':
            support = 'unknown'
        initial = ('correct' if outcome == 'independent_correct' else
                   'error' if outcome in ('uncorrected_error', 'self_corrected_error') else 'unknown')
        signals = [] if quality_status == 'usable' else [{
            'reason': 'uncertain_asr' if decision == 'uncertain' else 'ambiguous_interpretation',
            'component': 'sp15.listening_conversation', 'sourceEventId': answer_id}]
        quality = {'status': quality_status, 'signals': signals}
        sources = [source]
        if outcome == 'self_corrected_error':
            previous = next(item for item in reversed(prior_turns)
                            if item.get('role') == 'user' and item.get('purpose') == 'answer')
            sources.append({'eventId': previous['eventId'], 'revision': previous['revision'],
                            'role': 'user', 'purpose': 'correction'})
        if support == 'hinted':
            hint = None
            for item in reversed(prior_turns):
                if item.get('role') == 'assistant' and item.get('purpose') == 'context':
                    break
                if item.get('role') == 'assistant' and item.get('purpose') == 'hint':
                    hint = item
                    break
            if hint is None:
                outcome, support, initial = 'unresolved', 'unknown', 'unknown'
                quality = {'status': 'uncertain', 'signals': [{
                    'reason': 'unknown_support', 'component': 'sp15.listening_conversation',
                    'sourceEventId': answer_id}]}
            else:
                sources.append({'eventId': hint['eventId'], 'revision': hint['revision'],
                                'role': 'assistant', 'purpose': 'hint'})
        slot = 'teaching_action:' + str(proposal.get('phase') or 'conversation') + ':' + str(proposal.get('segmentIndex'))
        attempt = saved_turn.get('attemptId') or answer_id
        route = 'stage_progress' if quality['status'] == 'usable' and outcome != 'unresolved' else 'unresolved'
    ref = proposal['contentRef']
    return {'schemaVersion': 1, 'attemptId': attempt, 'evidenceSlot': slot,
            'anchorEventId': answer_id, 'lessonId': proposal['lessonId'],
            'occurredAt': saved_turn.get('occurredAt') or datetime.now(timezone.utc).isoformat(),
            'sources': sources, 'catalogVersion': proposal['catalogVersion'],
            'skillId': proposal['skillId'], 'topicId': proposal['topicId'], 'problemId': proposal['problemId'],
            'practice': 'regular', 'method': proposal['method'], 'kind': 'demonstration',
            'outcome': outcome, 'support': support, 'initialOutcome': initial, 'quality': quality,
            'provenance': proposal.get('provenance') or {'component': 'sp15.listening_conversation', 'analyzerVersion': '1'},
            'taskId': proposal['taskId'],
            'stageRef': {key: proposal['stageRef'][key] for key in ('planId', 'planRevision', 'stageId')},
            'actionId': f"{ref['contentId']}:{ref['revision']}", 'repeatOf': None,
            'route': route, 'rationale': proposal['rationale']}


def observation_operation_id(observation: dict) -> str:
    identity = ':'.join((observation['lessonId'], observation['anchorEventId'], observation['evidenceSlot']))
    return 'teaching-observation:' + sha256(identity.encode()).hexdigest()
