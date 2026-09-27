from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.dialogue import MemoryDialogueStore
from app.lesson_score import parse_lesson_scores
from app.lessons import MemoryLessonStore
from app.main import create_app
from app.pipeline import ClipPipeline
from tests.conftest import FakeLlm, FakeStt, FakeTts
from tests.conftest import test_settings as settings_for_test

AUTH = {"X-Internal-Token": "test-internal-token"}


def test_parse_lesson_scores_clamps_each_metric() -> None:
    assert parse_lesson_scores('{"grammar": 120, "vocabulary": -4, "fluency": 50.9}') == {
        "grammar": 100,
        "vocabulary": 0,
        "fluency": 50,
    }


def test_parse_lesson_scores_requires_all_three() -> None:
    with pytest.raises(RuntimeError):
        parse_lesson_scores('{"grammar": 30, "vocabulary": 30}')


class CountingScorer:
    def __init__(self) -> None:
        self.calls = 0

    async def score(self, lesson: dict) -> dict[str, int]:
        self.calls += 1
        return {"grammar": 34, "vocabulary": 51, "fluency": 22}


@pytest.mark.asyncio
async def test_score_route_stores_the_result_and_does_not_call_again() -> None:
    scorer = CountingScorer()
    lessons = MemoryLessonStore()
    lesson_id = await lessons.open("tg-score")
    await lessons.append_turn(
        "tg-score",
        "I goes home",
        "Got it",
        [{"wrong": "goes", "better": "go", "kind": "grammar"}],
    )
    assert await lessons.seal("tg-score") == lesson_id
    pipeline = ClipPipeline(
        stt=FakeStt(["hello"]),
        llm=FakeLlm(),
        tts=FakeTts(),
        dialogue=MemoryDialogueStore(max_messages=40, ttl_seconds=86400),
        lessons=lessons,
    )
    app = create_app(settings=settings_for_test(), pipeline=pipeline, lesson_scorer=scorer)
    with TestClient(app, headers=AUTH) as client:
        first = client.post("/internal/lessons/score", json={"lessonId": lesson_id})
        second = client.post("/internal/lessons/score", json={"lessonId": lesson_id})

    assert first.status_code == 200
    assert second.status_code == 200
    assert first.json() == second.json()
    assert first.json()["grammar"] == 34
    assert first.json()["corrections"] == [{"wrong": "goes", "better": "go", "kind": "grammar"}]
    assert scorer.calls == 1
