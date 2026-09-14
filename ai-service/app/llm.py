from __future__ import annotations

from typing import Any, Protocol

from openai import AsyncOpenAI

from app.dialogue import ChatMessage
from app.retry import once_on_retryable

SPEAKING_COACH_SYSTEM = """You are a warm English conversation partner helping the user practice speaking.

Speak only English. Keep replies to 2–4 short sentences.
Stay slightly above the user's level. Ask a natural follow-up so the talk continues.
If they struggle, rephrase their idea in natural English and keep going.
Do not lecture, list grammar rules, give CEFR scores, or switch to another language unless they ask.
At most one gentle recast of an error, woven into your reply — never a correction dump.
"""


class ChatModel(Protocol):
    async def complete(self, history: list[ChatMessage], user_text: str) -> str:
        ...


class OpenAiChatModel:
    def __init__(self, client: AsyncOpenAI, model: str, temperature: float = 0.7, max_tokens: int = 200) -> None:
        self._client = client
        self._model = model
        self._temperature = temperature
        self._max_tokens = max_tokens

    async def complete(self, history: list[ChatMessage], user_text: str) -> str:
        messages = [{"role": "system", "content": SPEAKING_COACH_SYSTEM}]
        messages.extend({"role": item.role, "content": item.content} for item in history)
        messages.append({"role": "user", "content": user_text})

        async def call() -> Any:
            return await self._client.chat.completions.create(
                model=self._model,
                messages=messages,
                temperature=self._temperature,
                max_completion_tokens=self._max_tokens,
            )

        response = await once_on_retryable(call)
        text = (response.choices[0].message.content or "").strip()
        if not text:
            raise RuntimeError("llm returned empty reply")
        return text
