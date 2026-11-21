# SP-13B: generation and semantic validation

`ai-service/app/learning_content_generation.py` prepares material for one current SP-12A
Stage. Its caller selects the Action kind; SP-12B owns the Plan and stage order. The
generator reads the scoped Task/Stage, published SP-02 Topic/Problem, and confirmed
skill CEFR from Foundation. `Unknown` remains `Unknown`; self-report is not promoted
to an assessment. The caller supplies at most 600 characters of relevant, consented
learner context. It must not pass raw transcripts or secrets.

`prepare(scope, ContentRequest)` calls the model for a draft and then for an
independent semantic review. The review checks Topic objective/scope, Problem,
CEFR, Action, language, voice use, ambiguity, and every proposed alternative.
Local checks enforce the SP-13A field limits, tagged payload, and absence of URLs
in spoken material. A failed check raises `ContentGenerationError`; no Content is
written or issued. A model review is an operational quality gate, not proof that
all model errors are caught. The review rationale stays in the caller's transient
result; it is not copied to the durable Content document.

The caller retains the returned `PreparedContent` and `operationId` for retries.
`create` persists its passed document through `learning.content.create`. `revise`
persists a different, validated formulation through `learning.content.revise`
with the same Task/Stage/Method/Action identity. A new follow-up question may use
`create`; simplify, rephrase, and replace-defect use `revise`. After an uncertain
write, retry the **same** document and operation ID. Re-running `prepare` may
generate different text and is not a write retry.

SP-14/15 call the existing Foundation `learning.content.issue` for a regular
Lesson and use `issued_handoff(content)` on the issued record. The common output
contains exact `contentRef={contentId,revision}`, `lessonId`, `stageRef`, method,
Action kind, spoken instruction, payload, and private `answerGuide`. The guide
describes criteria, valid alternatives and unacceptable meanings. It is not a
string-match answer key. SP-14/15 decide how an actual learner response fits,
including a valid answer absent from the examples, or ask for clarification.
They must not read the guide aloud as a hint. Exposure reconstruction requires
close wording; its separate meaning check accepts a semantic explanation.

If an issued condition is defective, `report_issued_defect` marks that exact
revision with an SP-13A reason. SP-14/15 can then request a replacement without
penalizing the learner. SP-17C owns the `defective_content` Observation and its
scoring eligibility; SP-19 owns Score. Content generation does not record either.
No external video or audio URL is generated. SP-21 owns video sources. Audio
rendering, ASR, playback, learner answer interpretation and lesson orchestration
remain SP-14/15/16 integration work.

Local tests cover all four methods, pinned ownership/catalog/CEFR, meaningful
alternatives, rejection of ambiguity and links, revision, and consumer/defect
handoff. These use fixed model responses. They do not prove live model quality,
voice playback, or a full regular lesson.
