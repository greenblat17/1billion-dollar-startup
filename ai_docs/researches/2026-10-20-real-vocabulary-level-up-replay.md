# Real-conversation replay of the Vocabulary level-up prompt

Date: 2026-10-20. Status: isolated, read-only provider evaluation. The prompt was not deployed, and the probe did not change Telegram, Redis, or assessment records.

## Method

The locally revised live notes prompt and final alternative verifier were sent through the configured PROD provider routes (`openai/gpt-5.6-luna` and `openai/gpt-4o-mini`). The first sample contained 58 real voice-turn transcripts from 29 retained, reviewed conversations: 32 ordinary practice-call turns and 26 onboarding turns. Each eligible conversation contributed two turns. The second sample replayed all 67 turns from five complete conversations: two practice calls and three onboardings. These samples can overlap. Records and turns with contact details were excluded by a simple text filter; the sample is deterministic but selected, not representative. Raw transcripts and model responses were held only in private temporary files during the evaluation and removed afterward. No original audio was reviewed, so ASR uncertainty remains.

For each turn, the local parser applied the same grounding and overlap checks as the proposed product code. For the five complete conversations, saved candidates were constructed from the parsed turns, verified in separate error and alternative groups of five, and passed through the assessment's selection function. The score and assessment prose were not regenerated. Provider latency is only the isolated model-call duration, not a production end-to-end measurement.

## Results

| Sample | Turns | Raw alternative proposals | Survived local parser | Accepted by final verifier |
| --- | ---: | ---: | ---: | ---: |
| Two turns per conversation | 58 | 15 | 5 | 3 in one alternative-verifier batch |
| Five complete conversations | 67 | 17 | 7 | 5 in the full grouped final verification |

The first sample had one notes timeout at ten seconds. Its median provider duration was 2.63 seconds; p95 was 5.59 seconds. All 67 full-conversation notes requests finished before the deadline; median was 1.96 seconds and p95 was 5.13 seconds. The local parser also retained 23 and 20 Vocabulary error candidates, respectively, before final verification.

The full assessment selection would show one alternative in the first practice call, three in the second, one in the first onboarding, and none in the other two onboardings. `put makeup on my face` → `put on makeup` teaches a conventional phrase. The user also confirmed that `feel scared` → `feel frightened` is a useful lexical level-up; the initial review wrongly classified it as a same-level synonym. The other displayed pairs were `didn't have any problem with cleaning roads and streets` → `didn't have any trouble clearing the roads and streets`, `the average is 4 kilometers a day` → `I walk an average of 4 kilometers a day`, and `the way to see the world` → `a way of seeing the world`. Their usefulness needs separate judgment; the first may change the action, and the latter two are sentence-level rewrites rather than clear lexical upgrades. This is a manual review of transcripts, not audio-grounded adjudication.

In the separate 58-turn sample, the verifier also accepted `begun` → `started` and `a little bit rusty` → `a little rusty`. Both violate the level-up criterion. The parser rejected ten of that sample's fifteen raw alternative proposals. The per-candidate rejection reasons from that first run were not retained, so no exact breakdown can be claimed.

A repeat request on the same five complete conversations produced 15 raw alternatives, not the original 17; provider output varied despite temperature zero. The local parser kept nine and rejected six: three quoted contexts did not match the transcript exactly, two had only `medium` confidence, and one overlapped a separate correction. The final alternative verifier accepted five of the nine. This repeat is a diagnostic run, not a reconstruction of the original 17 proposals.

## Conclusion

The detailed prompt can produce useful idiomatic and lexical suggestions on real speech, but the evaluation cannot establish sufficient precision for rollout. The initial review applied too narrow a definition of lexical level-up; other pairs need explicit product judgments before a quality rate is reported. Raw proposals still include downgraded synonyms, disguised error repairs, and meaning changes, and the final verifier sometimes accepts them. The two samples are selected, overlap may exist, and unlabelled turns do not establish recall. A next iteration needs a manually labelled, audio-checked real-turn set with explicit good, bad, and missed alternatives before changing the model or loosening discovery.
