"""Isolated current verifier replay; a private copy supplies cases and prompts."""

import asyncio
import json
import os

from openai import AsyncOpenAI

CASES = []  # private runner substitutes this value
ERROR_PROMPT = ""  # private runner substitutes this value
ALT_PROMPT = ""  # private runner substitutes this value


async def main():
    if not CASES or not ERROR_PROMPT or not ALT_PROMPT:
        raise RuntimeError("private probe input missing")
    client = AsyncOpenAI(api_key=os.environ["OPENAI_API_KEY"],
                         base_url=os.environ.get("OPENAI_BASE_URL"),
                         max_retries=0, timeout=20)
    output = []
    try:
        for case in CASES:
            semaphore = asyncio.Semaphore(3)
            groups = [[item for item in case["candidates"]
                       if (item["kind"] == "alternative") == alternative]
                      for alternative in (False, True)]
            chunks = [group[i:i + 5] for group in groups for i in range(0, len(group), 5)]

            async def verify(chunk):
                async with semaphore:
                    try:
                        response = await client.chat.completions.create(
                            model=os.getenv("CORRECTION_VERIFICATION_MODEL", "openai/gpt-4o-mini"),
                            messages=[{"role": "system", "content": ALT_PROMPT if chunk[0]["kind"] == "alternative" else ERROR_PROMPT},
                                      {"role": "user", "content": json.dumps({"candidates": chunk}, ensure_ascii=False)}],
                            temperature=0, max_completion_tokens=1200,
                            response_format={"type": "json_object"},
                        )
                        parsed = json.loads(response.choices[0].message.content or "")
                        return {"accepted": [id for id in parsed.get("acceptedIds", [])
                                              if id in {item["id"] for item in chunk}],
                                "ids": [item["id"] for item in chunk], "error": None}
                    except Exception as exc:
                        return {"accepted": [], "ids": [item["id"] for item in chunk],
                                "error": type(exc).__name__}

            tasks = [asyncio.create_task(verify(chunk)) for chunk in chunks]
            done, pending = await asyncio.wait(tasks, timeout=10)
            for task in pending:
                task.cancel()
            if pending:
                await asyncio.gather(*pending, return_exceptions=True)
            output.append({"case": case["case"],
                           "chunks": [task.result() for task in done if not task.cancelled()],
                           "timedOut": len(pending)})
        print(json.dumps({"cases": output}, ensure_ascii=False))
    finally:
        await client.close()


if __name__ == "__main__":
    asyncio.run(main())
