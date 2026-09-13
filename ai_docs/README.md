# Speaking Coach AI Docs

This directory is the working documentation hub for the Speaking Coach application. It keeps
project knowledge close to the code so decisions, implementation context, and ongoing work can
be found without relying on conversation history.

## Structure

- `architecture/` - system design, component boundaries, data flows, and important technical
  decisions.
- `bugs/` - bug reports, investigation notes, root-cause analyses, and documented fixes or
  workarounds.
- `integrations/` - setup guides, API contracts, configuration notes, and operational details for
  external services and platforms.
- `plans/` - implementation plans for features, refactors, migrations, and other engineering
  tasks before work begins.
- `researches/` - technical and product research, experiments, alternatives considered, and
  findings that inform future decisions.
- `summaries/` - concise context snapshots, discussion summaries, and handoff notes for future
  sessions.

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
