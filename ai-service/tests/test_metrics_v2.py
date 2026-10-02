import pytest

from app.metrics_v2 import MemoryMetricsV2, client_for, cost_micro


def test_client_for_prefixes() -> None:
    assert client_for("tg-ChatId(chatId=1)") == "telegram"
    assert client_for("app-u-1", "android") == "android"
    assert client_for("app-u-1", "ios") == "ios"
    assert client_for("app-u-1", "desktop") == "desktop"
    assert client_for("app-u-1") == "unknown"
    assert client_for("app-u-1", "browser") == "unknown"
    assert client_for("other") == "unknown"


def test_cost_micro_ignores_missing_and_negative() -> None:
    assert cost_micro(None) is None
    assert cost_micro(-1) is None
    assert cost_micro(0.000001) == 1
    assert cost_micro("0.5") == 500_000


@pytest.mark.asyncio
async def test_v2_keeps_money_tokens_and_clients_apart() -> None:
    store = MemoryMetricsV2()
    await store.record_llm("tg-1", "reply", "openai/gpt", 10, 4, 1500)
    await store.record_llm("tg-1", "notes", "openai/gpt", 3, 1, None)
    await store.record_tts("tg-1", "hexgrad/kokoro", 20, 800)
    await store.record_stt("tg-1", "whisper-large-v3", 1.5)
    await store.record_turn("tg-1")
    await store.record_realtime("app-1", "gpt-realtime", {
        "in_text": 1, "in_audio": 2, "out_text": 3, "out_audio": 4, "cached_text": 5, "cached_audio": 6,
    }, platform="ios")
    await store.record_action("tg-1", "text")
    await store.record_error("tg-1", "corrections", "deadline")
    snap = await store.snapshot()
    by_client = {item["client"]: item for item in snap["clients"]}
    telegram = by_client["telegram"]
    assert telegram["costMicro"] == 2300
    assert telegram["costCurrency"] == "USD"
    assert telegram["promptTokens"] == 13
    assert telegram["completionTokens"] == 5
    assert telegram["calls"] == 3
    assert telegram["ttsChars"] == 20
    assert telegram["sttSeconds"] == 1.5
    assert telegram["dau"] == 1
    assert telegram["actions"]["text"] == 1
    assert telegram["errors"]["corrections:deadline"] == 1
    assert telegram["chats"] == [{"session": "tg-1", "turns": 1}]
    assert telegram["realtimeInAudio"] == 0
    ios = by_client["ios"]
    assert ios["costMicro"] == 0
    assert ios["costCurrency"] == ""
    assert ios["realtimeOutAudio"] == 4
    assert ios["dau"] == 1
    assert "android" in by_client
    text = store.prometheus({**snap, "day": "2026-10-02"})
    assert 'client="telegram"' in text
    assert "speaking_cost_micro" in text
    assert 'client="ios"' in text
    assert "speaking_cost_micro{client=\"ios\"" not in text
