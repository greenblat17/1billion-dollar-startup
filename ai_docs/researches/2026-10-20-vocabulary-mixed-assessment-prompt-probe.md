# Vocabulary alternatives beside correction examples: synthetic prompt probe

Date: 2026-10-20. Status: isolated model probe; no deployment or push.

## Method

Six synthetic conversations were supplied directly to the existing onboarding or
practice-call final-review prompt, paired with the old and local revised Vocabulary
suggestion policies. Four conversations included an already verified Vocabulary error
and another phrase that could support an independent alternative. One conversation had
no error but a potential reusable expression; one contained already natural expressions
that should remain unchanged. The target model was `openai/gpt-4o-mini` through the PROD
AI container's configured OpenRouter account. These were isolated provider requests:
they did not create Telegram chats, call records, Redis state, or assessments.

For each response, the local `select_vocabulary_suggestions` filter checked exact
transcript grounding and overlap with the supplied correction. Five further requests
tested a trial prompt that explicitly told the model to set aside correction examples
and included one mixed correction/alternative illustration. The trial was not applied
to the repository prompt.

## Results

| Prompt | Requests | Proposed corrections relabelled as alternatives | Useful alternatives passing the local filter |
| --- | ---: | ---: | ---: |
| Old policy | 6 | 4 of 4 mixed cases | 0 |
| Local revised policy | 6 | 4 of 4 mixed cases | 0 |
| Explicit set-aside trial | 5 | 4 of 4 mixed cases | 0 |

All 17 requests returned parseable responses. In each mixed case the model proposed
the known `do a decision` -> `make a decision` repair as a Vocabulary suggestion,
despite receiving that repair in `vocabularyExamples`. The local filter rejected it.
Neither the old nor the revised prompt produced an alternative for the no-error
conversation with `outside my day job` and `use what I have`. The already natural
conversation produced no suggestion, as desired. The trial prompt did not improve
these outcomes.

## Interpretation

The revised prompt and filter safely prevent the observed duplicate correction from
appearing as an optional alternative. They did not increase useful Vocabulary
alternatives in this small synthetic sample. The model appears to focus on the salient
correction example even when asked to inspect other phrases; this is an inference from
the outputs, not a proven mechanism. The semantic value of other alternatives, model
variability, and real-user rates remain unmeasured.

This probe isolates final-review suggestion generation. It supplied known correction
examples rather than running live-turn discovery or final verification, and it did not
exercise Telegram rendering. The latter has a focused local test for showing both
sections. Do not treat the local prompt change as a demonstrated Vocabulary UX gain or
deploy it on the strength of this probe. A separate suggestion-focused request or a
different review design would need its own latency, cost, and quality evaluation.
