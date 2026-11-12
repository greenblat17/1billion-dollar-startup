"""Native tools for preliminary scheduling. No payment or entitlement mutation."""
from pydantic import BaseModel, ConfigDict, Field

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
        return await self.llm.complete_tools(messages, tools + TRIAL_TOOLS)
