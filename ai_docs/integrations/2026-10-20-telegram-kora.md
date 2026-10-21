# Kora in Telegram

User-approved scope: expose the accepted Voice Lab D conversation as an optional
Telegram character through `/voice`. Default remains Speaky. Free calls and ordinary
voice messages use the selected character; onboarding and roleplay keep their existing
pipeline. No live microphone or LiveKit is involved.

Flow: Telegram OGG → existing STT → Sonnet interest plan → Sonnet A2 reply → Hume EVI
`assistant_input` → complete, transcript-checked OGG Opus → Telegram voice message.
The planner and writer are the accepted D baseline, frozen in `app/kora_prompts.py`.
Kora is the only persona name change. No spoken rewrite or Hume paraphrase experiment
is included. Hume receives conversation context but does not choose reply content.

Selection persists in Redis `character:{session}`. Kora uses a separate dialogue key
`{session}:kora`, with existing content-retention rules. Switching is blocked while
a delivered call or clip job is active. An undelivered failed opening can be closed
when switching, so provider failure cannot trap the user in Kora. Corrections, streaks, call duration and reviews remain
on the existing user/session. First spoken turn also uses Kora. Failures do not silently
switch character; incomplete/mismatched Hume audio is rejected. Kora history advances
only after successful speech generation. Telegram delivery failure handling remains
the existing pipeline's responsibility.

Configuration: `HUME_API_KEY`, `HUME_CONFIG_ID`, `HUME_CONFIG_VERSION` (default `0`),
`KORA_MODEL` (default `anthropic/claude-sonnet-4.6`). Missing Hume configuration hides
the Kora choice but allows switching back to Speaky. No credentials belong in Git.
Hume's voice/config is the same Kora config used in the lab. `/speed` currently applies
to Speaky only; Kora retains its natural delivery. Each Telegram reply opens a bounded
EVI session with textual history; acoustic continuity of a browser session is not retained.

Provider calls use existing operational logging. Sonnet usage is metered; Hume cost
is recorded as unknown, never zero. The plan is an audit artifact with the same seven-day
retention as transcript/reply. No new personal data goes to application logs.

Lifecycle limitation discovered during integration: [Hume's official announcement](https://dev.hume.ai/intro)
says EVI/TTS access ends November 13, 2026 at 12:01 a.m. EST. This is a short-term DEV
integration, not a permanent dependency. `HumeSpeech` is isolated for replacement.

Acceptance: selector persistence; default/onboarding/roleplay regressions; opening and
reply route to Kora; real Sonnet and EVI completion with exact transcript and valid OGG;
DEV command registration and HTTP health. Whole-conversation quality in Telegram still
requires the user's evaluation after deployment.

## DEV delivery, 2026-10-20

The working branch predates the DEV analytics/reminder updates. Release was assembled
on `origin/main` `f6c5fb5` in `/tmp/speaky-kora-release`, applying only the Kora integration
and the `CallStore.open_call_today` accessor. Existing DEV AI source hashes matched that
main revision before deployment. Ordinary Speaky behavior on DEV was preserved; earlier
uncommitted persona experiments were not deployed as part of Kora.

Checks: working branch 396 Python tests and 212 Kotlin tests; release base 390 Python
and 250 Kotlin tests. Additional failed-opening recovery tests passed after review.
Real isolated Sonnet → EVI → valid Opus: 18.3 seconds. Full DEV-container opening plus
STT → planner → writer → EVI reply succeeded; reply pipeline took 16.8 seconds. The
smoke used synthetic conversation and in-memory state, without Telegram messages or
user analytics writes. These are transport/routing checks, not conversational acceptance.

Rollback images: `ai-service:before-kora-20261020` and
`speaking-coach:before-kora-20261020`. Source/env backup:
`/opt/ai-service/backup-before-kora-20261020`; old JAR:
`/opt/speaking-coach/server-before-kora-20261020.jar`.
Exact release patch/manifest are retained in this BB thread's storage under
`telegram-kora/release.patch` and `telegram-kora/release.json`.

Hume settings were copied privately from the existing DEV lab into the AI host `.env`.
GitHub secret blobs were not changed. Before a later CI redeploy, integrate the code
and add the documented Hume keys to `DEV_AI_SERVER_ENV`; otherwise that redeploy will
replace this manual DEV release/configuration. No PROD rollout or Git push was done.

## W0108 fix, 2026-10-21

The 2026-10-20 21:20 MSK Telegram failure happened after STT and both Sonnet calls
succeeded. EVI returned W0108 for a 356-character assistant input. The adapter treated
this warning as fatal. Replaying the saved input while allowing W0108 produced complete
audio with exactly the original text. Treat W0107 and W0108 as nonfatal length warnings;
retain strict transcript/completion validation and fail on other error codes.
Regression tests cover both warnings, unknown errors, incomplete audio and altered text.

For GitHub Actions, select branch `feature/telegram-kora` in **Redeploy DEV** and enable
both server targets. Include `HUME_API_KEY`, `HUME_CONFIG_ID`, and `HUME_CONFIG_VERSION`
in the `DEV_AI_SERVER_ENV` secret before dispatch. The key is already on the DEV AI host
in `/opt/ai-service/.env`; do not copy its value into source control or chat. Deployment
from Actions overwrites that host env file. `HUME_CONFIG_VERSION=0` and
`KORA_MODEL=anthropic/claude-sonnet-4.6` are defaults.
