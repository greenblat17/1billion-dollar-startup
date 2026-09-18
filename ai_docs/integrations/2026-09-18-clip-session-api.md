# Clip / session HTTP contract

Canonical DTOs: `cmp/server/.../ai/ClipDtos.kt`. Python: `ai-service/app/main.py`, job payload `jobs.py`. Ktor also **proxies** the same paths for Swagger (`AiServiceRoutes.kt`).

Base URL examples: `http://127.0.0.1:8090` (stub or local uvicorn), `http://127.0.0.1:8091` (compose `--profile llm` host port), VPS loopback `127.0.0.1:8090` inside `ai-service` container.

## `POST /v1/sessions` → 201

JSON optional: `{ "sessionId": "tg-123" }`. Empty/missing body → new UUID. Same id again is **get-or-create** (Redis: refresh TTL; memory: keep live session).

Response:

```json
{ "sessionId": "tg-123", "greeting": { "text": "Hey! 👋\nI'm Speaky..." } }
```

Telegram always sends the chat-scoped id so Redis history sticks to one chat.

## `GET /v1/sessions/{sessionId}/greeting/audio` → 200 `audio/ogg` or 404

Real service synthesizes `GREETING_VOICE_TEXT` once (cached in process). Stub returns packaged `greeting.ogg`.

## `POST /v1/clips` multipart → 202 `{ "jobId" }`

Fields: `sessionId` (form), `audio` (file). 404 unknown session, 400 empty audio.

## `GET /v1/clips/{jobId}` → 200 or 404

`status`: `pending` | `ok` | `error`.

On `ok`, Kotlin reads `result.notes`, `result.transcript`, fallback top-level `transcript`. Extra fields `replyText`, `timingsMs` are ignored by Ktor (`ignoreUnknownKeys`).

Notes on `ok`:

```json
{
  "jobId": "...",
  "status": "ok",
  "result": {
    "notes": ["I was in Turkey|||I went to Turkey"],
    "transcript": "I was in Turkey last summer"
  },
  "transcript": "...",
  "replyText": "...",
  "timingsMs": { "stt": 1, "llm": 2, "tts": 3, "total": 6 }
}
```

On `error`: `{ "code": "timeout"|"pipeline_failed", "message": "..." }` (message truncated to 240 chars).

## `GET /v1/clips/{jobId}/audio` → 200 `audio/ogg` or 404

Ktor saves as `reply.ogg` / `greeting.ogg`.

## Session ids

Kotlin: `SessionId` inline value. Telegram: `"tg-$chatId"` (`telegramSessionId`). Negative chat ids become `tg--100` style.

## Redis dialogue

- Env: `REDIS_URL` (compose/VPS `redis://redis:6379/0`). Unset → `MemoryDialogueStore`.
- `DIALOGUE_TTL_SECONDS` default **2592000**.
- `DIALOGUE_MAX_MESSAGES` default **40** (trimmed from the front).
- JSON value: `{ "messages": [ { "role", "content" } ] }` — **assistant content is spoken reply only**, not notes.

Stored history is what the next LLM call sees (plus system prompt). `/start` creates/refreshes the session; it does not wipe Redis history if the key already exists.
