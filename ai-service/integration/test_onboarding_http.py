"""Real HTTP/storage handoff; scripted model decisions are not live acceptance."""
import json
import os
import time
from uuid import uuid4

import httpx
import pytest

from app.context_builder import ContextBuilder
from app.foundation_client import FoundationClient
from app.foundation_tutor import FoundationTutor
from app.tutor import TutorRequest
from test_foundation_http import actor, now


class OnboardingModel:
    def __init__(self):
        self.turn = 0

    async def complete(self, messages):
        self.turn += 1
        if self.turn == 1:
            name, args = 'update_onboarding', {'expectedRevision': 0, 'changes': [
                {'field': 'goal', 'value': 'English for work', 'explanation': None, 'evidence': 'for work'},
                {'field': 'targetLevel', 'value': 'B2', 'explanation': None, 'evidence': 'B2'},
                {'field': 'selfAssessment', 'value': 'Unknown', 'explanation': 'Cannot choose current level',
                 'evidence': 'not sure of my level'}]}
        else:
            name, args = 'create_trial_booking', {'intent': 'explicit_request', 'evidence': 'trial now',
                                                  'schedule': {'mode': 'now'}}
        return {'role': 'assistant', 'tool_calls': [{'id': str(self.turn), 'type': 'function',
                 'function': {'name': name, 'arguments': json.dumps(args)}}]}


@pytest.mark.asyncio
async def test_profile_survives_booking_replay_and_reaches_voice_context():
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        subject, request = str(time.time_ns()), uuid4().hex
        body = 'B2 for work, not sure of my level, trial now'
        owner = await actor(http, root, token, subject, 'text', requestId=request, text=body, occurredAt=now())
        backend = FoundationClient(root, token, http)
        model = OnboardingModel()
        tutor = FoundationTutor(backend, model, ContextBuilder(backend))
        payload = TutorRequest(sessionId=subject, requestId=request, text=body, receivedAt=time.time())
        first = await tutor.respond(payload, owner['scope'])
        assert first['booking']['durationMinutes'] == 30
        assert first['booking']['timing'] == 'now'
        assert first['reply'] == ''
        assert await tutor.respond(payload, owner['scope']) == first
        assert model.turn == 2
        operator = await actor(http, root, token, subject)
        context = await ContextBuilder(backend).build(operator['scope'], 'voice', first['booking']['id'])
        state = context.data['learningState']
        assert state['revision'] == 1
        assert state['profile']['targetLevel'] == 'B2'
        assert state['profile']['currentLevels']['selfAssessment']['overall']['level'] == 'Unknown'
        assert state['profile']['currentLevels']['speaky']['overall']['level'] == 'Unknown'
        assert context.data['goal']['content']['value'] == 'English for work'
