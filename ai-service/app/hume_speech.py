"""Bounded EVI speech adapter. Sonnet remains the conversation owner."""
from __future__ import annotations

import asyncio
import base64
import json
from urllib.parse import urlencode

import websockets

from app.provider_call_log import logged_provider_call
from app.kora_delivery import delivery_prompt
from app.tts import TtsAudio


class HumeSpeech:
    def __init__(self, api_key: str, config_id: str, config_version: str = "0",
                 ffmpeg_bin: str = "ffmpeg", timeout: float = 40) -> None:
        self.api_key, self.config_id, self.config_version = api_key, config_id, config_version
        self.ffmpeg_bin, self.timeout = ffmpeg_bin, timeout

    async def synthesize(self, text: str, *, history=(), user="Let's talk.", speed: float = 1.0,
                         guided: bool = True, warm: bool = False) -> TtsAudio:
        if isinstance(speed, bool) or speed not in (0.8, 0.9, 1.0):
            raise ValueError("unsupported speech speed")
        return await logged_provider_call(
            lambda: self._synthesize(text, history, user, speed, guided, warm), kind="tts", provider="hume", model="evi-3",
        )

    async def _synthesize(self, text, history, user, speed, guided, warm) -> TtsAudio:
        url = "wss://api.hume.ai/v0/evi/chat?" + urlencode({
            "config_id": self.config_id, "config_version": self.config_version,
        })
        chunks, parts = [], []
        total = 0
        ended = False
        try:
            async with asyncio.timeout(self.timeout):
                # Header auth avoids placing credentials in exception URLs.
                async with websockets.connect(url, additional_headers={"X-Hume-Api-Key": self.api_key},
                                              max_size=8 * 1024 * 1024, open_timeout=10) as socket:
                    for message in [
                        {"type": "pause_assistant_message"},
                        {"type": "session_settings", "system_prompt":
                            delivery_prompt(history, user, text) if guided else
                            ("You are a warm, engaged adult talking with one person. Speak the supplied "
                             "assistant words exactly. Let the feeling in those words come through: "
                             "curiosity, delight, surprise, or sympathy when the meaning calls for it. "
                             "Vary your energy, pitch, pauses, and emphasis naturally, as in a real "
                             "conversation. Stay grounded; do not sound theatrical or excited all the time. "
                             "Do not add, omit, or change words. " if warm else
                             "You are a warm adult conversation partner. Speak the supplied "
                             "assistant words with subtle, natural changes in energy, pauses, "
                             "and emphasis that fit their meaning. Do not dramatize or add words. ") +
                            "Conversation context: " +
                            json.dumps([{"role": item.role, "content": item.content} for item in history])},
                        {"type": "user_input", "text": user},
                        *([] if guided else [{"type": "assistant_input", "text": text}]),
                        {"type": "resume_assistant_message"},
                    ]:
                        await socket.send(json.dumps(message))
                    async for raw in socket:
                        message = json.loads(raw)
                        kind = message.get("type")
                        if kind == "assistant_message":
                            parts.append(message["message"]["content"])
                        elif kind == "audio_output":
                            chunk = base64.b64decode(message["data"], validate=True)
                            total += len(chunk)
                            if total > 24 * 1024 * 1024:
                                raise ValueError("hume_audio_too_large")
                            chunks.append(chunk)
                        # Length warnings do not terminate synthesis. Still require exact text
                        # and complete audio below; all other provider errors remain fatal.
                        elif kind == "error" and message.get("code") not in {"W0107", "W0108"}:
                            raise ValueError("hume_provider_error")
                        elif kind == "assistant_end":
                            ended = True
                            break
                spoken = " ".join(" ".join(parts).split())
                if not ended or not chunks or not spoken:
                    raise ValueError("hume_incomplete_audio")
                if not guided and spoken != " ".join(text.split()):
                    raise ValueError("hume_transcript_mismatch")
                pcm = b"".join([await self._ffmpeg(chunk, ["-i", "pipe:0", "-f", "s16le", "-ac", "1", "-ar", "48000"])
                                for chunk in chunks])
                ogg = await self._ffmpeg(pcm, ["-f", "s16le", "-ar", "48000", "-ac", "1", "-i", "pipe:0",
                                               *(["-af", f"atempo={speed}"] if speed != 1.0 else []),
                                               "-c:a", "libopus", "-b:a", "32k", "-application", "voip", "-f", "ogg"])
                if not ogg.startswith(b"OggS"):
                    raise ValueError("hume_invalid_ogg")
                return TtsAudio(ogg, "audio/ogg", spoken_text=spoken)
        except asyncio.CancelledError:
            raise
        except Exception as error:
            # Provider errors can carry URLs/headers; retain only the safe class, not their payload.
            raise RuntimeError(f"Hume speech failed ({type(error).__name__})") from None

    async def _ffmpeg(self, payload: bytes, args: list[str]) -> bytes:
        process = await asyncio.create_subprocess_exec(
            self.ffmpeg_bin, "-v", "error", *args, "pipe:1",
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        )
        try:
            stdout, _ = await process.communicate(payload)
            if process.returncode != 0 or not stdout:
                raise ValueError("hume_audio_conversion_failed")
            return stdout
        finally:
            if process.returncode is None:
                process.kill()
                await process.wait()
