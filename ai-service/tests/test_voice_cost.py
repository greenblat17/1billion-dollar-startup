import httpx
import fakeredis.aioredis
import pytest

from app.voice_cost import VoiceCostLedger, bind_voice_cost, reset_voice_cost
from app.provider_call_log import logged_provider_call
from app import voice_cost


@pytest.mark.asyncio
async def test_tts_price_arrives_later_without_duplicate_call(monkeypatch):
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    ledger = VoiceCostLedger(redis, "https://openrouter.ai/api/v1", "test-key")
    observed = []

    def respond(request):
        observed.append(request)
        if len(observed) == 1:
            return httpx.Response(404)
        return httpx.Response(200, json={"data": {"total_cost": 0.00009}})

    original_client = httpx.AsyncClient
    monkeypatch.setattr(voice_cost.httpx, "AsyncClient",
                        lambda **kwargs: original_client(transport=httpx.MockTransport(respond), **kwargs))
    token = bind_voice_cost(ledger, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    try:
        await logged_provider_call(lambda: _tts_response(), kind="tts", provider="openrouter",
                                   model="x-ai/grok-voice-tts-1.0",
                                   generation_id_of=lambda response: response["generationId"])
    finally:
        reset_voice_cost(token)
    first = (await ledger.batch())[0]
    assert first["priceStatus"] == "pending" and first["revision"] == 1
    ledger = VoiceCostLedger(redis, "https://openrouter.ai/api/v1", "test-key")  # new worker after restart
    assert await ledger.settle_due(first["createdEpoch"] + 16) == 1
    assert (await ledger.batch())[0]["priceStatus"] == "pending"
    assert await ledger.settle_due(first["createdEpoch"] + 61) == 1
    settled = (await ledger.batch())[0]
    assert settled["callId"] == first["callId"]
    assert settled["costMicroUsd"] == 90 and settled["revision"] == 3
    await ledger.acknowledge([{"callId": first["callId"], "revision": 1}])
    assert len(await ledger.batch()) == 1
    await ledger.acknowledge([{"callId": first["callId"], "revision": 3}])
    assert await ledger.batch() == []
    assert len(observed) == 2


async def _tts_response():
    return {"generationId": "gen-1"}


@pytest.mark.asyncio
async def test_llm_and_stt_costs_are_recorded_per_attempt():
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    ledger = VoiceCostLedger(redis, "https://openrouter.ai/api/v1", "test-key")
    token = bind_voice_cost(ledger, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    try:
        for component in ("stt", "llm"):
            await logged_provider_call(lambda: _priced_response(), kind=component, provider="openrouter",
                                       model="test", cost_of=lambda value: value["cost"])
    finally:
        reset_voice_cost(token)
    events = await ledger.batch()
    assert len(events) == 2 and len({event["callId"] for event in events}) == 2
    assert {event["component"] for event in events} == {"stt", "llm"}
    assert all(event["costMicroUsd"] == 123 and event["priceStatus"] == "known" for event in events)


async def _priced_response():
    return {"cost": 123}
