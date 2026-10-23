# Kora in Telegram

User-approved scope: expose the accepted Voice Lab D conversation as an optional
Telegram character through `/voice`. Default remains Speaky. Free calls and ordinary
voice messages use the selected character; onboarding and roleplay keep their existing
pipeline. No live microphone or LiveKit is involved.

Initial flow: Telegram OGG → existing STT → Sonnet interest plan → Sonnet A2 reply →
Hume EVI `assistant_input` → complete, transcript-checked OGG Opus → Telegram voice.
The planner and writer remain the accepted D baseline in `app/kora_prompts.py`.
Guided Hume delivery now replaces `assistant_input` for conversational turns; see
the 2026-10-21 section below.

Selection persists in Redis `character:{session}`. Kora uses a separate dialogue key
`{session}:kora`, with existing content-retention rules. Switching is blocked while
a delivered call or clip job is active. An undelivered failed opening can be closed
when switching, so provider failure cannot trap the user in Kora. Corrections, streaks, call duration and reviews remain
on the existing user/session. First spoken turn also uses Kora. Failures do not silently
switch character; incomplete Hume audio is rejected. Kora history advances
only after successful speech generation. Telegram delivery failure handling remains
the existing pipeline's responsibility.

Configuration: `HUME_API_KEY`, `HUME_CONFIG_ID`, `HUME_CONFIG_VERSION` (default `0`),
`KORA_MODEL` (default `anthropic/claude-sonnet-4.6`). Missing Hume configuration hides
the Kora choice but allows switching back to Speaky. No credentials belong in Git.
Hume's voice/config is the same Kora config used in the lab. `/speed` applies to both characters. Kora reads the same session preference (including
the configured default) for openings, replies and clarification messages. After EVI
synthesis, FFmpeg `atempo` applies 0.8/0.9/1.0 without changing pitch; 1.0 bypasses
the filter. Previously sent audio is unchanged. This change is locally tested; deployment is pending. Each Telegram reply opens a bounded
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

## Guided Hume delivery (Voice Lab F), 2026-10-21

Kora now sends Sonnet's authored reply to EVI as `TARGET_REPLY` in the session prompt.
EVI generates the spoken wording and audio together. The prompt asks it to preserve
all substantive points, stance, topic and question intent, while allowing natural
phrasing and brief hesitations. `assistant_input` remains for fixed clarification text.
No separate model judges semantic equivalence. This is an experiment, and Hume can
still drift despite the prompt. The bounded session still requires a final transcript,
audio, `assistant_end`, and successful Opus conversion. Hume errors still fail the turn.

The spoken transcript becomes the Telegram `replyText`, rolling Kora history and call
turn reply. The Sonnet target is separately retained as `reply_draft` in the seven-day
attempt artifacts; the Hume transcript is retained as `reply`. A call opening keeps its
Sonnet text as `openingDraft` and the Hume wording as `openingQuestion`; its spoken
wording enters Kora history only after Telegram delivery. `/speed` remains an audio
post-processing control. Existing Speaky and roleplay routes are unchanged.

Local unit tests cover guided EVI transport, actual-spoken-text history, separate
draft storage, opening state and speed. A single real EVI completion tests transport,
not a whole conversation or semantic reliability. Deployment and Telegram acceptance
must be recorded separately.

## Optional Kora H for Telegram, 2026-10-22

`/voice` now offers **Kora H** alongside Speaky and the existing Kora. It mirrors
the current Voice Lab H: Sonnet first chooses a conversational move using the
`interest_alternative_view_v1` rule, then writes a short speech-first A2 reply.
Hume receives that reply through `assistant_input`; the returned transcript must
match the Sonnet text before its OGG is delivered. The same route applies to a
free-call opening and to ordinary voice turns. A fixed clarification remains
exact-text speech. The existing Kora keeps guided native Hume wording.

The choice persists as `kora_h` in the character key. Its dialogue history uses
`{session}:kora_h`, separate from both Speaky and Kora. On an invalid Sonnet
plan, H retries once; if the second plan is also invalid, Sonnet writes directly
for that turn. The seven-day `conversation_plan` artifact marks either the retry
or the direct fallback. Provider failures outside plan validation still fail
the turn. Existing call-switch guards, speed preference, roleplay exclusion and
delivery accounting remain in effect. The API reports `koraHAvailable`, while
older clients can ignore the additional field. This branch is for user testing;
local tests do not establish multi-turn Telegram conversation quality.

## DEV call-opening timeout, 2026-10-22

Two Kora H `Start call` attempts on DEV failed after about ten seconds at the
CMP → AI `/internal/calls/start` request. AI logs show both Sonnet steps
completed and Hume later returned valid opening audio. The CMP OkHttp client
closed the pending response first with `SocketTimeoutException`. The start-call
request now has a 90-second request and socket timeout via Ktor `HttpTimeout`;
other AI requests keep their existing limits. A delayed-response HTTP test covers
the override. This is a transport fix, not evidence of dialogue quality.

## Kora H spoken tone, 2026-10-22

The exact-text Sonnet reply now permits a short reaction to a specific detail
before its planned question or contribution. It favors contractions and allows
an occasional natural thinking word or connector. These must serve the thought,
not become a repeated opening or replace the substantive move. The A2 language,
plan, and exact Hume delivery stay unchanged. Conversation quality still needs
evaluation across complete calls.

## Kora H opening and voice tone, 2026-10-22

The free-call starter previously sent Sonnet only `Let's start a conversation.`
and often received an immediate question. Kora H now asks for a brief, natural
greeting before one easy topic-finding question. This request applies only to
the first spoken turn; ordinary Kora, reminders and roleplay keep their paths.
The H persona also says to welcome the user once, then stop greeting on later
turns.

Exact Hume delivery now asks for subtle variation in energy, pauses and emphasis
that fits Sonnet's text. It still sends `assistant_input` and rejects any spoken
transcript that differs from Sonnet's words. Hume's documented expressive TTS
can inflect supplied text, but this text-only bounded session cannot use the
user's vocal expression. Whether the new instruction improves the heard audio
must be judged on DEV; it does not restore native EVI co-generation.

## Kora H personalization parity, 2026-10-22

Kora H now receives the same verified personalization note as Speaky for both
free-call openings and voice replies. Its planner and writer both see the note:
remembered interests and facts, proficiency evidence, and the policy that recent
user statements override old memory. Fixed A2 instructions are removed from H's
planner and writer; they now match language complexity to verified proficiency,
or adapt cautiously to the current utterance when that is unknown. Ordinary
Kora retains its fixed A2 experiment. Kora H still chooses conversational moves
and sends Sonnet's exact text to Hume. The shared note is passed through the
existing generation path; no new stored profile is created here. Each character
still keeps its own dialogue history, while the learner profile is shared.

## Kora H warmer delivery experiment, 2026-10-22

Kora H now gives EVI a more explicit delivery instruction for Sonnet's exact
words. It asks for audible curiosity, delight, surprise, or sympathy only when
the reply's meaning supports it, with natural variation in pitch, energy, pauses,
and emphasis. It also asks EVI to stay grounded rather than sound theatrical or
constantly excited. This setting applies to Kora H speech, including openings,
replies, and fixed clarifications; ordinary Kora keeps its prior prompts. `assistant_input`
and the exact spoken-transcript check remain in place. Local tests can verify
routing and exact-text enforcement, but the audible effect needs a DEV listening
test across several turns.

## Kora H return-call opening, 2026-10-22

On a new free call, Kora H receives the shared verified personalization note and
up to eight recent Kora H dialogue turns. The stored synthetic call-opening
placeholder is excluded. Its two-stage Sonnet prompt treats the startup request
as an instruction, not a learner disclosure. The planner can choose a specific
unfinished topic from a prior learner turn; otherwise it chooses an easy everyday
topic, optionally informed by a relevant profile fact. It must not invent a
previous event or imply that a profile fact was discussed last time.

The spoken opening combines a varied short greeting and check-in with one
planned topic question. The previous-topic question may say "last time" only
when the prior learner turn supports it. This opening-only two-question shape
does not change subsequent turns. Sonnet still writes the exact words, and Hume
still delivers them with transcript validation. Character histories remain
separate: a Kora H opening cannot quote Speaky-only dialogue unless a fact has
entered the shared verified personalization note. Reminder openings and
roleplay follow their existing paths.

## Kora H reply moves v2, 2026-10-22

Two DEV exchanges exposed an agreement-only dead end: Sonnet planned
`contribute` after the learner described a coming release or the goal of a
human-sounding voice. It wrote a broadly agreeable observation without a
specific next angle; the learner answered "Yeah, yeah, exactly." Hume delivered
Sonnet's exact wording, so the problem was upstream of speech synthesis.

Kora H alone now distinguishes `question` from `perspective` in the plan.
`question` pairs a proportionate reaction with one specific question when the
only available statement would be agreement or paraphrase. `perspective`
requires a different plausible angle plus a reason, example, or practical
implication; it stands without a trailing question. It need not contradict
the learner. A `perspective` plan records one plausible specific learner
response beyond agreement in `response_path`; the parser rejects an empty one.
This is an audit aid, not proof that the spoken reply will invite that response.
Direct answers and repair use `answer`; an explicit goodbye uses
`close`. The plan parser rejects the old H `contribute` move and retries once;
ordinary Kora keeps its original `contribute` schema. The strategy artifact is
`interest_alternative_view_v2` so new calls can be compared with the earlier
behavior. Unit tests verify routing and schema, while whole-conversation
quality still needs a fresh DEV listening test.

The v2 wording originally made the emotional reaction explicit for `question`
but not for `perspective`. Both moves now allow a brief response to a specific
detail when it fits. The response colors the question or different view; it
does not replace either move or require excitement on every turn. Hume's
exact-text speech path is unchanged, so prosody still depends on Sonnet's
wording and Hume's delivery.

## Kora H reply routing v3, 2026-10-22

Kora H now makes the third route explicit: when repeated questions or views on
a topic receive no uptake, the planner selects `fading` with `direction=adjacent` and
names a neighboring topic. The writer bridges to it through a question or a
substantive perspective. A short reply alone does not trigger this transition.
The plan parser rejects a fading plan without an adjacent topic, or an adjacent
plan that does not mark the topic as fading. This replaces the conflicting v2
instruction to stay on the same topic in every question or perspective.

For a `perspective`, the planner supplies a concrete fallback question. Sonnet
writes the exact spoken reply, then a separate Sonnet call judges that finished
reply against the recent exchange. The judge does not see the planner's claim
that the view is useful. A generic slogan, paraphrase, or unsupported prediction
is rejected; the writer then uses the fallback question with a fitting reaction.
If the review call fails, the route also uses that fallback question. The
recorded plan includes the review outcome and final move. This adds one
Sonnet call to accepted perspectives and two to rejected ones. It is a model
judgment rather than a deterministic guarantee of conversational quality.
Question, direct answer, closing, and call opening do not call the judge. Hume
still speaks the final Sonnet text exactly. Existing Kora and Speaky are unchanged.

A read-only Sonnet replay on DEV rejected the reported "good enough and shipped"
reply and one held-out generic launch slogan. It accepted two concrete beta
feedback angles. In a separate replay, three work questions with only brief
answers led to `fading` and a question about after-work activities. These are
directional probes, not a full Telegram conversation or a voice-quality check.

## Kora H single-call reply v4, 2026-10-22

Kora H now makes one Sonnet call per valid turn. One JSON response contains both
diagnostic metadata (`interest`, `direction`, `move`, `evidence`, topic and tone)
and the final `reply`. The reply goes to Hume's existing exact-text speech path.
The metadata remains in `conversation_plan`, without a separate planner, writer,
or online perspective judge. Invalid JSON or inconsistent routing gets one retry;
a second invalid result fails instead of silently falling back to an unplanned
writer. Other Kora and Speaky routes are unchanged.

The single prompt preserves the search/engaged/fading/ending distinction, an
adjacent bridge after repeated failed attempts, concrete perspective versus
question choice, personalized opening with up to eight earlier Kora H turns,
verified proficiency, and spoken style. It asks Sonnet to choose a question if
it has no specific alternative view. This is a prompt-level quality constraint,
not proof that every perspective is substantive. Evaluate perspective quality,
openings, whole-conversation flow and exact speech in offline replays and user
tests. Compare `reply_llm_ms` and total response latency after DEV deployment;
local tests and isolated provider calls do not measure Telegram voice latency.

Two read-only DEV Sonnet replays used identical input histories for v3 and v4.
For a beta-release discussion, v4 produced a concrete alternative view in
4.1 s; v3 chose a question and its planner plus writer took 4.9 s. For three
flat work replies, v3 bridged to an outside-work question in 4.2 s. The first
v4 prompt produced the same bridge but inconsistent `uncertain/adjacent`
metadata, which would need a retry. After making the routing constraint
explicit, a repeat returned `fading/adjacent` and an after-work question in
2.9 s. These are small, non-deterministic samples; neither response quality
nor the latency gain is established for a whole Telegram call. Hume latency
was outside this replay.

## Kora speech with Gemini 3.8 Flash TTS, 2026-10-22

The initial Gemini adapter kept Kora's Sonnet planner and writer but spoke the
written text with `google/gemini-3.8-flash-tts` through the existing OpenRouter
key. Voice was `Kore`; `KORA_TTS_MODEL` and `KORA_TTS_VOICE` can change
the model and voice. OpenRouter accepts only `response_format=pcm` for this model. The adapter
converts its 24 kHz, mono, signed 16-bit PCM to Telegram OGG Opus and applies
the existing 0.8/0.9/1.0 `/speed` setting in FFmpeg. It uses the Sonnet text as
the displayed reply and dialogue history; Gemini returns audio without a
transcript, so exact spoken wording is not independently verified.

**Kora H** remains Sonnet plus Hume EVI and requires Hume configuration.
Kora no longer requires Hume; it is available when the configured chat endpoint
is OpenRouter. Ordinary Speaky, lessons and situations retain their speech
routes. A Kora audio failure fails the turn and does not substitute Hume or
advance Kora history. Existing `TTS_MODEL` controls Speaky, not Kora.

A read-only DEV OpenRouter call returned valid PCM for the new model; `wav`
and `mp3` requests returned HTTP 400. Local conversion produced valid OGG
for all three speed settings. An isolated DEV-container probe also ran the new
adapter end to end, producing a valid OGG with speed 0.9. These checks
establish provider transport and encoding, not voice quality or a complete
Telegram call. DEV deployment and Telegram listening tests remain separate.

## Kora speech model switch to Gemini 3.1, 2026-10-22

Kora's default TTS model is now `google/gemini-3.1-flash-tts-preview`.
The `Kore` voice, OpenRouter endpoint, raw PCM conversion, OGG output and
`/speed` handling are unchanged. Kora H still uses Hume. A read-only DEV
OpenRouter call with the 3.1 model returned headerless, even-length PCM with
`response_format=pcm`. An isolated DEV-container probe of the adapter produced
valid OGG at speed 0.9. A Telegram listening test remains after deployment.

## Kora H speech switch to Gemini 3.1, 2026-10-23

Kora H keeps its Sonnet conversation model, personalized opening and separate
dialogue history. Its opening, replies and fixed clarification now use the same
`GeminiKoraSpeech` instance as Kora, with the default
`google/gemini-3.1-flash-tts-preview` model and `Kore` voice. Both variants use
`KORA_TTS_MODEL`, `KORA_TTS_VOICE` and the existing `/speed` setting. Kora H is
available with the configured OpenRouter endpoint; Hume credentials are no longer
required for either Telegram character. The displayed and stored reply remains
Sonnet's text. Gemini does not return a spoken transcript, so exact wording
cannot be independently checked as it was with Hume. `HumeSpeech` remains in the
codebase for other experiments, but the Telegram Kora H route no longer uses it.
Local routing tests do not establish voice quality; listen to an actual Telegram
call after deployment.

## Kora and Kora H per-turn emotional delivery, 2026-10-23

Both characters use the `tone` field from their existing Sonnet output to
choose speech delivery per reply. Kora gets it from its planner; Kora H gets
it from its single JSON turn. The allowed values are `plain`, `warm`,
`curious`, `surprised`, `excited`, `gentle`, `thoughtful`, and `playful`. Sonnet
chooses from the learner's meaning and intended response; it must not add
stage directions to the spoken reply. An unknown tone falls back to `plain` without
retrying the whole conversational turn. This adds no LLM call.

Only the OpenRouter Gemini 3.1 TTS input receives a mapped inline audio tag,
such as `[curious]` or `[amazed]`. Subtitles, audit reply, and dialogue history
keep Sonnet's clean text. Plain delivery has no tag. The current experiment
applies to Kora and Kora H openings and free-conversation replies, not Speaky,
lessons or situations. Kora H opening speech always uses `warm`, including
retries of a cached opening draft; later turns still use Sonnet's per-turn tone.
Kora cached opening drafts and fixed clarification lines keep plain delivery.
Google documents inline tags for Gemini 3.1:
https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-tts-preview

Local tests verify tone routing and the exact OpenRouter request text. They do
not prove Gemini changes intonation, omits the tag from speech, or preserves
every spoken word. Listen to matched DEV turns before judging this experiment;
Gemini does not return a transcript to verify exact spoken wording.

## Leda voice experiment, 2026-10-23

Kora and Kora H now default to Gemini's `Leda` voice instead of `Kore`.
The shared `KORA_TTS_VOICE` setting can override it. Sonnet's dialogue and
per-turn delivery tags are unchanged. This is a voice preference experiment;
Telegram listening is needed to assess the result.

## Kora H structured-turn reliability, 2026-10-23

A DEV voice turn failed before TTS: Sonnet returned non-JSON text twice while
the request used OpenRouter `json_object`, so Kora H could not parse its
conversation move and reply. The same saved user utterance and 40-message
history reproduced that non-JSON response locally. With a strict JSON schema
for all eight fields, the same input parsed on the first call. A further
15-case probe, including five runs of the incident context, produced 15 valid
first-call turns. This is directional evidence, not a guaranteed 90% rate.

Kora H now requests `json_schema` with `strict=true` and still validates
cross-field rules in `parse_h_turn`. If the first response is invalid, it makes
one more structured request at lower temperature with the validation error.
There is no unplanned text-writer fallback or third call. An invalid second
response still fails the turn and records the parse error.
