# Narrow Vocabulary rules: real-dialogue replay

Date: 2026-10-21. Status: isolated read-only evaluation of the local, undeployed code. No Telegram message, Redis record, or saved assessment was changed.

## Sample and method

Six completed, reviewed conversations were selected deterministically from retained production Redis data: three ordinary calls (31 learner turns) and three onboardings (14 learner turns), **45 turns total**. Contact details and explicit school/minor references were filtered from transcripts and coach questions. This small selected sample may overlap earlier evaluations and is not representative. Audio was not reviewed, so ASR uncertainty remains. Raw transcripts and model responses stayed in private temporary files during the run and were removed afterward.

Each turn used the new local notes prompt through the configured production provider route (`openai/gpt-5.6-luna`, 1200 output tokens, ten-second request limit). The local parser applied exact transcript grounding and the new short-span rules. Saved candidates were grouped for the new local final-verifier prompt on `openai/gpt-4o-mini`, in batches of five with a ten-second per-conversation deadline. The current selection functions then assembled up to five Grammar and five Vocabulary error cards, plus any verified single-word upgrades. This replay did **not** compose final assessment prose or scores. Calls were isolated from Telegram and Redis writes.

## Results

| Stage | Grammar | Vocabulary errors | Single-word upgrades |
| --- | ---: | ---: | ---: |
| Raw model notes | 54 | 18 | 0 |
| Local parser retained | 40 | 7 | 0 |
| Final verifier accepted | 36 | 3 | 0 |
| Selected for final cards | 20 | 3 | 0 |

All 45 provider requests returned before the ten-second limit. Median isolated notes duration was 2.37 seconds, p95 was 6.11 seconds, and maximum was 7.42 seconds. No final-verifier batch failed or timed out. These times exclude STT, TTS, Telegram, and queue delay.

Selected Vocabulary cards, with preliminary assessment:

- `politics news` → `political news`: plausible word-form/collocation repair.
- `haven't plenty of vocabulary` → `don't have much vocabulary`: the replacement repairs negation/auxiliary grammar and quantity wording. It is **misclassified as Vocabulary**, yet the final verifier accepted it.
- `do filter coffee` → `make filter coffee`: plausible verb/collocation repair. The surrounding sentence still has an independent error.

Four raw Vocabulary claims occurred in three turns whose `speech_artifacts` arrays contained an inexact transcript quote. The live schema check discards **all notes for each such turn**. One lost pair was `politic` → `a politician`; another was `exciting` → `excited`. They need context/audio review before counting as useful misses. A plausible `compete between each other` → `compete with each other` candidate was also dropped because the model quoted a 38-word context and the parser caps context at 30 words. Other rejected claims included unsupported meaning changes and ASR-uncertain words.

## Interpretation

The narrower rule prevented phrase alternatives, but it did **not** produce a single higher-level word suggestion on these 45 turns. This sample does not establish whether clear upgrade opportunities existed in the turns. The main observed defects are false Vocabulary classification at final verification and whole-turn loss from one malformed `speech_artifact` quote. The current local change should not be deployed on the strength of this run. Review more real turns and audio before claiming acceptable precision or recall.
