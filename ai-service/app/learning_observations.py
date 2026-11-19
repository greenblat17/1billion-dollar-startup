"""SP-17A v1 wire types. Backend owns validation/storage; no analyzer or scoring here.

Source revision is conversation_event.sequence in v1, NOT a raw-call revision.
SP-06 maps sourceEventId/sourceSequence to eventId/revision; raw eventId/revision are distinct.
Spans are Unicode code point offsets [start, end). Missing confidence is unknown quality.
"""
from typing import Literal, NotRequired, TypedDict


class Span(TypedDict):
    start: int
    end: int


class ObservationSource(TypedDict):
    eventId: str
    revision: int
    role: Literal['user', 'assistant']
    purpose: Literal['answer', 'context', 'hint', 'correction']
    span: NotRequired[Span | None]


class StageReference(TypedDict):
    planId: str
    planRevision: int
    stageId: str
    stageAttemptId: NotRequired[str | None]


class QualitySignal(TypedDict):
    reason: Literal['uncertain_asr', 'ambiguous_interpretation', 'defective_content',
                    'missing_context', 'unknown_support', 'refusal', 'technical_failure']
    component: str
    sourceEventId: str


class Confidence(TypedDict):
    value: float
    component: str
    kind: Literal['interpretation', 'asr']


class Quality(TypedDict):
    status: Literal['usable', 'uncertain', 'invalid']
    signals: list[QualitySignal]
    confidence: NotRequired[Confidence | None]


class Provenance(TypedDict):
    component: str
    analyzerVersion: NotRequired[str | None]
    model: NotRequired[str | None]
    promptVersion: NotRequired[str | None]


class LearningObservation(TypedDict):
    schemaVersion: Literal[1]
    attemptId: str
    evidenceSlot: str
    anchorEventId: str
    lessonId: NotRequired[str | None]
    occurredAt: str
    sources: list[ObservationSource]
    catalogVersion: str
    skillId: str
    topicId: NotRequired[str | None]
    problemId: NotRequired[str | None]
    practice: Literal['trial', 'regular', 'homework']
    method: NotRequired[Literal['conversation', 'explanation', 'exercises', 'exposure'] | None]
    kind: Literal['demonstration', 'self_report', 'feedback']
    outcome: Literal['independent_correct', 'uncorrected_error', 'self_corrected_error', 'hinted_correct', 'unresolved', 'invalid']
    support: Literal['independent', 'hinted', 'unknown']
    initialOutcome: Literal['correct', 'error', 'unknown']
    quality: Quality
    provenance: Provenance
    taskId: NotRequired[str | None]
    stageRef: NotRequired[StageReference | None]
    actionId: NotRequired[str | None]
    repeatOf: NotRequired[str | None]
    route: Literal['assessment_only', 'problem_detection', 'stage_progress', 'unresolved']
    rationale: str
