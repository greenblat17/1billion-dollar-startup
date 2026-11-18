# SP-08/09/10 scoped synthetic Roadmap acceptance

These scenarios use `foundation-check-remote.py` on the existing DEV Ktor loopback.
The runner creates an `agent_test` student, issues a fresh operator scope for each
step, and never exports that scope. It requires `SPEAKY_ENV=dev` and
`AGENT_TEST_ENABLED=true`. Run only after the deployed revision includes the
SP-08/09 contracts being checked. No scenario calls a model or Telegram API.

Use the **same synthetic `studentId`** in each runner input. A new UUID creates
an isolated student. The runner calls `identity.resolve("agent_test", UUID)`;
therefore an old **Telegram** Trial UUID does not select its Telegram student.
Use only a UUID previously run by the agent-test harness for a read-only
existing-student capture. An unknown UUID creates a new, empty synthetic
student, so classify the ID before claiming a read-only check. The runner's
`request.kind=source` creates a synthetic
conversation event; `$ref` passes its source into structured writes. Keep the
runner's sanitized `result.json` for each scenario separately. The runner's
input format and execution method are in `infra/dev/foundation-check-remote.py`.

1. `foundation-roadmap-inputs.json` writes a work goal, A2 self-report, mixed
   Speaky skill levels, 3×30 minute lessons, 20 minutes of daily Homework, a
   deadline, and one Known B2 Grammar Topic. Its oracle checks storage and
   confirms that Roadmap and priority do not exist before Trial.
   This fixture is for independent input and mixed synthetic system-level checks. Do
   not start its Trial: `TrialAssessment.apply` rejects synthetic Speaky level
   provenance before writing an assessment. A Trial may also try to revise the
   synthetic Known Topic, which the product guard protects.
1a. `foundation-roadmap-self-report-inputs.json` is the separate next-Trial
    fixture. It writes the same work goal and workload, with explicit A2
    self-assessment for Speaking, Listening, Grammar, and Vocabulary. It writes
    no Speaky levels or Topic Knowledge. Run `self_report_inputs` before Trial
    and `self_report_posttrial` after the genuine Trial pipeline. The latter
    checks the Roadmap, priority, Tasks, provisional estimate, and Trial-only
    provenance for any measured levels. It does not claim mixed measured levels
    or Known Topic omission; use independent real assessment evidence for those
    behaviors. Synthetic system fields do not prove measurement.
2. Complete a genuine synthetic Trial/Assessment/Roadmap pipeline for that
   student through the approved DEV harness. This is a separate, potentially
   paid acceptance step. No Foundation command directly requests
   `roadmap.generate`; the Trial job owns it. Do not treat a manually saved
   Roadmap as proof of this step.
3. `foundation-roadmap-posttrial.json` reads state, Roadmap, priority, and four
   pages of Tasks. Its oracle checks the route's four Skills, Knowledge labels,
   Known omission, unique Task links, zero initial progress, priority overlay,
   and a provisional SP-10 projection from the captured state.
4. `foundation-roadmap-reestimate.json` keeps the existing Roadmap, changes
   Homework consent to No, then raises lesson frequency to four per week. Its
   oracle checks that the route, priority, goal, Task lifecycle, and progress
   survive; Homework minutes stop contributing; and the provisional range
   responds in the expected direction.

For a **past agent-test Trial UUID**, run `foundation-roadmap-existing-core.json`
and `existing` oracle first. It only reads state, Roadmap, jobs, and Tasks; it
does not assume the work-goal/Known fixture above. If the deployed revision has
SP-09 priority reads, `foundation-roadmap-existing-read.json` adds priority.
Both use the same `existing` oracle. Neither mutates the Trial or its fixture
when the UUID already belongs to `agent_test`. The strict `posttrial` oracle
applies only to the new work-goal/Known scenario after its Trial.

Run an oracle with `python3 infra/dev/roadmap_acceptance_oracles.py
inputs|self_report_inputs|posttrial|self_report_posttrial|reestimate|existing
<sanitized-result.json>`. An oracle exits nonzero
on any mismatch. The checks never claim that Cambridge guided hours predict
this learner's real completion date. They also cannot validate pedagogical
quality, Telegram delivery, or observed pace from SP-19/20.

## AC coverage from these captures

| Task | Direct synthetic oracles | Still needs another acceptance step |
| --- | --- | --- |
| SP-08 | AC-01/02/03/04/07/08 and the data handoff in AC-09 after Trial; work goal focus from AC-06 | AC-05 pedagogical order, AC-06 real lesson content, AC-09 Telegram delivery, AC-10 negative score/action audit |
| SP-09 | AC-01/02 initial next Task and evidence; unchanged Task progress from AC-08 | AC-03/04/05/06/07/09/10 need decision/lesson/score flows or negative audit; AC-08 needs a real reorder |
| SP-10 | AC-01/02/03/04/06/07/09 and read adapter in AC-10 | AC-05 source applicability, AC-08 measured pace, AC-10 SP-11 display/delivery |

The runner's current DEV allowlist already permits all prepared `source`,
`learning.update`, `learning.topic.record`, and Roadmap/priority/Task reads.
**Required allowlist diff: none.** A direct `roadmap.generate` shortcut would
bypass the Trial contract, so no route or command is added for it. This is why
the post-Trial capture remains a deferred integration step.

The oracle loads the **checked-out** `ai-service/app/roadmap_timeline.py` at
runtime. During cross-branch review, set `ROADMAP_TIMELINE_SOURCE` to the
current HEAD file. The acceptance files do not change SP-10 implementation.
