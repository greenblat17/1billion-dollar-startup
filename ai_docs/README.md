# Speaking Coach AI Docs

Working documentation next to the code. A coding agent in a new session should start at:

**`summaries/2026-09-18-agent-start-here.md`**

Глобальная архитектура (диаграммы): [architecture.md](../architecture.md). Деплой на VPS: **`deploy.md`**.

Then dated architecture + integrations `2026-09-18-*` (clips vs CMP: `integrations/2026-09-18-clip-session-api.md`, `integrations/2026-09-18-mobile-api.md`). CMP WebRTC: `architecture/2026-09-20-webrtc-client.md`. Product intent: `product.md`. Do not treat `researches/2026-09-14-stt-llm-tts-ai-service.md` as current implementation.

Vocabulary Builder decisions after the product interview: [`product/2026-10-24-vocabulary-builder.md`](product/2026-10-24-vocabulary-builder.md).
New Vocabulary Builder lesson order and mandatory cards: [`product/2026-10-31-vocabulary-builder-lesson.md`](product/2026-10-31-vocabulary-builder-lesson.md); [implementation plan](plans/2026-10-31-vocabulary-builder-new-lesson.md). This supersedes the earlier lesson order and optional-game decision for new lessons.

Agents: start here, then open the linked docs for the current task. Do not skip this tree
when changing product behavior, UI, navigation, or backend contracts. Put new decisions in
these files, not only in chat.

Monitoring and onboarding analytics integration: `integrations/2026-10-04-combined-monitoring-onboarding.md`.
Main admin metrics dashboard and calculation boundaries: `integrations/2026-10-14-admin-overview.md`.
Habit analytics (WAU, active days, cohort retention, conversation duration): `integrations/2026-10-19-habit-analytics.md`; plan: `plans/2026-10-19-habit-analytics.md`.
Per-call LLM, STT and TTS logs: `integrations/2026-10-14-provider-call-logs.md`.
Final correction-check latency and timeout metrics: `integrations/2026-10-02-monitoring.md`.
Independent final correction search: `plans/2026-10-20-final-correction-discovery-evaluation.md`; synthetic probe: `researches/2026-10-20-independent-correction-discovery-probe.md`; real-conversation evaluations: `researches/2026-10-20-real-final-discovery-evaluation.md` and `researches/2026-10-20-real-call-vocabulary-audio-evaluation.md`.
Vocabulary alternatives beside correction examples: `plans/2026-10-02-telegram-vocabulary-alternatives.md`; initial synthetic prompt probe: `researches/2026-10-20-vocabulary-mixed-assessment-prompt-probe.md`; larger live-turn evaluation: `researches/2026-10-20-live-vocabulary-larger-sample.md`.
Real-conversation replay of the Vocabulary level-up prompt: `researches/2026-10-20-real-vocabulary-level-up-replay.md`.
Full real-conversation replay after explicit Vocabulary categories: `researches/2026-10-20-real-vocabulary-category-full-replay.md`.
Vocabulary workflow rebuild draft: `plans/2026-10-20-vocabulary-rebuild-draft.md`.
Agreed live-naturalness and final Vocabulary-enrichment plan: `plans/2026-10-21-live-naturalness-final-vocabulary-enrichment.md`; local workflow contract: `integrations/2026-10-21-vocabulary-assessment-workflow.md`.
Initial real-dialogue probe of final Vocabulary enrichment: `researches/2026-10-21-final-vocabulary-enrichment-real-probe.md`.
Paired benchmark of the current and dedicated Vocabulary workflows: `researches/2026-10-20-vocabulary-rebuild-benchmark.md`.
Real-dialogue replay of the narrower Vocabulary rules: `researches/2026-10-21-narrow-vocabulary-real-replay.md`.
Real-dialogue replay of the two-suggestion Vocabulary rule: `researches/2026-10-21-two-suggestion-vocabulary-real-replay.md`.
Confirmed model cost per completed Telegram voice exchange: `integrations/2026-10-14-voice-unit-cost.md`.
Plan for complete STT, LLM and delayed TTS cost accounting: `plans/2026-10-15-complete-voice-unit-cost.md`.
Voice error observability: `architecture/2026-10-05-voice-error-observability.md`.
Onboarding journey dashboard: `integrations/2026-09-30-onboarding-analytics.md` (journey/stage-errors section); implementation scope: `plans/2026-10-05-onboarding-dashboard-journey-and-recent-users.md`.
Agreed interaction history and service logs: `architecture/2026-10-05-conversation-audit-logging.md`.
Live monitoring versus the journal, model reply audio, and load-test exclusion: `architecture/2026-10-11-live-monitoring-journal-load-tests.md`.
Cost of one voice day (onboarding or a 5/10/15 minute call): `architecture/2026-10-11-voice-loop-day-cost.md`.
Provider ceilings for the configured Groq and OpenRouter models: `architecture/2026-10-11-provider-limits.md`.
Real DEV voice load test and measured Groq RPM limit: `architecture/2026-10-13-real-voice-load-test.md`.
Real DEV OGG load test with AssemblyAI and the 10 RPS failure: `architecture/2026-10-13-assemblyai-load-test.md`.
DEV load test through the CMP queue and AI client: `architecture/2026-10-13-cmp-load-test.md`.
Env catalog (GitHub blobs and per-host keys, no secret values): `integrations/2026-10-11-env.md`.
DEV clip load and the VPS sizing note: `architecture/2026-10-11-vps-sizing.md`.
Telegram MVP acceptance map: `architecture/2026-10-11-telegram-mvp-acceptance.md`.
Implementation plan for that system: `plans/2026-10-05-interaction-history-and-service-logs.md`.
Telegram practice-call dashboard plan: `plans/2026-10-05-telegram-call-dashboard.md`.
Telegram practice-call dashboard contract: `integrations/2026-10-05-telegram-call-dashboard.md`.
Kora H pilot analytics and metric definitions: `plans/2026-10-23-kora-launch-analytics.md`.
Free-versus-situation analytics plan: `plans/2026-10-09-telegram-mode-analytics.md`.
First conversation feedback: `integrations/2026-10-05-first-call-feedback.md`.
Reminder dashboard analytics: `integrations/2026-10-05-reminder-dashboard-analytics.md`; implementation plan: `plans/2026-10-05-reminder-dashboard-analytics.md`.
Decision-focused reminder dashboard and conversion definitions: `integrations/2026-10-20-reminder-effectiveness-dashboard.md`.
Incomplete onboarding reminders: `plans/2026-10-06-incomplete-onboarding-nudge.md`.
Incomplete onboarding reminder analytics and dashboard: `plans/2026-10-19-onboarding-nudge-analytics.md`.
Personalized incomplete-onboarding reminder time: `plans/2026-10-20-personal-onboarding-nudge-time.md` (implemented locally).
Stage-specific onboarding and practice follow-ups: `plans/2026-10-20-onboarding-stage-followups.md` (implemented locally).
Ongoing practice reminder rules and copy comparison: `plans/2026-10-20-ongoing-practice-reminders.md` (implemented locally).
Reminder-topic call opening and one active reminder button: `plans/2026-10-20-reminder-to-call-continuity.md` (implemented locally).
One-shot soft nudges for interrupted onboarding and calls: `plans/2026-10-20-soft-conversation-nudges.md` (implemented locally).
English follow-up copy by journey stage: `product/2026-10-20-onboarding-followup-copy.md`.
Onboarding assessment completion limit: `plans/2026-10-08-onboarding-assessment-token-limit.md`.
Short-call feedback and practice result order: `plans/2026-10-24-short-call-feedback-and-result-order.md`;
current and historical Telegram review behavior: `integrations/2026-09-30-telegram-calls.md`.

## Structure

- `design/` - UI mockups (PNG) and screen-flow notes for the CMP client.
- `architecture/` - system design, component boundaries, data flows, and important technical
  decisions. Hub diagrams: [`../architecture.md`](../architecture.md).
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
plans/mvp-backend-plan.md
plans/2026-09-19-mobile-mvp-backend.md
plans/2026-09-13-live-feedback-plan.md
researches/2026-09-13-speech-to-text-options.md
summaries/2026-09-13-speaking-session-context.md
```

Keep each document focused on one topic. When a document describes ongoing work, update its
status and link related plans, investigations, decisions, or implementation files.

Telegram optional character Kora H and ordinary Kora retirement: [integration guide](integrations/2026-10-20-telegram-kora.md).
