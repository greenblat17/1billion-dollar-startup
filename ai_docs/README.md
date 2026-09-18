# Speaking Coach AI Docs

Working documentation next to the code. A coding agent in a new session should start at:

**`summaries/2026-09-18-agent-start-here.md`**

Глобальная архитектура (диаграммы): **`architecture.md`**. Деплой на VPS: **`deploy.md`**.

Then dated architecture + integrations `2026-09-18-*`. Product intent: `product.md`. Do not treat `researches/2026-09-14-stt-llm-tts-ai-service.md` as current implementation.

## Structure

- `architecture/` - system design, component boundaries, data flows, technical decisions.
- `bugs/` - bug reports and root-cause notes.
- `integrations/` - API contracts, env, CI, Telegram behaviour.
- `plans/` - implementation plans (may lag the code).
- `product/` - extra product notes; hub file is `product.md` at this folder root.
- `researches/` - historical research; re-check against `src/` before using.
- `summaries/` - handoff notes for future sessions.

## File Naming

Use date-prefixed, task-specific filenames:

```text
architecture/2026-09-13-audio-processing-pipeline.md
bugs/2026-09-13-telegram-audio-upload-failure.md
integrations/2026-09-13-ai-service-api.md
plans/2026-09-13-live-feedback-plan.md
researches/2026-09-13-speech-to-text-options.md
summaries/2026-09-13-speaking-session-context.md
```

Keep each document focused on one topic. When a document describes ongoing work, update its
status and link related plans, investigations, decisions, or implementation files.
