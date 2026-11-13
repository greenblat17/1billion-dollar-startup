---
name: prepare-to-assist-me
description: >-
  Loads Speaky's product direction, implementation status, architecture and source map.
  Use when starting work on this repository or when asked to read its docs or get context.
---

# Prepare to assist me

Get the context needed for the user's task before editing. Context loading alone does not authorize refactors or deployment.

## Read the relevant sources

Paths below are relative to the repository root.

1. `ai_docs/README.md` — documentation map.
2. `ai_docs/product/2026-11-08-ai-tutor-experience-v0.5.md` — product intent, not shipped scope.
3. `ai_docs/status.md` — shipped behavior, work in progress and verification limits.
4. `architecture.md` — single reference for implemented components, flows, data ownership and decision rationale.
5. `AGENTS.md` — operational boundaries.

Follow the status page to the current plan when the task concerns ongoing work.
Read the matching technical reference rather than every integration document:

- Text tutor, Telegram routing, tools and trial: `ai_docs/integrations/tutor-chat.md`.
- Legacy calls, clips, memory, corrections and reviews: `ai_docs/integrations/voice-and-reviews.md`.
- Legacy Vocabulary Builder and reminders: `ai_docs/integrations/practice.md`.
- Legacy events, cohorts and metrics: `ai_docs/integrations/analytics.md`.
- Local commands, env, CI, deploy and monitoring: `ai_docs/integrations/operations.md`.
- Legacy CMP client API: `ai_docs/integrations/mobile-api.md`.

## Verify against the working tree

Check git status first and preserve other work. Read source under `src/` and `ai-service/app/`, not generated `bin/` or `build/`.

- Ktor entrypoint: `cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/Application.kt` calls `SpeakyApplication.kt` in that package.
- Python shipping composition: `ai-service/app/speaky_main.py`; `app/main.py` is preserved legacy.
- Active tutor: `ai-service/app/foundation_tutor.py`, `trial_tools.py`, `memory_tools.py`, `subscription_tools.py`; `tutor.py` supplies relationship policy. `tutor_store.py` is legacy/import only.
- Active voice: `ai-service/app/trial_api.py`, `trial_realtime.py`, `trial_audio.py`; frontend in `mini-app/src/`. `pipeline.py` and `dialogue.py` belong to legacy clips/calls.
- Learning and memory: `learning_engine.py`, `regular_lesson.py`, `roadmap.py`, `context_builder.py`, `memory_context.py`; authoritative operations live in Ktor foundation.
- Foundation adapters and storage ownership: follow `architecture.md` and verify actual composition-root wiring; use `ai_docs/status.md` and the plan acceptance matrix for test evidence.
- CMP client: `cmp/app/shared/`; its prototype is distinct from the standalone lesson/progress Mini App in `mini-app/`.

Speaky's product direction is a continuing relationship with Sasha, with Kora H's character.
Do not infer deployment, shared memory or migration completion from a plan, a class name or a passing unit test.
Distinguish target behavior, implemented code, deployed revision and live acceptance.
Gradle runs from `cmp/` with `GRADLE_USER_HOME=$HOME/.gradle`; Python commands run from `ai-service/`.

## Continue the task

For an orientation-only request, summarize the relevant components and unresolved facts.
If the user already assigned work, proceed within that scope after loading context; do not stop merely because onboarding is complete.
