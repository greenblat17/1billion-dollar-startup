# Direct Deepgram TTS for Telegram

Status: implemented locally; 196 AI tests and `:server:test` pass. DEV rollout and live Telegram latency check pending.

## Goal and evidence

Reduce the time from a received voice message to Speaky's playable reply while keeping the current Aura-2 Thalia voice and existing correction behavior. A DEV benchmark from the AI host used the same text and voice for three runs per route:

| Text length | OpenRouter MP3 + OGG conversion, median | Direct Deepgram MP3 + OGG conversion, median | Direct Deepgram OGG/Opus, median |
| --- | ---: | ---: | ---: |
| 44 characters | 1.87 s | 1.17 s | 1.55 s |
| 160 characters | 4.50 s | 2.78 s | 3.92 s |

Direct Deepgram MP3 was the fastest measured route. Skipping the MP3-to-OGG conversion can save another roughly 0.2–0.4 s. These are synthetic provider timings, not end-to-end Telegram timings or a guarantee of a 3 s reply.

## Implementation

1. **Direct provider behind configuration.** Add a Deepgram TTS adapter using `POST /v1/speak?model=aura-2-thalia-en&encoding=mp3`, `Authorization: Token ...`, JSON `{"text": ...}`, and an MP3 response. Reuse the existing async HTTP dependency, set a bounded request timeout, and keep the OpenRouter adapter as the default until rollout. Add `TTS_PROVIDER=deepgram|openrouter` and `DEEPGRAM_API_KEY`; fail configuration clearly if Deepgram is selected without its key. Do not log text, audio, or credentials. Keep the same TTS interface so normal dialogue, clarification, onboarding, and greeting use the selected provider. Initially convert direct MP3 to OGG as today: this step changes no HTTP audio contract.

2. **Pass through MP3 end to end.** Make TTS output carry both audio bytes and media type. Propagate the media type through `PipelineResult`, job storage, `/v1/clips/{jobId}/audio`, and the cached greeting response. The direct provider returns `audio/mpeg` and raw MP3; the existing OpenRouter provider keeps `audio/ogg` and conversion. In Kotlin `HttpClipClient`, choose `.mp3` for `audio/mpeg` and `.ogg` for `audio/ogg` for both greeting and reply; reject unsupported types. Telegram `sendVoice` receives the matching filename. Preserve the `audioAvailable=false` path and incoming user OGG voice unchanged.

3. **Verification.** Unit test the Deepgram request, auth header, response bytes, timeout/error behavior, and provider selection without real credentials. Test both media types across ordinary reply, clarification, greeting, and onboarding audio. Test Kotlin filename and content-type handling for both formats. Run `pytest` in `ai-service/` and `./gradlew :server:test` from `cmp/`. On DEV, send short and normal real Telegram turns; verify that voice plays and that correction cards and subtitles still appear. Compare logged TTS stage and end-to-end Telegram latency at p50/p95 with the same prompts before and after each step; include audio-download and sendVoice time. Do not infer a 3 s end-to-end result from provider timing alone.

4. **Rollout and rollback.** For step 1, configure the Deepgram key in the AI server environment and deploy AI only; switch `TTS_PROVIDER` there. For step 2, deploy Kotlin support for both audio types first, then deploy AI MP3 output. Existing OGG continues to work during the transition. Roll back by switching the provider to OpenRouter and redeploying AI; Kotlin continues to accept OGG. Update `ai_docs/integrations/2026-09-18-clip-session-api.md`, `ai_docs/integrations/2026-09-18-run-and-ci.md`, and `ai_docs/deploy.md` with the implemented format/configuration and deployment order. Never put the API key value in the repository or documentation.

## Acceptance

- Direct Deepgram selected on DEV produces playable Telegram voice for `/start`, normal turns, clarification, and onboarding.
- MP3 responses use `audio/mpeg` and `.mp3`; OpenRouter fallback configuration still uses `audio/ogg` and `.ogg`.
- TTS and end-to-end timing are measured separately. If normal real turns still miss the 3 s goal, use the stage timings to choose the next optimization rather than assuming TTS is the remaining bottleneck.

## Provider contracts

- [Deepgram TTS request and MP3 response](https://developers.deepgram.com/docs/text-to-speech)
- [Telegram `sendVoice` accepts MP3 as a playable voice message](https://core.telegram.org/bots/api#sendvoice)
