# DEV synthetic voice Trial · 2026-11-17

## Scope and revisions

- DEV runtime: Ktor `7e416da`; AI service `e308902` after the DEV-only Docker peer fix. Mini App was not deployed in these runs.
- Deployment `44104039332` on `7e416da` passed. AI-only deployment `44106192748` on `e308902` passed. Readiness runs `44104039332` and `44106762511` passed. These jobs did not explicitly query the applied V65 migration.
- Text booking run `44105197442` used workflow SHA `32274a6`. The separate `agent_test` identity is `f5b9bbba-0ac4-4740-aa0d-8cba196f9de6`; Foundation student is `a6249c70-9296-4045-bb5f-7104c6e63d65`. Trial lesson is `9e758555-b84a-45e2-b62a-a74096b37706`. Its snapshot shows `launch_mode=executable`, `timing=now`, 30 minutes, and no Telegram recipient.
- Initial voice run `44105498523` on workflow SHA `32274a6` stopped at `not_permitted` before Foundation admission or model audio. The exact denied guard term was not recorded. The AI container used Docker bridge networking; the later fix accepts only its exact default gateway peer, with DEV flags, service token, and one-time grant still required.
- Successful transport run `44106947563` used workflow SHA `e308902`. GitHub private artifact: `agent-voice-44106947563` (five WAVs, `dialogue.json`, `dialogue.md`). No secrets appear in the artifact.

## Observed result

| Check | Result | Evidence and limit |
| --- | --- | --- |
| Admission and relay | Pass | Two `connected` events for the owned lesson; no relay error on the second run. |
| Greeting | Pass for event; fail for Trial continuity | An audible English generic “welcome back” greeting occurred at both connections. The second greeting asks what to work on, although the Trial and topic were already active. |
| User ASR persistence | Pass for two turns | Foundation events contain both synthetic user transcripts, source IDs `3fad606f-3e15-400d-9b1e-e083d522827d` and `74f00c01-b5a6-4acb-8ffd-1e312f9dd149`. The second ASR changed “meetings” to “meeting”. |
| Tutor transcript persistence | Partial | Four assistant events are in the final Foundation snapshot. Speech transcribed independently from the last WAV is absent from that snapshot. A later read was not made. |
| Playback acknowledgement | Partial, client defect | Four of five assistant utterance IDs have `playbackComplete=true` in PostgreSQL metadata. The fifth has no completion. The original client marked its WAV as `simulated_timing` before sending `played`, then ended after the earlier item. |
| PCM/WAV | Pass for returned audio; item mapping limited | Five nonempty mono signed 16-bit 24 kHz WAVs: 7.15, 28.70, 6.75, 6.00, and 25.85 seconds. Independent BB voice transcription recovered intelligible speech from all five. The last two provider audio items overlap, so the per-item WAV assignment is ambiguous. This is not human listening or a microphone test. |
| PostgreSQL voice state | Pass for observed writes | Lesson remained `in_progress`, phase `warmup`, transcript revision 6. Two call session IDs: `6faa5e9f-f2a2-456a-9195-737ec74d3beb` and `948ad5ee-092f-4533-8e67-8332b302f39b`. |
| S3 archive metadata | Pass for metadata; direct object read blocked | Foundation returned 261 `stored` audio rows: 74 user, 187 assistant, across both sessions. All have the owned student and lesson IDs; metadata uses PCM 24 kHz and OpenAI Realtime transport. A direct GET/HEAD of each S3 object was not performed. |
| Reconnect | Pass for transport; fail for dialogue continuity | The client disconnected after turn one and reconnected with a new call session. The relay resumed the same lesson, but Sasha restarted with a generic greeting. |
| Full Trial and learning AC | Blocked | Only two synthetic turns were sent. The 30-minute RU→EN→RU path, live microphone, interruptions, final assessment, and Telegram follow-up were not tested. |

The first returned audio began 1,034 ms after `user_turn_complete`; the second began after 927 ms. These are client timestamps for synthetic audio, not user-perceived latency.

In the second call session, `audio_start` for `item_ElEn7kRhFB78V1qp2TGbV` arrived about 5.5 seconds before `audio_end` for `item_ElEn5eYYoA9hjfunqlBcp`. The relay sends binary PCM frames without an item ID. The client assigns frames to the most recently started item. Therefore the content of `attempt-2-audio-4.wav` and `attempt-2-audio-5.wav` cannot be assigned to those provider item IDs with confidence. The WAVs are still useful for content review. This is a separate relay/protocol ambiguity from the missing playback acknowledgement.

## Speech content and source boundary

The first response after the learner asked to practise work speech began in Russian: Sasha called it a friendly warm-up and asked about the learner's work. After reconnect, the learner said they felt nervous in meetings. Sasha acknowledged the anxiety, then a second WAV expanded on possible sources of that anxiety. The Foundation snapshot contains only the first short sentence of this second answer.

The fifth WAV was transcribed independently with `bb voice transcribe`: “Это нормально, правда. Многие нервничают, когда нужно говорить при коллегах. У нас формат простой. Это не экзамен. Мы просто поговорим так, как бывает в обычной встрече. Я могу помочь словами, перефразировать или дать паузу, если мысль застряла. Скажи по-русски, что именно тебя больше всего напрягает: страх ошибиться, что не поймут или, скорее, сложно начать говорить?” This text is **not** a saved Foundation transcript. Independent transcription confirms intelligible audio, but does not establish subjective voice quality or that a learner heard it.

The repo-local `infra/dev/agent-voice.py` fix now waits for every `audio_end`/`played`, a three-second quiet interval, and two stable PostgreSQL reads before ending a turn. Its report distinguishes pending from sent playback acknowledgements and marks overlapping item attribution as ambiguous. The updated local stub test covers two delayed, overlapping tutor audio items; seven focused tests passed. This patch is local only: no commit, push, or further DEV run was made.
