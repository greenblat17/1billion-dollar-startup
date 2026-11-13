"""Provider schemas and local validation for durable learning results."""
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class LearningResult(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)

    @classmethod
    def response_format(cls):
        schema = cls.model_json_schema()
        def strict(node):
            if isinstance(node, dict):
                node.pop('default', None)
                if node.get('type') == 'object':
                    node['required'] = list(node.get('properties', {}))
                for value in node.values():
                    strict(value)
            elif isinstance(node, list):
                for value in node:
                    strict(value)
        strict(schema)
        return {'type': 'json_schema', 'json_schema': {
            'name': cls.__name__, 'strict': True, 'schema': schema}}


class LessonPlan(LearningResult):
    objective: str = Field(min_length=1, max_length=3000)
    topic: str = Field(min_length=1, max_length=3000)
    strategy: str = Field(min_length=1, max_length=3000)


class ObservationEvidence(LearningResult):
    kind: Literal["self_report", "ai_observation"]
    signal: Literal["strength", "difficulty", "neutral"]
    support: Literal["independent", "prompted", "unknown"]
    situation: str = Field(min_length=1, max_length=500)
    quote: str = Field(min_length=1, max_length=1000)


class Observation(LearningResult):
    key: str = Field(min_length=1)
    dimension: Literal['fluency', 'confidence', 'autonomy', 'grammar', 'vocabulary']
    content: str = Field(min_length=1, max_length=1000)
    uncertainty: Literal['limited', 'tentative', 'supported']
    sourceId: str = Field(min_length=1)
    evidence: ObservationEvidence | None = None


class ExtractedFact(LearningResult):
    key: str = Field(pattern=r'^[a-z][a-z_]{0,49}$')
    value: str = Field(min_length=1, max_length=1000)
    sourceId: str
    evidence: str = Field(min_length=1)
    expectedVersion: int = Field(ge=0)


class LessonEvaluation(LearningResult):
    status: Literal['evaluated', 'insufficient_evidence']
    observations: list[Observation] = Field(max_length=10)


class PersonalCandidate(LearningResult):
    key: str = Field(pattern=r'^[a-z][a-z_]{0,49}$')
    value: str = Field(min_length=1, max_length=1000)
    sourceId: str


class LessonNote(LearningResult):
    kind: Literal["topic", "skill", "strength", "difficulty", "feedback", "recommendation"]
    content: str = Field(min_length=1, max_length=1000)
    sourceIds: list[str] = Field(min_length=1, max_length=10)


class MemoryEvaluation(LessonEvaluation):
    facts: list[ExtractedFact] = Field(max_length=20)
    candidates: list[PersonalCandidate] = Field(max_length=10)
    notes: list[LessonNote] = Field(default_factory=list, max_length=30)
