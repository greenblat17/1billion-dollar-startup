---
name: prepare-to-assist-me
description: >-
  Loads this repo's current architecture, docs, and source map before coding.
  Use at the start of a session, when the user says prepare to assist me,
  read the docs, onboard, or asks the agent to get context on Speaky / the
  speaking-coach Telegram bot.
---

# Prepare to assist me

read the code and documentation in the project and prepare to assist me

Do this **before** implementing. Do not start refactors, deploys, or speculative edits.

## 1. Docs (in order)

Read these with the file tool (do not skim only titles):

1. `ai_docs/summaries/2026-09-18-agent-start-here.md`
2. `ai_docs/architecture.md`
3. `ai_docs/deploy.md`
4. `ai_docs/product.md` — **intent**, not shipped scope
5. `ai_docs/architecture/2026-09-18-agent-guardrails.md`
6. Then, if the user's next task is obvious, the matching integration doc:
   - Telegram / quotes / notes → `ai_docs/integrations/2026-09-18-telegram.md`
   - HTTP sessions/clips → `ai_docs/integrations/2026-09-18-clip-session-api.md`
   - local run / env names → `ai_docs/integrations/2026-09-18-run-and-ci.md`

Skip `ai_docs/researches/2026-09-14-stt-llm-tts-ai-service.md` as current truth.
`ai_docs/plans/mvp-plan.md` is aspirational.

## 2. Code (ground the docs)

Open the live sources, not `bin/`:

- `cmp/server/.../telegram/TelegramHandlers.kt`, `CoachingFeedback.kt`, `StartMessage.kt`
- `cmp/server/.../ai/HttpClipClient.kt`, `ClipDtos.kt`
- `cmp/server/.../Application.kt`, `AppConfig.kt`
- `ai-service/app/pipeline.py`, `llm.py`, `sessions.py`, `dialogue.py`, `main.py`

If the user already named a task, read only the files for that layer plus the docs above.

## 3. Mental model (must be true)

- Shipped: Telegram **webhook** voice coach **Speaky** (Ktor → FastAPI STT/LLM/TTS, Redis dialogue).
- Not shipped: CMP Android/iOS/Desktop as the product UI; pronunciation/fluency scores; exercise loops from `product.md`.
- Notes are `wrong|||better` spliced into a quote as whole words/phrases; the notes LLM chooses span width.
- Two deploys: Redeploy cmp-server → `/opt/speaking-coach`; Redeploy ai-server → `/opt/ai-service` + Redis left running.
- Gradle from `cmp/` with `GRADLE_USER_HOME=$HOME/.gradle`.

## 4. When ready

Reply in a few sentences: what is live, where you will touch code for typical work, one thing you will not do (from guardrails). Then wait for the actual task.
