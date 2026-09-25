# Clip / session HTTP contract

Canonical DTOs: `cmp/server/.../ai/ClipDtos.kt`. Python: `ai-service/app/main.py`, job payload `jobs.py`. Ktor **does not** expose these paths publicly. Telegram uses `HttpClipClient` server-side. Every `/v1/*` call needs header `X-Internal-Token` matching `AI_INTERNAL_TOKEN` on both hosts. `/health` is open.

Base URL examples: local uvicorn `http://127.0.0.1:8090`, two-host DEV/prod `http://<ai-server>:$AI_SERVICE_PORT`.

## `POST /v1/sessions` → 201

JSON optional: `{ "sessionId": "tg-123" }`. Empty/missing body → new UUID. Same id again is **get-or-create** (Redis: refresh TTL; memory: keep live session).

Response:

```json
{ "sessionId": "tg-123", "greeting": { "text": "Hey! 👋\nI'm Speaky..." } }
```

Telegram always sends the chat-scoped id so Redis history sticks to one chat.

## `GET /v1/sessions/{sessionId}/greeting/audio` → 200 `audio/ogg` or 404

Synthesizes `GREETING_VOICE_TEXT` once (cached in process).

## `POST /v1/clips` multipart → 202 `{ "jobId" }`

Fields: `sessionId` (form), `audio` (file). 404 unknown session, 400 empty audio.

## `GET /v1/clips/{jobId}` → 200 or 404

`status`: `pending` | `ok` | `error`.

On `ok`, Kotlin reads `result.corrections` (falls back to `result.notes` when `corrections` is empty or absent), `result.transcript`, fallback top-level `transcript`. Extra fields `replyText`, `timingsMs` are ignored by Ktor (`ignoreUnknownKeys`).

`corrections` is the typed list (`kind`: `grammar` | `word` | `natural`, max 3, sorted by that priority). `notes` is the same list as `wrong|||better` strings, kept so an older Ktor still works when `ai-server` is redeployed first. Either deploy order is safe.

Notes on `ok`:

```json
{
  "jobId": "...",
  "status": "ok",
  "result": {
    "notes": ["I was in Turkey|||I went to Turkey"],
    "corrections": [{ "wrong": "I was in Turkey", "better": "I went to Turkey", "kind": "grammar" }],
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
