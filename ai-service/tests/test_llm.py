import json
from types import SimpleNamespace

import pytest

from app.config import Settings
from app.dialogue import MemoryDialogueStore
from app.llm import Correction, NOTES_MAX_TOKENS, NOTES_SYSTEM, OpenAiChatModel, parse_corrections, parse_reply
from app.metrics import MemoryMetricsStore, MetricRates
from app.pipeline import ClipPipeline
from app.review import parse_review
from tests.conftest import FakeStt, FakeTts


def decision(context="I am agree with you.", error="am agree", replacement="agree", kind="grammar", **overrides):
    return {
        "context": context, "error": error, "replacement": replacement, "kind": kind,
        "reason": "Agree is a verb and does not take am.", "confidence": "high",
        "definitely_wrong": True, "is_spoken_language_artifact": False,
        "is_asr_uncertain": False, "worth_showing": True, "understandable_alone": True,
        **overrides,
    }


def parse(notes, transcript="I am agree with you."):
    return parse_corrections(json.dumps({"notes": notes}), transcript)


def test_parse_reply_reads_reply():
    assert parse_reply('{"reply":"Nice — what did you buy?"}') == "Nice — what did you buy?"


def test_default_reply_and_notes_models_are_luna(monkeypatch):
    monkeypatch.delenv("LLM_MODEL", raising=False)
    monkeypatch.delenv("NOTES_MODEL", raising=False)
    settings = Settings.from_env()
    assert settings.llm_model == "openai/gpt-5.6-luna"
    assert settings.notes_model == "openai/gpt-5.6-luna"


def test_correction_keeps_context_and_only_public_fields():
    correction = parse([decision()])[0]
    assert correction == Correction("I am agree with you.", "I agree with you.", "grammar")
    assert correction.to_json() == {
        "wrong": "I am agree with you.", "better": "I agree with you.", "kind": "grammar",
    }
    assert correction.note == "I am agree with you.|||I agree with you."


def test_short_russian_reason_is_public_but_legacy_note_stays_a_pair():
    correction = parse([decision(reason="Agree — глагол, поэтому am здесь не нужен.")])[0]
    assert correction.explanation == "Agree — глагол, поэтому am здесь не нужен."
    assert correction.to_json()["explanation"] == correction.explanation
    assert correction.note == "I am agree with you.|||I agree with you."


@pytest.mark.parametrize("reason", [
    "Agree is a verb.", "Так правильнее.", "Звучит лучше.",
    "Сначала одно предложение. Затем другое.", "Первая строка.\nВторая строка.",
    "А" * 161,
])
def test_bad_public_explanation_does_not_hide_reliable_correction(reason):
    correction = parse([decision(reason=reason)])[0]
    assert correction.explanation is None
    assert "explanation" not in correction.to_json()


@pytest.mark.parametrize("field,value", [
    ("confidence", "medium"), ("confidence", "low"), ("definitely_wrong", False),
    ("is_spoken_language_artifact", True), ("is_asr_uncertain", True),
    ("worth_showing", False), ("understandable_alone", False), ("reason", ""),
    ("worth_showing", "true"), ("is_asr_uncertain", 0), ("kind", "style"),
    ("context", 123), ("replacement", "am agree"),
])
def test_uncertain_or_invalid_candidate_is_omitted(field, value):
    assert parse([decision(**{field: value})]) == []


@pytest.mark.parametrize("field", list(decision()))
def test_every_decision_field_is_required(field):
    note = decision()
    del note[field]
    assert parse([note]) == []


@pytest.mark.parametrize("raw", ['{}', '{"notes":[]}', '{"notes":"bad"}', '[]', '{bad',
                                      '{"notes":["I am agree|||I agree"]}'])
def test_malformed_or_legacy_output_does_not_bypass_checks(raw):
    assert parse_corrections(raw, "I am agree") == []


def test_exact_original_whole_words_and_contractions_are_required():
    assert parse([decision(context="I am agreement.")], "I am agreement.") == []
    assert parse([decision(context="I can't go.", error="can", replacement="could")], "I can't go.") == []
    assert parse([decision(context="I can’t go.", error="can", replacement="could")], "I can’t go.") == []
    assert parse([decision()], "I agree with you.") == []


def test_modal_and_main_verb_are_preserved_when_removing_extra_to():
    sentence = "I can't imagine he can to swim."
    assert parse([decision(context=sentence, error="can to swim", replacement="swim")], sentence) == []
    assert parse([decision(context=sentence, error="can to swim", replacement="can swim")], sentence) == [
        Correction(sentence, "I can't imagine he can swim."),
    ]


def test_duplicates_and_overlapping_fragments_are_removed():
    notes = [decision(), decision(), decision(context="am agree", error="am agree", replacement="agree")]
    assert len(parse(notes)) == 1


def test_context_may_cross_a_pause_but_edit_must_be_exact():
    transcript = "I hope it'll be works next week."
    note = decision(context=transcript, error="it'll be works", replacement="it'll work")
    assert parse([note], transcript) == [Correction(transcript, "I hope it'll work next week.")]


def test_unrelated_monologue_is_omitted_but_identical_repeated_error_is_allowed():
    long = " ".join(["I talked about my trip"] * 8) + " and she don't like soup"
    assert parse([decision(context=long, error="don't", replacement="doesn't")], long) == []
    repeated = "She don't like soup. She don't like soup."
    assert parse([decision(context="She don't like soup.", error="don't", replacement="doesn't")], repeated) == [
        Correction("She don't like soup.", "She doesn't like soup."),
    ]


def test_spoken_artifact_blocks_only_an_overlapping_edit():
    transcript = "I was like I am agree with the plan."
    note = decision(context=transcript, error="am agree", replacement="agree")
    raw = json.dumps({"speech_artifacts": ["like"], "notes": [note]})
    assert parse_corrections(raw, transcript) == [Correction(transcript, "I was like I agree with the plan.")]

    repeated = "The price is also is high for students"
    note = decision(context=repeated, error="is also is", replacement="is also")
    raw = json.dumps({"speech_artifacts": ["is also is"], "notes": [note]})
    assert parse_corrections(raw, repeated) == []

    repeated_sentence = "I am agree with you. I am agree with you."
    note = decision(context="I am agree with you.")
    raw = json.dumps({"speech_artifacts": ["I am agree with you."], "notes": [note]})
    assert parse_corrections(raw, repeated_sentence) == [Correction(
        "I am agree with you.", "I agree with you.",
    )]


def test_limit_and_priority_apply_after_filtering():
    notes = [decision(context=f"I fragment {i}", error=f"fragment {i}", replacement=f"fixed {i}", kind=kind)
             for i, kind in enumerate(["natural", "word", "natural", "grammar"])]
    notes.insert(0, decision(worth_showing=False))
    corrections = parse(notes, "I fragment 0 and I fragment 1 and I fragment 2 and I fragment 3")
    assert [(item.wrong, item.kind) for item in corrections] == [
        ("I fragment 3", "grammar"), ("I fragment 1", "word"), ("I fragment 0", "natural"),
    ]


@pytest.mark.asyncio
async def test_live_notes_path_uses_strict_parser_and_separate_output_budget():
    class Model(OpenAiChatModel):
        calls = 0

        async def _complete(self, messages, temperature, max_tokens=None, model=None, retry=True,
                            reasoning_effort=None, purpose="reply"):
            assert purpose == "notes"
            self.calls += 1
            assert messages[0]["content"] == NOTES_SYSTEM
            assert max_tokens == NOTES_MAX_TOKENS
            assert model == "openai/gpt-5.6-luna"
            assert reasoning_effort == "none"
            return json.dumps({"notes": [decision(), decision(context="absent", error="absent", replacement="invented")]})

    model = Model(None, "test", notes_model="openai/gpt-5.6-luna")
    assert await model.complete_notes("I am agree with you.") == [Correction("I am agree with you.", "I agree with you.")]
    assert model.calls == 1
    assert await model.complete_notes("I agree with you.") == []
    assert model.calls == 2


@pytest.mark.asyncio
async def test_none_reasoning_is_sent_only_for_live_corrections():
    class Completions:
        def __init__(self):
            self.calls = []

        async def create(self, **kwargs):
            self.calls.append(kwargs)
            content = '{"notes":[]}' if kwargs["messages"][0]["content"] == NOTES_SYSTEM else '{"reply":"OK"}'
            return SimpleNamespace(
                choices=[SimpleNamespace(finish_reason="stop", message=SimpleNamespace(content=content))],
                usage=None,
            )

    completions = Completions()
    client = SimpleNamespace(base_url="https://openrouter.ai/api/v1", chat=SimpleNamespace(completions=completions))
    model = OpenAiChatModel(client, "openai/gpt-5.6-luna")

    assert await model.complete_notes("I agree with you.") == []
    assert await model.complete_reply([], "Hi") == "OK"
    assert await model.complete_json("review", "data") == '{"reply":"OK"}'

    notes, reply, review = completions.calls
    assert notes["extra_body"] == {"reasoning": {"effort": "none"}}
    assert notes["max_completion_tokens"] == NOTES_MAX_TOKENS
    assert reply["extra_body"] is None
    assert review["extra_body"] is None


@pytest.mark.asyncio
async def test_live_notes_artifact_markers_reject_an_overlapping_candidate():
    class Model(OpenAiChatModel):
        async def _complete(self, messages, temperature, max_tokens=None, model=None, retry=True,
                            reasoning_effort=None):
            return json.dumps({"speech_artifacts": ["is also is"], "notes": [decision(
                context=transcript, error="is also is", replacement="is also",
            )]})

    transcript = "The price is also is high for students"
    assert await Model(None, "test").complete_notes(transcript) == []


@pytest.mark.asyncio
async def test_notes_provider_failure_does_not_fail_the_voice_turn():
    class Model(OpenAiChatModel):
        async def _complete(self, messages, temperature, max_tokens=None, model=None, retry=True,
                            reasoning_effort=None):
            raise RuntimeError("missing provider choices")

    assert await Model(None, "test").complete_notes("I am agree with you.") == []


@pytest.mark.asyncio
async def test_model_retries_one_empty_provider_envelope():
    class Completions:
        calls = 0

        async def create(self, **kwargs):
            self.calls += 1
            if self.calls == 1:
                return SimpleNamespace(choices=None, usage=None)
            return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content='{"reply":"OK"}'))], usage=None)

    completions = Completions()
    client = SimpleNamespace(chat=SimpleNamespace(completions=completions))
    assert await OpenAiChatModel(client, "test")._complete([{"role": "user", "content": "Hi"}], 0) == '{"reply":"OK"}'
    assert completions.calls == 2


@pytest.mark.asyncio
async def test_empty_provider_envelope_has_one_total_retry():
    class Completions:
        calls = 0

        async def create(self, **kwargs):
            self.calls += 1
            return SimpleNamespace(choices=None, usage=None)

    completions = Completions()
    client = SimpleNamespace(chat=SimpleNamespace(completions=completions))
    assert await OpenAiChatModel(client, "test").complete_notes("I am agree with you.") == []
    assert completions.calls == 2


@pytest.mark.asyncio
async def test_http_retry_followed_by_empty_envelope_stays_at_two_attempts():
    import httpx

    class Completions:
        calls = 0

        async def create(self, **kwargs):
            self.calls += 1
            if self.calls == 1:
                response = httpx.Response(503, request=httpx.Request("POST", "https://example.test/chat"))
                raise httpx.HTTPStatusError("unavailable", request=response.request, response=response)
            return SimpleNamespace(choices=None, usage=None)

    completions = Completions()
    client = SimpleNamespace(chat=SimpleNamespace(completions=completions))
    assert await OpenAiChatModel(client, "test").complete_notes("I am agree with you.") == []
    assert completions.calls == 2


@pytest.mark.asyncio
async def test_notes_metrics_distinguish_empty_invalid_and_filtered_outputs():
    class Model(OpenAiChatModel):
        response = ""

        async def _complete(self, messages, temperature, max_tokens=None, model=None, retry=True,
                            reasoning_effort=None, purpose="reply"):
            return self.response

    metrics = MemoryMetricsStore(MetricRates())
    model = Model(None, "test", metrics=metrics)
    pipeline = ClipPipeline(FakeStt([]), model, FakeTts(), MemoryDialogueStore(40, 86400), metrics)
    for response in ('{"notes":[]}', 'not json', '{"notes":[{"context":"I am agree with you."}]}'):
        model.response = response
        assert await pipeline.complete_live_notes("I am agree with you.") == []
    outcomes = (await metrics.snapshot())["corrections"]
    assert {key: value["count"] for key, value in outcomes.items()} == {
        "empty": 1, "invalid_json": 1, "filtered": 1,
    }


@pytest.mark.asyncio
async def test_model_rejects_truncated_json_before_parsing():
    class Completions:
        async def create(self, **kwargs):
            return SimpleNamespace(
                choices=[SimpleNamespace(finish_reason="length", message=SimpleNamespace(content='{"partial":'))],
                usage=None,
            )

    client = SimpleNamespace(chat=SimpleNamespace(completions=Completions()))
    with pytest.raises(ValueError, match="completion token limit"):
        await OpenAiChatModel(client, "test")._complete([{"role": "user", "content": "Hi"}], 0)


@pytest.mark.asyncio
async def test_review_schema_requires_capable_openrouter_provider():
    class Completions:
        arguments = None

        async def create(self, **kwargs):
            self.arguments = kwargs
            return SimpleNamespace(
                choices=[SimpleNamespace(finish_reason="stop", message=SimpleNamespace(content='{"ok":true}'))],
                usage=None,
            )

    completions = Completions()
    client = SimpleNamespace(base_url="https://openrouter.ai/api/v1", chat=SimpleNamespace(completions=completions))
    schema = {"type": "json_schema", "json_schema": {"name": "test", "strict": True, "schema": {"type": "object"}}}
    await OpenAiChatModel(client, "default").complete_json("system", "data", response_format=schema, model="backup")
    assert completions.arguments["model"] == "backup"
    assert completions.arguments["response_format"] == schema
    assert completions.arguments["extra_body"] == {"provider": {"require_parameters": True}}


def test_parse_review_orders_grammar_then_vocabulary() -> None:
    raw = json.dumps(
        {
            "steps": [
                {
                    "metric": "Vocabulary",
                    "score": 84,
                    "lead": "Words",
                    "bullets": ["verbs"],
                    "examples": [],
                    "tip": "Tip v",
                },
                {
                    "metric": "Grammar",
                    "score": 78,
                    "lead": "Tenses",
                    "bullets": ["Past"],
                    "examples": [{"original": "I go", "improved": "I went"}],
                    "tip": "Tip g",
                },
            ]
        }
    )
    parsed = parse_review(raw)
    assert [step["metric"] for step in parsed["steps"]] == ["Grammar", "Vocabulary"]
    assert parsed["steps"][0]["score"] == 78
