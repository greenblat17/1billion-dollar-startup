from __future__ import annotations

import os
from dataclasses import dataclass


def _env(name: str, default: str | None = None) -> str | None:
    value = os.getenv(name)
    if value is None:
        return default
    trimmed = value.strip()
    return trimmed if trimmed else default


def _int_env(name: str, default: int) -> int:
    raw = _env(name)
    if raw is None:
        return default
    return int(raw)


def _float_env(name: str, default: float) -> float:
    raw = _env(name)
    if raw is None:
        return default
    return float(raw)


@dataclass(frozen=True)
class Settings:
    groq_api_key: str | None
    openai_api_key: str | None
    openai_base_url: str
    groq_base_url: str
    stt_model: str
    llm_model: str
    tts_model: str
    tts_voice: str
    tts_response_format: str
    ffmpeg_bin: str
    redis_url: str | None
    dialogue_ttl_seconds: int
    dialogue_max_messages: int
    job_ttl_seconds: int
    pipeline_timeout_seconds: float
    log_level: str
    ai_internal_token: str | None
    openai_realtime_api_key: str | None
    notes_model: str = "openai/gpt-5.6-luna"
    onboarding_review_model: str | None = None
    onboarding_review_fallback_model: str | None = None
    tts_provider: str = "openrouter"
    deepgram_api_key: str | None = None
    tts_output_format: str = "ogg"
    tts_speed: float = 0.9
    log_file: str = ""
    hume_api_key: str | None = None
    hume_config_id: str | None = None
    hume_config_version: str = "0"
    kora_model: str = "anthropic/claude-sonnet-4.6"
    kora_tts_model: str = "google/gemini-3.1-flash-tts-preview"
    kora_tts_voice: str = "Leda"
    kora_h_enabled: bool = False
    kora_h_allowlist: str = ""

    @staticmethod
    def from_env() -> Settings:
        return Settings(
            groq_api_key=_env("GROQ_API_KEY"),
            openai_api_key=_env("OPENAI_API_KEY"),
            openai_base_url=_env("OPENAI_BASE_URL", "https://openrouter.ai/api/v1") or "https://openrouter.ai/api/v1",
            groq_base_url=_env("GROQ_BASE_URL", "https://api.groq.com/openai/v1") or "https://api.groq.com/openai/v1",
            stt_model=_env("STT_MODEL", "whisper-large-v3") or "whisper-large-v3",
            llm_model=_env("LLM_MODEL", "openai/gpt-5.6-luna") or "openai/gpt-5.6-luna",
            tts_model=_env("TTS_MODEL", "hexgrad/kokoro-82m") or "hexgrad/kokoro-82m",
            tts_voice=_env("TTS_VOICE", "af_heart") or "af_heart",
            tts_response_format=_env("TTS_RESPONSE_FORMAT", "mp3") or "mp3",
            ffmpeg_bin=_env("FFMPEG_BIN", "ffmpeg") or "ffmpeg",
            redis_url=_env("REDIS_URL"),
            dialogue_ttl_seconds=_int_env("DIALOGUE_TTL_SECONDS", 2592000),
            dialogue_max_messages=_int_env("DIALOGUE_MAX_MESSAGES", 40),
            job_ttl_seconds=_int_env("JOB_TTL_SECONDS", 600),
            pipeline_timeout_seconds=_float_env("PIPELINE_TIMEOUT_SECONDS", 60),
            log_level=_env("LOG_LEVEL", "INFO") or "INFO",
            ai_internal_token=_env("AI_INTERNAL_TOKEN"),
            openai_realtime_api_key=_env("OPENAI_REALTIME_API_KEY"),
            notes_model=_env("NOTES_MODEL", "openai/gpt-5.6-luna") or "openai/gpt-5.6-luna",
            onboarding_review_model=_env("ONBOARDING_REVIEW_MODEL"),
            onboarding_review_fallback_model=_env("ONBOARDING_REVIEW_FALLBACK_MODEL"),
            tts_provider=_env("TTS_PROVIDER", "openrouter") or "openrouter",
            deepgram_api_key=_env("DEEPGRAM_API_KEY"),
            tts_output_format=_env("TTS_OUTPUT_FORMAT", "ogg") or "ogg",
            tts_speed=_float_env("TTS_SPEED", 0.9),
            log_file=_env("LOG_FILE", "") or "",
            hume_api_key=_env("HUME_API_KEY"),
            hume_config_id=_env("HUME_CONFIG_ID"),
            hume_config_version=_env("HUME_CONFIG_VERSION", "0") or "0",
            kora_model=_env("KORA_MODEL", "anthropic/claude-sonnet-4.6") or "anthropic/claude-sonnet-4.6",
            kora_tts_model=_env("KORA_TTS_MODEL", "google/gemini-3.1-flash-tts-preview") or "google/gemini-3.1-flash-tts-preview",
            kora_tts_voice=_env("KORA_TTS_VOICE", "Leda") or "Leda",
            kora_h_enabled=_env("KORA_H_ENABLED", "false").lower() == "true",
            kora_h_allowlist=_env("KORA_H_ALLOWLIST", "") or "",
        )
