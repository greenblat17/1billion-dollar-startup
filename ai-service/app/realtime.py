from __future__ import annotations

import logging
from typing import Protocol

import httpx

from app.retry import once_on_retryable

logger = logging.getLogger(__name__)

REALTIME_MODEL = "gpt-realtime"
TOPICS = {"Everyday", "Work", "Travel"}
VOICES = {"marin", "cedar"}

SPEAKY_REALTIME_INSTRUCTIONS = """You are Speaky, a warm English conversation partner helping the user practice speaking.

Speak only English. Keep it to 2–4 short sentences.
Stay slightly above their level. Ask a natural follow-up so the talk continues.
Do not lecture, list grammar rules, give CEFR scores, or switch language unless they ask.
Do not mention errors, corrections, or the transcript as a quote.
Greet the user and start the conversation yourself as soon as the session begins.
"""

TOPIC_HINTS = {
    "Everyday": "Today's topic is everyday life.",
    "Work": "Today's topic is work and career.",
    "Travel": "Today's topic is travel.",
}


class RealtimeGateway(Protocol):
    async def start_call(self, sdp: str, topic: str, voice: str) -> tuple[str, str | None]: ...


class OpenAiRealtimeGateway:
    def __init__(
        self,
        api_key: str,
        base_url: str = "https://api.openai.com/v1",
        transport: httpx.AsyncBaseTransport | httpx.BaseTransport | None = None,
    ) -> None:
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")
        self._transport = transport

    def _http(self) -> httpx.AsyncClient:
        return httpx.AsyncClient(timeout=30.0, transport=self._transport)

    async def start_call(self, sdp: str, topic: str, voice: str) -> tuple[str, str | None]:
        session = {
            "type": "realtime",
            "model": REALTIME_MODEL,
            "instructions": _instructions(topic),
            "output_modalities": ["audio"],
            "audio": {
                "input": {
                    "turn_detection": {"type": "semantic_vad"},
                    "transcription": {"model": "gpt-4o-mini-transcribe"},
                },
                "output": {"voice": voice},
            },
        }
        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }

        async def mint() -> httpx.Response:
            async with self._http() as http:
                return await http.post(
                    f"{self._base_url}/realtime/client_secrets",
                    headers=headers,
                    json={"session": session},
                )

        minted = await once_on_retryable(mint)
        _raise_openai(minted, self._api_key)
        payload = minted.json()
        ephemeral = str(payload.get("value") or payload.get("client_secret", {}).get("value") or "")
        if not ephemeral:
            raise RuntimeError("realtime client_secrets missing value")

        offer = sdp_for_openai(sdp)

        async def exchange() -> httpx.Response:
            async with self._http() as http:
                return await http.post(
                    f"{self._base_url}/realtime/calls",
                    headers={
                        "Authorization": f"Bearer {ephemeral}",
                        "Content-Type": "application/sdp",
                    },
                    content=offer.encode("utf-8"),
                )

        answered = await once_on_retryable(exchange)
        _raise_openai(answered, self._api_key, ephemeral, sdp_bytes=len(offer.encode("utf-8")))
        call_id = _call_id(answered.headers.get("location") or answered.headers.get("Location"))
        return answered.text, call_id


def sdp_for_openai(sdp: str) -> str:
    if sdp.endswith("\r\n"):
        return sdp
    if sdp.endswith("\n"):
        return sdp[:-1] + "\r\n"
    return sdp + "\r\n"


def _raise_openai(response: httpx.Response, *secrets: str, sdp_bytes: int | None = None) -> None:
    if response.is_success:
        return
    body = response.text or ""
    for secret in secrets:
        if secret:
            body = body.replace(secret, "[redacted]")
    extra = f" sdp_bytes={sdp_bytes}" if sdp_bytes is not None else ""
    logger.warning(
        "openai realtime HTTP %s %s%s body=%s",
        response.status_code,
        response.request.url.path,
        extra,
        body[:2000],
    )
    response.raise_for_status()


def _instructions(topic: str) -> str:
    hint = TOPIC_HINTS.get(topic, "")
    if hint:
        return f"{SPEAKY_REALTIME_INSTRUCTIONS}\n{hint}"
    return SPEAKY_REALTIME_INSTRUCTIONS


def _call_id(location: str | None) -> str | None:
    if not location:
        return None
    return location.rstrip("/").split("/")[-1] or None
