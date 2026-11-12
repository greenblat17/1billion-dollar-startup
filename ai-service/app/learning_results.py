"""Provider schemas and local validation for durable learning results."""
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class LearningResult(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)

    @classmethod
    def response_format(cls):
        return {'type': 'json_schema', 'json_schema': {
            'name': cls.__name__, 'strict': True, 'schema': cls.model_json_schema()}}


class LessonPlan(LearningResult):
    objective: str = Field(min_length=1, max_length=3000)
    topic: str = Field(min_length=1, max_length=3000)
    strategy: str = Field(min_length=1, max_length=3000)


class Observation(LearningResult):
    key: str = Field(min_length=1)
    dimension: Literal['fluency', 'confidence', 'autonomy', 'grammar', 'vocabulary']
    content: str = Field(min_length=1, max_length=1000)
    uncertainty: Literal['limited', 'tentative', 'supported']
    sourceId: str = Field(min_length=1)


class LessonEvaluation(LearningResult):
    status: Literal['evaluated', 'insufficient_evidence']
    observations: list[Observation] = Field(max_length=10)
