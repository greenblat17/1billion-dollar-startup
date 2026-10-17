"""Live structured-output smoke check for the configured personal-memory model.

Run with OPENAI_API_KEY, OPENAI_BASE_URL and LLM_MODEL from the target environment.
Only synthetic facts are sent; no real user data is printed.
"""
import asyncio
import json

from openai import AsyncOpenAI

from app.config import Settings
from app.llm import OpenAiChatModel
from app.onboarding_model import OnboardingModel


async def main() -> None:
    settings = Settings.from_env()
    if not settings.openai_api_key:
        raise RuntimeError("OPENAI_API_KEY is required")
    client = AsyncOpenAI(api_key=settings.openai_api_key, base_url=settings.openai_base_url,
                         max_retries=0)
    try:
        model = OnboardingModel(OpenAiChatModel(client, settings.llm_model))
        moved = await asyncio.wait_for(model.update_person_once({}, ["I moved to Kazan last month."]), 20)
        if "kazan" not in json.dumps(moved, ensure_ascii=False).lower():
            raise AssertionError("move to Kazan was not kept")
        changed = await asyncio.wait_for(model.update_person_once(moved, ["I moved from Kazan to Moscow."]), 20)
        if "moscow" not in json.dumps(changed, ensure_ascii=False).lower():
            raise AssertionError("new city was not kept")
        forgotten = await asyncio.wait_for(model.update_person_once(changed, ["Please forget where I live."]), 20)
        if "moscow" in json.dumps(forgotten, ensure_ascii=False).lower():
            raise AssertionError("forget request did not remove current city")
        print("person-memory structured output and synthetic cases passed")
    finally:
        await client.close()


if __name__ == "__main__":
    asyncio.run(main())
