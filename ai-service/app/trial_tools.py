"""Native tools for preliminary scheduling. No payment or entitlement mutation."""
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.tutor_tools import TOOLS


class PreliminarySchedule(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)
    evidence: str = Field(min_length=1, max_length=2000)
    timezone: str
    startDate: str
    localTime: str
    weekdays: list[int] = Field(min_length=1, max_length=7, description='ISO weekdays 1=Monday … 7=Sunday')
    durationMinutes: int = Field(ge=15, le=60)
    scheduleId: str | None
    expectedVersion: int | None


class CancelPreliminarySchedule(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)
    evidence: str = Field(min_length=1, max_length=2000)
    scheduleId: str
    expectedVersion: int = Field(ge=1)


TRIAL_TOOLS = [
    {'type': 'function', 'function': {'name': 'cancel_preliminary_schedule',
        'description': 'Cancel the saved preliminary schedule and its future placeholder meetings only on a clear cancellation request.',
        'parameters': CancelPreliminarySchedule.model_json_schema(), 'strict': True}},
    {'type': 'function', 'function': {'name': 'save_preliminary_schedule',
        'description': 'Save or change an explicitly agreed preliminary schedule. Creates visible, non-joinable regular lesson placeholders for 14 days. No charge or active subscription. Obtain timezone, days, local time and duration; never invent agreement. Use current scheduleId and expectedVersion for changes.',
        'parameters': PreliminarySchedule.model_json_schema(), 'strict': True}},
    {'type': 'function', 'function': {'name': 'get_preliminary_schedules',
        'description': 'Read saved preliminary schedules and versions.',
        'parameters': {'type': 'object', 'properties': {}, 'required': [], 'additionalProperties': False}, 'strict': True}},
]


class LessonTime(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)
    timing: Literal['scheduled', 'now', 'after'] = Field(description='now: start immediately; after: N minutes from server commit; scheduled: agreed absolute instant')
    startsAt: str | None = Field(description='ISO instant with offset for scheduled only; null for now/after')
    delayMinutes: int | None = Field(ge=1, le=525600, description='Minutes from server commit for after only; null otherwise')
    timezone: str = Field(description='Agreed IANA timezone; reuse the existing lesson timezone when moving to now/after')

    @model_validator(mode='after')
    def validate_time_choice(self):
        if self.timing == 'scheduled':
            valid = bool(self.startsAt) and self.delayMinutes is None
        elif self.timing == 'now':
            valid = self.startsAt is None and self.delayMinutes is None
        else:
            valid = self.startsAt is None and self.delayMinutes is not None
        if not valid:
            raise ValueError('invalid_time_choice')
        return self


class RegularLesson(LessonTime):
    evidence: str = Field(min_length=1, max_length=2000)
    durationMinutes: int = Field(ge=15, le=60)


class MoveLesson(LessonTime):
    evidence: str = Field(min_length=1, max_length=2000)
    lessonId: str
    expectedVersion: int = Field(ge=1)


class CancelLesson(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)
    evidence: str = Field(min_length=1, max_length=2000)
    lessonId: str
    expectedVersion: int = Field(ge=1)


class ResolveScheduleTime(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)
    evidence: str = Field(min_length=1, max_length=2000)
    scheduleId: str
    expectedVersion: int = Field(ge=1)
    occurrenceKey: str
    localTime: str = Field(description='Explicitly agreed replacement local time for the unresolved date')


SCHEDULE_MODELS = {
    'save_schedule': (PreliminarySchedule, 'schedule.agree'),
    'cancel_schedule': (CancelPreliminarySchedule, 'trial.schedule_cancel'),
    'create_extra_lesson': (RegularLesson, 'lesson.create'),
    'reschedule_lesson': (MoveLesson, 'lesson.reschedule'),
    'cancel_lesson': (CancelLesson, 'lesson.cancel'),
    'resolve_schedule_time': (ResolveScheduleTime, 'schedule.resolve_time'),
}
SCHEDULE_DESCRIPTIONS = {
    'save_schedule': 'Save or change the explicitly agreed recurring schedule: weekdays, local time, IANA timezone, duration 15–60, first date. Creates real regular lessons. Recommend 7/week but respect fewer. Read current schedule and version before changing. No payment or subscription claim.',
    'cancel_schedule': 'Cancel the recurring agreement only on an explicit request, preserving started lessons.',
    'create_extra_lesson': 'Create one real additional or one-off regular lesson ONLY on explicit agreement. Never infer one-off from a recurring request.',
    'reschedule_lesson': 'Move one scheduled lesson on a clear request without a second confirmation. Keep the recurring agreement unchanged. Read lessonId/version/timezone first. Use timing=now for right now, timing=after with delayMinutes for minutes from now; startsAt=null in both modes. Never invent a current timestamp.',
    'cancel_lesson': 'Cancel just one scheduled lesson. Read lessonId/version first. Do not cancel the recurring agreement.',
    'resolve_schedule_time': 'Resolve a pending DST occurrence with an explicitly agreed alternative local time. Read timeIssues from get_schedules. Does not change other dates.',
}
SCHEDULE_TOOLS = [{'type': 'function', 'function': {'name': name, 'description': SCHEDULE_DESCRIPTIONS[name],
    'parameters': model.model_json_schema(), 'strict': True}} for name, (model, _) in SCHEDULE_MODELS.items()]
SCHEDULE_TOOLS += [{'type': 'function', 'function': {'name': name, 'description': description,
    'parameters': {'type': 'object', 'properties': {}, 'required': [], 'additionalProperties': False}, 'strict': True}}
    for name, description in [('get_schedules', 'Read recurring agreements, versions and unresolved DST dates.'),
                              ('get_lessons', 'Read real upcoming/current lessons, ids and versions; do not guess next meeting.')]]


class TrialTutorModel:
    def __init__(self, llm):
        self.llm = llm

    async def complete(self, messages):
        tools = []
        for tool in TOOLS:
            function = dict(tool['function'])
            if function['name'] == 'create_trial_booking':
                function['description'] = 'Book the free approximately 15-minute voice trial on a clear present request. No second confirmation or mandatory questionnaire. mode=now needs no timezone. A technical failure may be rebooked with preserved context.'
            tools.append({'type': 'function', 'function': function})
        return await self.llm.complete_tools(messages, tools + SCHEDULE_TOOLS)
