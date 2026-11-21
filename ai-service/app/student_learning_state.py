"""Personal-memory boundary and restricted onboarding tool contract for the foundation runtime."""
from typing import Annotated, Literal

from pydantic import Field, field_validator, model_validator

from app.tutor_tools import Strict, UpdateContext

LEARNING_KEYS = frozenset({
    'goal', 'goals', 'personal_goal', 'desired_result', 'level', 'self_assessment', 'current_level', 'target_level',
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


def assessed_content_level(state: dict, skill_id: str) -> str:
    levels = state['profile']['currentLevels']['speaky']
    skill_level = levels['skills'][skill_id]['level']
    return skill_level if skill_level != 'Unknown' else levels['overall']['level']


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


GoalId = Literal['G01', 'G02', 'G03', 'G04', 'G05', 'G06', 'G07', 'G08']
GOAL_MAP = {
    'G01': 'Everyday Communication',
    'G02': 'Travel',
    'G03': 'Career',
    'G04': 'Education',
    'G05': 'Relocation',
    'G06': 'Exams & Certifications',
    'G07': 'Content & Entertainment',
    'G08': 'Personal Development',
}


class GoalSelection(Strict):
    goals: list[GoalId] = Field(min_length=1, max_length=8)
    mode: Literal['replace', 'extend']

    @field_validator('goals')
    @classmethod
    def unique_goals(cls, goals: list[GoalId]) -> list[GoalId]:
        if len(goals) != len(set(goals)):
            raise ValueError('duplicate_goal_id')
        return goals


class OnboardingChange(Strict):
    field: Literal['goal', 'selfAssessment', 'targetLevel', 'deadline']
    value: GoalSelection | Annotated[str, Field(max_length=2000)] | None
    explanation: str | None = Field(max_length=2000)
    evidence: str = Field(min_length=1, max_length=2000)

    @model_validator(mode='after')
    def goal_selection_matches_field(self):
        if self.value is not None:
            if self.field == 'goal' and not isinstance(self.value, GoalSelection):
                raise ValueError('goal_selection_required')
            if self.field != 'goal' and not isinstance(self.value, str):
                raise ValueError('invalid_onboarding_value')
        return self


class UpdateOnboarding(Strict):
    expectedRevision: int = Field(ge=0)
    changes: list[OnboardingChange] = Field(min_length=1, max_length=4)


ONBOARDING_TOOL = {'type': 'function', 'function': {
    'name': 'update_onboarding',
    'description': 'Save explicit learner goal, overall self-assessment, target CEFR or deadline. '
        'Call before replying when the CURRENT message supplies or corrects one of these fields. '
        'For goal, a short answer to the preceding question can supply the goal. '
        'Select all applicable IDs from Goal Map v1: ' + ', '.join(f'{key} {name}' for key, name in GOAL_MAP.items()) + '. '
        'Use a goal value with goals and mode. Use replace for a first goal or explicit replacement. '
        'Use extend when the learner adds detail or another goal to the stored Personal Goal. '
        'For goal evidence copy the full goal wording verbatim, including all relevant details. '
        'The service stores that exact evidence as Personal Goal. '
        'Skip this tool for a report of a difficulty alone; use update_learner_context for that. '
        'Never backfill goal or levels from earlier turns with this call. '
        'Use learningState.revision, or 0 for a new profile. Levels are A1/A2/B1/B2/C1/Unknown; '
        'never infer a measured level. Keep reported uncertainty in explanation. '
        'Unknown self-assessment can retain the learner description in explanation. '
        'Deadline is YYYY-MM-DD or null; clarify ambiguous dates. Null clears goal/deadline only. '
        'Omit unchanged fields. Each change needs verbatim evidence from the current message. '
        'A short acceptance can confirm the preceding unambiguous level proposal. '
        'No system assessment, Topic Knowledge, intensity or Homework writes.',
    'parameters': UpdateOnboarding.model_json_schema(), 'strict': True}}
