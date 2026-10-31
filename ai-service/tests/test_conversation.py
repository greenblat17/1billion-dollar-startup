import pytest

from app.characters import Characters, CharacterStore
from app.conversation import (ConversationHistory, ConversationMode, ConversationRouting,
                              Personality)
from app.dialogue import MemoryDialogueStore, shared_free_scope_id
from tests.conftest import FakeLlm, FakeTts


@pytest.mark.asyncio
async def test_mode_and_personality_use_shared_free_history_for_telegram():
    characters = Characters(CharacterStore(), FakeLlm(), FakeTts(), enabled=True, allowlist="1")
    await characters.store.set("tg-1", "kora_h")
    routing = ConversationRouting(characters)
    history = ConversationHistory(MemoryDialogueStore(40, 86400))

    free = await routing.resolve("tg-1", ConversationMode.FREE)
    onboarding = await routing.resolve("tg-1", ConversationMode.ONBOARDING)
    situation = await routing.resolve("tg-1", ConversationMode.SITUATION)

    assert free.personality == Personality.KORA_H
    assert onboarding.personality == situation.personality == Personality.KORA_H
    assert history.scope_id("tg-1", free) == shared_free_scope_id("tg-1")
    assert history.scope_id("tg-1", onboarding) == history.scope_id("tg-1", situation) == "tg-1"
    assert (await routing.resolve("app-1", ConversationMode.FREE)).personality == Personality.SPEAKY


@pytest.mark.asyncio
async def test_situation_context_uses_call_turns_without_writing_free_history():
    dialogue = MemoryDialogueStore(40, 86400)
    history = ConversationHistory(dialogue)
    route = await ConversationRouting(Characters(CharacterStore())).resolve("tg-1", ConversationMode.SITUATION)
    await dialogue.record_turn("tg-1", "Real topic", "Real answer")

    context = history.situation(await history.load("tg-1", route), {
        "id": "interview-1",
        "openingQuestion": "Welcome to the interview.",
        "turns": [{"transcript": "I led a team.", "reply": "How many people?"}],
    })

    assert [message.content for message in context.messages] == [
        "Welcome to the interview.", "I led a team.", "How many people?",
    ]
    assert context.scope_id == "call:interview-1"
    assert [message.content for message in await dialogue.history("tg-1")] == ["Real topic", "Real answer"]
