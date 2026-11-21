"""SP-13A wire types for prepared lesson material.

The backend checks shape and Task/Stage ownership. SP-13B will generate and validate
real material; the live tutor in SP-14/15 decides whether a learner's answer fits.
"""
import json
from pathlib import Path
from typing import Literal, TypedDict


ACTION_KINDS = {
    method: frozenset(kinds)
    for method, kinds in json.loads(Path(__file__).with_name('learning-content-action-kinds-v1.json').read_text()).items()
}
CONTENT_LIMITS = json.loads(Path(__file__).with_name('learning-content-limits-v1.json').read_text())


Method = Literal['Explanation', 'Exercises', 'Exposure', 'Conversation']
ValidationStatus = Literal['pending', 'passed', 'rejected']


class StageRef(TypedDict):
    taskId: str
    planId: str
    planRevision: int
    stageId: str


class AnswerGuide(TypedDict):
    criteria: list[str]
    alternatives: list[str]
    unacceptable: list[str]


class ContentValidation(TypedDict):
    status: ValidationStatus
    component: str
    reasons: list[str]


class LearningContentDocument(TypedDict):
    schemaVersion: Literal[1]
    taskId: str
    stageRef: StageRef
    catalogVersion: str
    skillId: str
    topicId: str
    problemId: str
    method: Method
    actionKind: str
    cefr: Literal['A1', 'A2', 'B1', 'B2', 'C1', 'Unknown']
    objective: str
    spokenInstruction: str
    payload: dict
    answerGuide: AnswerGuide
    validation: ContentValidation
