# Speaking Coach AI Docs

Working documentation next to the code. A coding agent in a new session should start at:

**`summaries/2026-09-18-agent-start-here.md`**

Глобальная архитектура (диаграммы): **`architecture.md`**. Деплой на VPS: **`deploy.md`**.

Then dated architecture + integrations `2026-09-18-*`. Product intent: `product.md`. Do not treat `researches/2026-09-14-stt-llm-tts-ai-service.md` as current implementation.

Agents: start here, then open the linked docs for the current task. Do not skip this tree
when changing product behavior, UI, navigation, or backend contracts. Put new decisions in
these files, not only in chat.

## Structure

- `design/` - UI mockups (PNG) and screen-flow notes for the CMP client.
- `architecture/` - system design, component boundaries, data flows, and important technical
  decisions. Hub diagrams: `architecture.md`.
- `bugs/` - bug reports, investigation notes, root-cause analyses, and documented fixes or
  workarounds.
- `integrations/` - setup guides, API contracts, configuration notes, and operational details for
  external services and platforms.
- `plans/` - implementation plans for features, refactors, migrations, and other engineering
  tasks before work begins.
- `product/` - extra product notes; hub file is `product.md` at this folder root.
- `researches/` - technical and product research, experiments, alternatives considered, and
  findings that inform future decisions. Re-check against `src/` before using.
- `summaries/` - concise context snapshots, discussion summaries, and handoff notes for future
  sessions.
- `deploy.md` - VPS / GitHub Actions deploy (not under a dated folder).

## File Naming

Use date-prefixed, task-specific filenames:

```text
architecture/2026-09-13-audio-processing-pipeline.md
design/2026-09-17-mobile-ui.md
bugs/2026-09-13-telegram-audio-upload-failure.md
integrations/2026-09-13-ai-service-api.md
plans/2026-09-19-mobile-mvp-backend.md
plans/2026-09-13-live-feedback-plan.md
researches/2026-09-13-speech-to-text-options.md
summaries/2026-09-13-speaking-session-context.md
```

Keep each document focused on one topic. When a document describes ongoing work, update its
status and link related plans, investigations, decisions, or implementation files.
