import json

import pytest

from app.onboarding_model import OnboardingModel


class Llm:
    def __init__(self, question):
        self.question = question
        self.prompt = ""

    async def complete_json(self, prompt, payload, **kwargs):
        self.prompt = prompt
        self.payload = json.loads(payload)
        return json.dumps({"question": self.question})


@pytest.mark.asyncio
async def test_call_opening_greets_then_follows_a_prior_user_topic():
    llm = Llm("Last time you mentioned a job interview. How did it go?")
    model = OnboardingModel(llm)
    opening = await model.start_call_question({
        "personalContext": "Verified learner memory",
        "firstName": "Alex",
        "recentConversation": [{"role": "user", "content": "I have a job interview next week."}],
    })

    assert opening == "Hi, Alex! How are you? Last time you mentioned a job interview. How did it go?"
    assert "previous user turn" in llm.prompt
    assert "Never invent an event or outcome" in llm.prompt


@pytest.mark.asyncio
async def test_call_opening_without_usable_name_uses_plain_greeting():
    llm = Llm("What was the best part of your day?")
    opening = await OnboardingModel(llm).start_call_question({"firstName": "🎙", "recentConversation": []})
    assert opening == "Hi! How are you? What was the best part of your day?"


@pytest.mark.asyncio
async def test_call_opening_rejects_extra_questions_in_follow_up():
    llm = Llm("How did it go? What happened next?")
    with pytest.raises(ValueError, match="missing call opening question"):
        await OnboardingModel(llm).start_call_question({})
