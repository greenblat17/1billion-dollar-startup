# SP-19A — Score Mathematics & State Contract

Scope: local fixture-driven score calculation and PostgreSQL state. SP-19B owns consumption of real SP-17 Observations and integration with Teaching Actions. The numbers below are experimental MVP parameters, not CEFR ratings.

## Inputs and mathematics

The input Plan is the current SP-12A definition for one SP-04 Task. A numeric Stage starts at `S=50`; an unstarted Stage contributes zero. Outcomes use SP-17A names. `independent_correct` applies `S+(100-S)*0.12`; `uncorrected_error` applies `S*0.90`; `self_corrected_error` applies `S*0.96`. `hinted_correct`, `unresolved` and `invalid` do not change S. The score remains in `[0,100]`.

`N(S)=clamp((S-50)/35,0,1)` and `TaskProgress=100*sum(weight*N(S))`. SP-12A requires numeric weights to sum exactly to 1. Binary stages have no weight. Every required binary condition must be met for `masteryReady`, along with every numeric Stage reaching 85 and Progress reaching 100. A binary-only Plan gives 0 Progress and no mastery signal; its completion policy is deferred.

At `S>=85`, the Stage is completed. The snapshot retains its score and contribution when later events arrive. `S<=15` is a `review_approach` signal, not an automatic transition. An operator may explicitly reopen a completed numeric Stage with reason `pedagogical_review` or `manual_correction`: it gets a new `stageAttemptId`, `attemptNumber+1`, and `S=50`. The criteria for choosing such a reason and the pedagogical trigger remain open for SP-19B/20.

## Storage and API

V58 adds `learning_score_stage` (current attempt per Task/Plan revision/Stage) and `learning_score_event` (fixture event history). Both delete with the Task. Plan revisions remain distinct: scores are never transferred to a changed definition. The current Task/Plan revision is checked before writes. Foundation's student lock and operation receipt protect concurrent writes; `fixtureEventId` prevents a second application of the same event on the same Stage. Different payloads with the same ID conflict.

`learning.score.fixture` is an **operator-only synthetic command**, not a model-facing scoring command. Arguments: `taskId`, `planId`, `planRevision`, `stageId`, `fixtureEventId`, plus exactly one of `outcome`, `binaryMet=true`, or `reopen=true` with `reason`. It writes the Stage event and SP-04 Task Progress in one transaction. It does not transition the Task to Mastered. Only SP-04/SP-20 may apply lifecycle decisions. Homework status and Task priority are not inputs.

Scoped reads: `GET /internal/foundation/v1/learning-tasks/{id}/score` and `/score/history?offset=N`. The snapshot returns `progress`, `masteryReady`, Plan identity and Stage rows with `score`, `completed`, `signal`, `stageAttemptId`, attempt number, and state revision. A Stage without stored events is projected as S=50/zero contribution. History is paginated. Reads require student scope and do not create state.

## Verification boundary

The synthetic Plan fixture has Explanation binary, Exercises weight 0.3, and Conversation weight 0.7. Mathematics tests verify 0%, 17.14%, 30%, 70%, completed-stage retention, binary gating, thresholds and inert hinted outcomes. PostgreSQL tests exercise fixture replay, history and restart persistence when `TEST_POSTGRES_URL` is available. This does not verify real Observation routing, ASR uncertainty handling, invalidation/revision replacement, live model outcomes, or Stage reopen criteria. SP-19B must map verified current Observation revisions to this state contract, handle replacement/replay, and reconcile Task Progress after a Plan revision before claiming integration acceptance.
