import json
from types import SimpleNamespace

import pytest

from app.config import Settings
from app.dialogue import MemoryDialogueStore
from app.llm import (
    CompletionTokenLimitError, Correction, NOTES_MAX_TOKENS, NOTES_SYSTEM,
    REPLY_RETRY_MAX_TOKENS, OpenAiChatModel, parse_corrections, parse_reply,
    read_generation_time_ms, read_reasoning_tokens,
)
from app.load_test import bind_load_test, reset_load_test
from app.roleplay import ROLEPLAY_CONTEXT_PREFIX
from app.metrics import MemoryMetricsStore, MetricRates
from app.pipeline import ClipPipeline
from app.review import parse_review
from tests.conftest import FakeStt, FakeTts


def decision(context="I am agree with you.", error="am agree", replacement="agree", kind="grammar", **overrides):
    return {
        "context": context, "error": error, "replacement": replacement, "kind": kind,
        "vocabularyCategory": "naturalness_error" if kind == "natural" else None,
        "reason": "Agree is a verb and does not take am.", "confidence": "high",
        "definitely_wrong": True, "already_correct": False, "meaning_preserved": True,
        "is_spoken_language_artifact": False, "is_asr_uncertain": False,
        "worth_showing": True, "understandable_alone": True, **overrides,
    }


def parse(notes, transcript="I am agree with you."):
    return parse_corrections(json.dumps({"notes": notes}), transcript)


def test_live_notes_keep_independent_syntax_and_lexical_errors_in_grammar():
    transcript = "I am agree with you. I did a decision yesterday."
    grammar = decision("I am agree with you.", "am agree", "agree")
    natural = decision("I did a decision yesterday.", "did a decision", "made a decision", "grammar",
                       reason="Здесь используют другую форму.")
    assert parse([natural, grammar], transcript) == [
        Correction("I am agree with you.", "I agree with you."),
        Correction("I did a decision yesterday.", "I made a decision yesterday.", "grammar",
                   "Здесь используют другую форму."),
    ]


def test_live_lexical_error_requires_wrong_original_and_preserved_meaning():
    transcript = "I did a decision yesterday."
    note = decision(transcript, "did a decision", "made a decision", "grammar", reason="Здесь говорят make a decision.")
    assert len(parse([note], transcript)) == 1
    for field, value in (("meaning_preserved", False), ("already_correct", True),
                         ("definitely_wrong", False), ("vocabularyCategory", "word_upgrade")):
        assert parse([{**note, field: value}], transcript) == []
    assert parse([{**note, "kind": "alternative", "vocabularyCategory": "word_upgrade"}], transcript) == []


def test_live_grammar_accepts_wrong_single_word_but_rejects_long_rewrite():
    transcript = "I borrowed him my book."
    note = decision(transcript, "borrowed", "lent", "grammar", reason="Здесь нужен lent.")
    assert parse([note], transcript)[0].better == "I lent him my book."
    assert parse([{**note, "error": "I borrowed him my book", "replacement": "I gave him my book to use"}], transcript) == []


def test_live_grammar_accepts_function_word_repair():
    transcript = "I need to speak with different people on the English."
    note = decision(transcript, "on the English", "in English", "grammar", reason="Здесь нужен in English.")
    assert parse([note], transcript)[0].better == "I need to speak with different people in English."


def test_local_edit_does_not_copy_words_left_outside_its_span():
    transcript = "She explained me the problem yesterday."
    unsafe = decision(transcript, "explained me", "explained the problem to me")
    assert parse([unsafe], transcript) == []
    safe = decision(transcript, "explained me the problem", "explained the problem to me")
    assert parse([safe], transcript) == [
        Correction(transcript, "She explained the problem to me yesterday."),
    ]


def test_take_a_decision_is_not_a_vocabulary_error():
    transcript = "I took a decision to cancel the trip."
    note = decision(transcript, "took a decision", "made a decision", "word")
    assert parse([note], transcript) == []


def test_parse_reply_reads_reply():
    assert parse_reply('{"reply":"Nice — what did you buy?"}') == "Nice — what did you buy?"


def test_generation_time_uses_openrouter_metadata_only_when_valid():
    assert read_generation_time_ms(SimpleNamespace(openrouter_metadata={"generation_time": 2500})) == 2500
    assert read_generation_time_ms(SimpleNamespace(model_extra={"openrouter_metadata": {"generation_time": 1000}})) == 1000
    for value in (None, 0, -1, True, "nan", "inf", "bad"):
        assert read_generation_time_ms(SimpleNamespace(openrouter_metadata={"generation_time": value})) is None


@pytest.mark.asyncio
async def test_openrouter_uses_generation_metadata_for_telegram_tps(monkeypatch):
    from app import llm
    from app.metrics_v2 import bind_metrics, reset_metrics
    from app.operational_metrics import LlmGenerationMetrics

    class Completions:
        async def create(self, **kwargs):
            assert kwargs["extra_headers"] == {"X-OpenRouter-Metadata": "enabled"}
            return SimpleNamespace(
                choices=[SimpleNamespace(finish_reason="stop", message=SimpleNamespace(content='{"reply":"OK"}'))],
                usage=SimpleNamespace(prompt_tokens=200, completion_tokens=100),
                openrouter_metadata={"generation_time": 2000},
            )

    metrics = LlmGenerationMetrics()
    monkeypatch.setattr(llm, "LLM_GENERATION_METRICS", metrics)
    client = SimpleNamespace(base_url="https://openrouter.ai/api/v1", chat=SimpleNamespace(completions=Completions()))
    token = bind_metrics("tg-1")
    try:
        assert await OpenAiChatModel(client, "test").complete_reply([], "Hi") == "OK"
    finally:
        reset_metrics(token)
    assert 'speaking_llm_generation_tokens_total{client="telegram"} 100' in metrics.prometheus()
    assert 'speaking_llm_generation_seconds_total{client="telegram"} 2.0' in metrics.prometheus()


@pytest.mark.asyncio
async def test_load_test_logs_provider_tokens_without_logging_normal_reply(caplog) -> None:
    class Completions:
        async def create(self, **kwargs):
            return SimpleNamespace(
                choices=[SimpleNamespace(finish_reason="stop", message=SimpleNamespace(content='{"reply":"OK"}'))],
                usage=SimpleNamespace(prompt_tokens=123, completion_tokens=45),
            )

    client = SimpleNamespace(chat=SimpleNamespace(completions=Completions()))
    model = OpenAiChatModel(client, "test-model")
    with caplog.at_level("INFO", logger="app.llm"):
        token = bind_load_test(True)
        try:
            assert await model.complete_reply([], "Hi") == "OK"
        finally:
            reset_load_test(token)
        assert await model.complete_reply([], "Hi") == "OK"

    usage_lines = [record.message for record in caplog.records if "load_test_llm_usage" in record.message]
    assert len(usage_lines) == 1
    assert "purpose=reply" in usage_lines[0]
    assert "prompt_tokens=123 completion_tokens=45 usage_present=True" in usage_lines[0]


@pytest.mark.asyncio
async def test_reply_retries_malformed_json_once() -> None:
    class Model(OpenAiChatModel):
        def __init__(self):
            super().__init__(None, "test")
            self.calls = 0

        async def _complete(self, messages, *args, **kwargs):
            self.calls += 1
            return '{"reply":"broken' if self.calls == 1 else '{"reply":"OK"}'

    model = Model()
    assert await model.complete_reply([], "Hi") == "OK"
    assert model.calls == 2


@pytest.mark.asyncio
async def test_reply_stops_after_second_malformed_json() -> None:
    class Model(OpenAiChatModel):
        def __init__(self):
            super().__init__(None, "test")
            self.calls = 0

        async def _complete(self, messages, *args, **kwargs):
            self.calls += 1
            return '{"reply":"broken'

    model = Model()
    with pytest.raises(json.JSONDecodeError):
        await model.complete_reply([], "Hi")
    assert model.calls == 2


@pytest.mark.asyncio
async def test_reply_retries_token_limit_with_larger_budget() -> None:
    class Model(OpenAiChatModel):
        def __init__(self):
            super().__init__(None, "test")
            self.limits = []

        async def _complete(self, messages, *args, **kwargs):
            self.limits.append(kwargs["max_tokens"])
            if len(self.limits) == 1:
                raise CompletionTokenLimitError("llm response reached completion token limit")
            return '{"reply":"OK"}'

    model = Model()
    assert await model.complete_reply([], "Hi") == "OK"
    assert model.limits == [None, REPLY_RETRY_MAX_TOKENS]


@pytest.mark.asyncio
async def test_roleplay_uses_counterpart_system_instead_of_teacher_persona():
    class CaptureModel(OpenAiChatModel):
        async def _complete(self, messages, *args, **kwargs):
            self.messages = messages
            return '{"reply":"Tell me more about your experience."}'

    model = CaptureModel(None, "test")
    await model.complete_reply([], "I led a project.", ROLEPLAY_CONTEXT_PREFIX + '{"kind":"job"}')
    assert "employer's interviewer" in model.messages[0]["content"]
    assert "You are Speaky" not in model.messages[0]["content"]
    await model.complete_reply([], "I led a project.")
    assert "You are Speaky" in model.messages[0]["content"]


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


@pytest.mark.parametrize("field", [key for key in decision() if key not in {"already_correct", "meaning_preserved"}])
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


def test_three_grammar_candidates_do_not_drop_phrase_suggestion():
    notes = [decision(context=f"I fragment {i}", error=f"fragment {i}", replacement=f"fixed {i}")
             for i in range(3)]
    notes.append(decision(context="I made a photo", error="made a photo", replacement="took a photo",
                          kind="grammar",
                          definitely_wrong=True, already_correct=False, meaning_preserved=True,
                          reason="Здесь естественно took a photo."))
    corrections = parse(notes, "I fragment 0 and I fragment 1 and I fragment 2 and I made a photo")
    assert [item.kind for item in corrections] == ["grammar", "grammar", "grammar", "grammar"]


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
async def test_assessment_completion_log_has_limits_usage_and_no_content(caplog):
    class Completions:
        async def create(self, **kwargs):
            assert kwargs["extra_body"] is None
            assert kwargs["max_completion_tokens"] == 1200
            return SimpleNamespace(
                choices=[SimpleNamespace(finish_reason="length", message=SimpleNamespace(content="private output"))],
                usage=SimpleNamespace(prompt_tokens=60, completion_tokens=1200,
                                      completion_tokens_details=SimpleNamespace(reasoning_tokens=900)),
            )

    client = SimpleNamespace(chat=SimpleNamespace(completions=Completions()))
    with caplog.at_level("INFO", logger="app.llm"):
        with pytest.raises(ValueError, match="completion token limit"):
            await OpenAiChatModel(client, "openai/gpt-5.6-luna").complete_json(
                "private prompt", "private transcript", max_tokens=1200,
                operation="onboarding_assessment",
            )
    log = caplog.text
    assert "operation=onboarding_assessment" in log
    assert "model=openai/gpt-5.6-luna max_completion_tokens=1200" in log
    assert "finish_reason=length prompt_tokens=60 completion_tokens=1200 reasoning_tokens=900" in log
    assert "private prompt" not in log
    assert "private transcript" not in log
    assert "private output" not in log


def test_reasoning_usage_is_optional():
    assert read_reasoning_tokens(SimpleNamespace(usage=None)) is None
    assert read_reasoning_tokens(SimpleNamespace(usage={"completion_tokens_details": {"reasoning_tokens": 7}})) == 7


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
