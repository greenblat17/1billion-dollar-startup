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

## Optional onboarding contract (2026-09-28)

All operations below require the existing `X-Internal-Token`. They are internal AI-service endpoints, not public Ktor APIs.

- `POST /internal/onboarding/state`: `{sessionId, requestId, reset?: ""|"start"|"force"}` → `{runId, status, seconds, cefr, resultText, retryAvailable}`. Resolves new/existing eligibility and persists the choice. Call before funnel events. `start` resets incomplete onboarding; `force` also restarts completed/exempt users. Duplicate recent request ids do not reset again.
- `POST /internal/onboarding/actions`: `{sessionId, requestId, runId, action: "begin"|"retry"|"continue"}` → 202 `{jobId}`. Uses the existing status/audio polling endpoints. Stale run ids and duplicate callbacks produce an ignored result.
- `POST /v1/clips` accepts optional `onboardingRunId`, `requestId` and `durationSeconds` multipart fields. A run id requires a request id. Without a run id, the existing clip flow is unchanged. Telegram duration is a fallback when STT duration is absent.
- Onboarding jobs add `result.onboarding` with state and `result.audioAvailable`. Default `audioAvailable` for legacy jobs is true. `replyText` carries the result/error text. `pending` onboarding state means the user can retry result generation; it is distinct from the job's `pending` processing status.
- A failed summary stays text-only (`audioAvailable` false, `/audio` 404) so the client can offer retry without more speech. A completed introduction has audio: the last spoken question. Clients **must not** download audio when `audioAvailable` is false. `onboarding.status == "ignored"` means no Telegram response should be sent. `resultText` is empty on completion; the level stays in `cefr` for the coach and is not a chat message.

Deployment order: AI-service then Ktor. New AI-service does not enroll old clients automatically; enrollment is explicitly resolved by the updated Telegram client.
