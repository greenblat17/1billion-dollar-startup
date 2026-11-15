"""Personal-memory boundary for the active foundation runtime. No learning writer is exposed to the model."""
from typing import Literal

from pydantic import Field, field_validator

from app.tutor_tools import Strict, UpdateContext

LEARNING_KEYS = frozenset({
    'goal', 'goals', 'desired_result', 'level', 'self_assessment', 'current_level', 'target_level',
    'current_levels', 'target_cefr', 'current_cefr', 'deadline', 'intensity', 'homework',
    'homework_preferences', 'topic_knowledge', 'lessons_per_week', 'lesson_duration_minutes',
})


def personal_key(value: str) -> str:
    if value in LEARNING_KEYS:
        raise ValueError('learning_state_required')
    return value


class PersonalFact(Strict):
    memory: Literal['personal']
    key: str = Field(pattern=r'^[a-z][a-z_]{0,49}$', description='Personal facts only; not goals, CEFR, topic knowledge, intensity or homework preferences.')
    value: str | None = Field(max_length=1000)
    evidence: str = Field(min_length=1, max_length=2000)
    kind: Literal['user_report']

    _personal_key = field_validator('key')(personal_key)


class UpdatePersonalContext(UpdateContext):
    facts: list[PersonalFact] = Field(max_length=20)


def learning_context(snapshot: dict, budget: int) -> tuple[dict | None, dict | None]:
    """Bound prompt material without changing stored assessments; full evidence remains available through the API."""
    from copy import deepcopy
    import json

    state = deepcopy(snapshot.get('learningState'))
    if state is not None:
        provenance = state.pop('provenance', None)
        partial = bool(provenance)
        for group in state.get('profile', {}).get('currentLevels', {}).values():
            assessments = [group.get('overall', {}), *group.get('skills', {}).values()]
            for assessment in assessments:
                explanation = assessment.get('explanation')
                if isinstance(explanation, str) and len(explanation) > 240:
                    assessment['explanation'] = explanation[:240]
                    partial = True
        if partial:
            state['contextPartial'] = True
            state['detailsEndpoint'] = '/learning-state'
    topics = deepcopy(snapshot.get('topicKnowledge'))
    if topics is not None:
        selected = []
        remaining = budget
        for item in topics.get('items', []):
            size = len(json.dumps(item, ensure_ascii=False))
            if size > remaining:
                break
            selected.append(item)
            remaining -= size
        partial = len(selected) < len(topics.get('items', []))
        topics['items'] = selected
        if partial:
            topics['nextOffset'] = len(selected)
        topics['contextPartial'] = partial or topics.get('nextOffset') is not None
        topics['detailsEndpoint'] = '/learning-state/topics'
        topics['missingItemsAreNotUnknown'] = True
    return state, topics
