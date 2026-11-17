# DEV acceptance · Trial voice → Roadmap · 17 November 2026

## Evidence boundary

This report covers SP-06, SP-17B, SP-07, SP-08, SP-11A, parent SP-11 stage A, and Trial-dependent parent SP-17. `pass` means the complete AC was observed on its required DEV interface. `fail` means the observed behavior contradicts the AC. `blocked` names the missing observation; a green code check is not a live pass.

- **T** — [new isolated text booking run 44126287817](https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44126287817): agent identity `9e5d6bcd-6bdf-441d-a1b6-890505a2a227`, Foundation student `ec7c104b-3a19-4803-a0b6-9cb3118bf20b`, executable Trial `605e6ea7-2519-4773-959c-509a84885bb9`. It saved the learner's IT work, meeting anxiety, and B1 self-description as text context. At booking, system CEFR was Unknown and Observations/Roadmap were empty. The B1 phrase was in the conversation summary, not in structured self-assessment.
- **V** — [synthetic voice run 44126530255](https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44126530255), same student and lesson, runner SHA `ecbe949c83d26a5e00c8ea1a27665d3cc14dc15f`. DEV Ktor `7e416da659bfdaaa98318cf83f2c3b3d5299cd91`, AI `e308902d1cdf903aee00bebcd1a4825f4820f478`, Mini App `2f38856` per [status](../../status.md); this run used the relay, not the visual Mini App. [Readable saved dialogue](trial-voice-ac-dialogue-2026-11-17.md) and [sanitized raw JSON](trial-voice-ac-2026-11-17.json) preserve source IDs, relay events, timer, WAV names and playback receipts. The private Actions artifact `agent-voice-44126530255` holds five full WAVs for seven days.
- **V result** — Two short synthetic turns, two call sessions, 5 WAVs, 5 playback acknowledgements, and both turn reads confirmed. The final lesson remained `in_progress`, phase `warmup`, transcript revision 7. Observations count 0, learning revision 0, no Roadmap or trial result. The second ASR saved “meeting” for synthesized “meetings.” No terminal event or post-Trial job was observed. The fifth audio was saved in Foundation in this run, resolving the previous test client's missing-transcript issue.
- **V failures** — On both admissions Sasha started with a generic English “welcome back” and asked what to work on. After reconnect, she again asked for a topic despite the active Trial and saved IT/meeting context. In call session 2, `audio_start` for `item_ElGOherI3ntrtcW2yi0It` preceded `audio_end` for `item_ElGOfRhe4aKJKRc7o1xyU`; binary PCM frames carry no item ID. Both WAV-to-item assignments are `ambiguous_overlap`. Transport completion does not repair these product/protocol failures.

The voice input used `espeak-ng`; playback time was simulated. No human microphone, Telegram delivery, 30-minute conversation, or direct S3 object read was verified. The first run [44106947563](agent-voice-dev-2026-11-17.md) found the same reconnect and overlap failures; V confirms they reproduce with the fixed client.

## SP-06 — Trial Lesson

| AC | Expected | Actual DEV result | Evidence | Verdict and exact reason |
| --- | --- | --- | --- | --- |
| [AC-01][sp06] | Existing call, about 30 connected minutes, reconnect preserves time. | Two sessions reached about 100 connected seconds; same lesson survived reconnect. | T, V, `605e6ea7…` | **blocked** — 30 connected minutes and terminal Trial were not exercised. |
| [AC-02][sp06] | Russian warm-up → natural English conversation → Russian wrap-up. | Warm-up stayed Russian after English synthetic inputs; phase remained `warmup`. | V, transcript revision 7 | **blocked** — no 5/25-minute phase boundaries or wrap-up were reached. |
| [AC-03][sp06] | Use known Telegram context without repeating intake. | Generic English greetings asked what to work on at admission and reconnect. Known IT/meeting anxiety was in T; later responses used IT context. | T, V, greeting source IDs `f9cecc01…`, `58b3f991…` | **fail** — initial/reconnect greeting restarted topic selection and ignored active Trial context. |
| [AC-04][sp06] | Calm, adaptive conversation without oral exam. | After learner anxiety Sasha offered gentle support; only two turns occurred. | V, saved dialogue | **blocked** — sustained English conversation and adaptation to a simplification request were not tested. |
| [AC-05][sp06] | No spoken CEFR, scores, percentages, or target dates. | None appeared in five saved assistant utterances. | V, saved dialogue | **blocked** — the full Trial, including explicit level question and wrap-up, was not heard. |
| [AC-06][sp06] | Handle questions, confusion, topic change, and early exit naturally. | Only work and anxiety statements were sent. | V | **blocked** — no requested behavior was exercised. |
| [AC-07][sp06] | Russian wrap-up promises a later personal Telegram Roadmap without claiming delivery. | No wrap-up or terminal state. | V, `trial_phase=warmup` | **blocked** — wrap-up not reached. |
| [AC-08][sp06] | Raw Trial sources available to SP-17/07 without diagnosis by SP-06. | Seven saved conversation events and 258 stored audio metadata rows; no raw-event API read, direct object read, or downstream job. | V, lesson revision 7 | **blocked** — raw event provenance and consumer handoff still need API/job evidence. |
| [AC-09][sp06] | Do not sell regular schedule before Roadmap. | No schedule or sale in five utterances. | V | **blocked** — end-of-Trial transition was not observed. |
| [moved SP05-AC02][sp06] | Discuss only unknown frequency, duration, Homework during Trial. | Learning profile retained null/unknown; wrap-up not reached. | T, V | **blocked** — no load discussion occurred. |
| [moved SP05-AC04][sp06] | Clarify “2–3” and vague Homework; save only exact numbers/null. | Neither vague input nor correction was spoken. | V | **blocked** — no voice preference write or revision. |
| [moved SP05-AC05][sp06] | Save Homework No, preserve unknowns, continue learning. | Consent remained `unknown`. | V, learning revision 0 | **blocked** — explicit refusal was not tested. |

## SP-17B — Trial Observation Integration

| AC | Expected | Actual DEV result | Evidence | Verdict and exact reason |
| --- | --- | --- | --- | --- |
| [AC-1][sp17b] | Existing raw Trial events feed the common Observation format. | Conversation events persisted; Trial was not completed and raw-event API was not read. | V | **blocked** — no raw → Observation receipt. |
| [AC-2][sp17b] | Speaking/Grammar/Vocabulary signals reach SP-07; no Listening Observation. | Observation list empty while Trial remained active. | V | **blocked** — post-Trial producer did not run; absence of Listening alone is insufficient. |
| [AC-3][sp17b] | Silence creates no error; explicit source uncertainty is unusable. | No silence/uncertainty scenario or Observation was produced. | V | **blocked** — neither branch was exercised. |
| [AC-4][sp17b] | ASR repeats do not duplicate Observations. | Two distinct user source IDs; no duplicate ASR callback or Observation. Audio item overlap is a separate protocol failure. | V | **blocked** — repeat/idempotency not exercised on DEV. |
| [AC-5][sp17b] | SP-07 consumes Trial Observations without Teaching Engine. | No terminal Trial, Observation job, or assessment. | V | **blocked** — consumer handoff has no live receipt. |

## SP-07 — Post-Trial Assessment

| AC | Expected | Actual DEV result | Evidence | Verdict and exact reason |
| --- | --- | --- | --- | --- |
| [AC-01][sp07] | Diagnose Speaking/Grammar/Vocabulary from evidence; Listening Unknown. | No assessment; all system levels Unknown. | V | **blocked** — Trial not completed and no Observation producer. |
| [AC-02][sp07] | Overall/skill CEFR each single value or Unknown. | Initial Unknown persisted. | T, V | **blocked** — no post-Trial level write to inspect. |
| [AC-03][sp07] | Keep self-report separate from system diagnosis. | B1 text remained summary; structured self-report stayed Unknown. | T, V | **blocked** — no diagnosis applied, so non-overwrite was not challenged. |
| [AC-04][sp07] | Distinguish Known, Not Known, Presumed Not Known, Unknown per Topic. | No Topic state generated. | V | **blocked** — no Topic assessment. |
| [AC-05][sp07] | Unobserved Topics do not become Not Known automatically. | No Topic state generated. | V | **blocked** — negative transition cannot be checked. |
| [AC-06][sp07] | Provenance and uncertain ASR do not make confident conclusions. | Source IDs saved; ASR changed “meetings” to “meeting”; no assessment to test its use. | V | **blocked** — evidence quality decision and provenance receipt absent. |
| [AC-07][sp07] | State is usable as SP-08 Roadmap input. | No assessment or Roadmap. | V | **blocked** — no input handoff. |
| [AC-08][sp07] | Sparse Trial gives partial/Unknown result without invented level. | Short Trial remained `in_progress`. | V | **blocked** — sparse terminal Trial not run. |
| [AC-09][sp07] | SP-07 does not speak CEFR during Trial. | No spoken CEFR in observed utterances; no SP-07 job. | V | **blocked** — assessment timing/voice separation not tested at completion. |

## SP-08 — Roadmap Generation

| AC | Expected | Actual DEV result | Evidence | Verdict and exact reason |
| --- | --- | --- | --- | --- |
| [AC-01][sp08] | Full ordered A2→B2 path across four MVP Skills. | No assessment or Roadmap. | V | **blocked** — generation did not run. |
| [AC-02][sp08] | Separate current skill levels shape the path. | All system skill levels Unknown; no Roadmap. | V | **blocked** — differentiated input absent. |
| [AC-03][sp08] | Known skipped with reason; Unknown stays distinct. | No Topic knowledge or Tasks. | V | **blocked** — no generated items to compare. |
| [AC-04][sp08] | Provisional hypotheses clearly labeled. | No Roadmap. | V | **blocked** — no hypothesis output. |
| [AC-05][sp08] | Personal order without global prerequisite graph. | No Roadmap generation. | V | **blocked** — live ordering absent. |
| [AC-06][sp08] | Work goal changes content/priority without dropping B2 base. | IT/meeting context saved in text; structured goal null; no Roadmap. | T, V | **blocked** — no work-goal Roadmap or base comparison. |
| [AC-07][sp08] | No duplicate active Student+Topic+Problem Task. | No Tasks created. | V | **blocked** — generation/retry not exercised. |
| [AC-08][sp08] | New Tasks start at 0% Progress. | No Tasks created. | V | **blocked** — no Task records. |
| [AC-09][sp08] | Output ready for SP-09/10/11 and free delivery. | No Roadmap/job/delivery receipt. | V | **blocked** — entire downstream handoff absent. |
| [AC-10][sp08] | Generation does not calculate Stage Score or choose exercises. | No generation run. | V | **blocked** — ownership boundary not checked on DEV. |

## SP-11A — First Roadmap delivery

| AC | Expected | Actual DEV result | Evidence | Verdict and exact reason |
| --- | --- | --- | --- | --- |
| [AC-1][sp11a] | Send Roadmap after unpaid Trial. | Synthetic student has no Telegram chat; Trial not complete. | T, V | **blocked** — requires coordinated `@alexgusev93` DEV Telegram UI and delivery receipt. |
| [AC-2][sp11a] | Show goal, target, full path, nearest Tasks and reasons. | No Roadmap or visible message. | V | **blocked** — generation/delivery absent. |
| [AC-3][sp11a] | Unknown/Presumed not stated as proven gaps. | No visible message. | V | **blocked** — copy not observed. |
| [AC-4][sp11a] | Omit invented date when forecast unavailable. | No visible message. | V | **blocked** — sparse forecast copy not observed. |
| [AC-5][sp11a] | Delivery works without SP-19/20. | No delivery. | V | **blocked** — runtime independence needs completed Trial and send receipt. |

## Parent SP-11 — stage A only

| AC | Expected | Actual DEV result | Evidence | Verdict and exact reason |
| --- | --- | --- | --- | --- |
| [AC-01][sp11] | Roadmap reaches every learner after Trial. | No terminal Trial; agent identity has no Telegram recipient. | V | **blocked** — real test Telegram delivery not run. |
| [AC-02][sp11] | Full goal/path and nearest Tasks visible. | No visible Roadmap. | V | **blocked** — generation and UI absent. |
| [AC-03][sp11] | Next Task has an understandable reason. | No Task or visible explanation. | V | **blocked** — no output to inspect. |
| [AC-04][sp11] | Unknown/Presumed differentiated from proven gaps. | No visible Roadmap. | V | **blocked** — copy not observed. |
| [AC-06][sp11] | No arbitrary CEFR averaging. | No generated/delivered levels. | V | **blocked** — level presentation not observed. |
| [AC-07][sp11] | Forecast interval or none when evidence is sparse. | No forecast. | V | **blocked** — post-Trial forecast not observed. |
| [AC-08][sp11] | Existing My Progress accessible without mandatory home screen. | Mini App UI was not opened in V. | V | **blocked** — UI acceptance belongs to coordinated Telegram/Mini App test. |
| [AC-09][sp11] | Payment does not hide the Roadmap. | Test student unpaid, but no Roadmap/message. | T, V | **blocked** — unpaid delivery not observed. |
| [AC-10][sp11] | Regular schedule follows Roadmap, not Trial call. | No wrap-up or Roadmap transition. | V | **blocked** — ordering not observed. |

## Parent SP-17 — Trial-dependent criteria

| AC | Expected | Actual DEV result | Evidence | Verdict and exact reason |
| --- | --- | --- | --- | --- |
| [AC-01][sp17] | Common Observation format accepts Trial evidence. | No Observation from completed Trial. | V | **blocked** — common producer/record not observed. |
| [AC-02][sp17] | Source and Task/Stage/Action refs retained when present. | Trial conversation IDs saved; no Observation refs. | V | **blocked** — provenance not checked in Observation record. |
| [AC-03][sp17] | Independent/hinted/self-corrected/uncorrected distinct. | No classified Observation. | V | **blocked** — outcome variants not elicited. |
| [AC-04][sp17] | Silence not error; uncertainty guarded. | No silence or uncertainty Observation. | V | **blocked** — branches not exercised. |
| [AC-05][sp17] | Repeated attempt does not double count. | No replayed transcript or score. | V | **blocked** — idempotency not exercised live. |
| [AC-08][sp17] | Missing Topic/Problem remains Unknown. | Topic Knowledge absent and no assessment. | V | **blocked** — Trial post-processing not run. |
| [AC-09][sp17] | Detection distinct from active Task Stage Progress. | No Task/Observation route. | V | **blocked** — route not visible in Trial result. |
| [AC-10][sp17] | SP-07 receives standardized events. | No Observation → Assessment job. | V | **blocked** — consumer receipt absent. |
| [AC-11][sp17] | Existing post-call analysis preserved. | No terminal call or analysis. | V | **blocked** — post-call compatibility not exercised. |

Parent SP-11 AC-05/11 belong to stage B; parent SP-17 AC-06/07 need Teaching Methods/Homework evidence. Other sessions own those interfaces.

## Test harness handoff

The local patch adds `early-exit` and `phase-probe` to the DEV workflow. The phase probe uses server `connectedSeconds` and waits across natural turn boundaries. `expectFinished` requires a terminal relay event. The DEV-only `voice-state` response adds scoped raw Trial events and job receipts for the owned `agent_test` lesson. These edits require parent integration and DEV deployment before they provide live evidence. Local Python client tests: 8/8 passed. `git diff --check` passed. The short V run used the already merged fixed runner client, before this new patch.

[sp06]: https://app.notion.com/p/418045c3d5768175a536f196d96fa72f
[sp17b]: https://app.notion.com/p/41a045c3d5768186b157e286e8486781
[sp07]: https://app.notion.com/p/418045c3d576819a954ce931bc9446e2
[sp08]: https://app.notion.com/p/418045c3d576815580d4fa9659b5e71b
[sp11a]: https://app.notion.com/p/41a045c3d576816c93bbcf346212e260
[sp11]: https://app.notion.com/p/418045c3d57681be85feddb2a33ae1c8
[sp17]: https://app.notion.com/p/418045c3d5768127b1dbcce526d667de
