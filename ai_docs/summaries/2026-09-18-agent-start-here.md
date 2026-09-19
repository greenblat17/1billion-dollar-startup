# Agent start here (this branch)

Read this first, then the linked files. Product intent: `ai_docs/product.md`. MVP plan is aspirational; **shipped code is Telegram voice + STT/LLM/TTS**, not a mobile speaking session.

Next work on `feature/back-for-mobile`: backend slice «бэк готов» — [`plans/mvp-backend-plan.md`](../plans/mvp-backend-plan.md). Wider backlog (stores, OAuth, dual-host): [`plans/2026-09-19-mobile-mvp-backend.md`](../plans/2026-09-19-mobile-mvp-backend.md). Do not deploy that branch to the prod bot host.

## What to change for which job

| Job | Touch |
| --- | --- |
| Telegram UX, queue, webhook, session ids | `cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/telegram/`, `speaking/` |
| Clip HTTP client / OpenAPI proxy | `cmp/server/.../ai/` |
| Server env, TLS, webhook vs no-bot | `AppConfig.kt`, `Application.kt` |
| STT / LLM / TTS / Redis dialogue | `ai-service/app/` |
| Local compose / VPS scripts | `infra/` |
| CI / GitHub secret **names** | `.github/workflows/` |
| Compose Multiplatform UI | `cmp/app/` — Releva mock UI (Welcome/Home/Call/Review Grammar+Vocabulary/Profile), mock data, not wired to clips. History and pronunciation/fluency/speed screens deferred. |

## Runtime path that actually talks

```text
Telegram voice
  → Ktor POST /telegram/webhook (TLS, secret header)
  → SessionClipQueue (max 3 in-flight per session)
  → HttpClipClient → FastAPI ai-service
       STT Groq → LLM OpenRouter JSON → TTS → ffmpeg OGG
       Redis key session:{id} (or in-memory if REDIS_URL unset)
  → Telegram text quote (corrections) + sendVoice
```

Gradle root is **`cmp/`**. Before `./gradlew`, `GRADLE_USER_HOME=$HOME/.gradle`.

## Docs map

- **System diagrams:** `ai_docs/architecture.md`
- **Deploy / VPS / CI:** `ai_docs/deploy.md`
- Architecture notes: `ai_docs/architecture/2026-09-18-system.md`
- Do not touch: `ai_docs/architecture/2026-09-18-agent-guardrails.md`
- HTTP contract (Telegram clips): `ai_docs/integrations/2026-09-18-clip-session-api.md`
- HTTP contract (CMP screens): `ai_docs/integrations/2026-09-18-mobile-api.md`
- MVP backend («бэк готов»): `ai_docs/plans/mvp-backend-plan.md`
- Wider mobile backend backlog: `ai_docs/plans/2026-09-19-mobile-mvp-backend.md`
- Telegram + notes: `ai_docs/integrations/2026-09-18-telegram.md`
- Run / env / CI: `ai_docs/integrations/2026-09-18-run-and-ci.md`

`ai_docs/researches/2026-09-14-stt-llm-tts-ai-service.md` is **stale** (mentions polling bot, empty `ai-service/`, old DTO). Prefer current files above.
