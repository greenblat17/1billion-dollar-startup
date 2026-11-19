# SP-20 Post-Lesson Review

Local implementation, 2026-11-17. This is not DEV or live acceptance.

`lesson.complete` queues `lesson.review` for a regular lesson. A later saved
lesson event or current Observation revision queues a new review fingerprint.
The worker sends an empty result; Foundation reads the persisted SP-17
Observations, SP-18 proposals and SP-19 Score/Task Progress under the student
lock. It does not calculate any of these values again. Review history is
append-only in V64; the current row points to the latest applied revision.
The job receipt and evidence fingerprint stop replay and supersede stale jobs.

The review moves an In Progress or Reopened Task to Mastered only when SP-19
reports `masteryReady`, all current Plan stages use real Observation scores,
and usable current lesson evidence is available. SP-04 keeps the Task history.
It leaves other Tasks and their Stage Scores intact. Reopening a Mastered Task
remains an explicit SP-09 learner decision. Problem proposals remain proposals;
SP-20 does not create Tasks or alter a declined decision.

After the review, Foundation queues SP-09 with `post_lesson_review`. The
existing priority service keeps personal prerequisites and deferrals, removes
Mastered Tasks from active order, and saves the next Task. The review itself
never recreates the SP-08 initial Roadmap. `nextLessonContext` in the Foundation
snapshot gives the prior Task results, exact Observation IDs/revisions, live
source expiry/current-revision flags, proposal refs and the current SP-09 next
Task. A missing or stale priority gives `priorityReady=false`; it does not
claim a next meeting. Existing lesson evaluation notes provide the qualitative
outcome; next-lesson context hides notes when their source text has expired.
The immutable review journal stores only the evaluation reference, never a
second copy of those notes. A privacy forget removes the review projection and
journal.
The same snapshot is available to SP-16 preparation and
to later SP-11/SP-21 consumers.

Topic Knowledge remains unchanged. SP-17C currently supplies Observations but
does not emit the approved Known/Not Known conclusion described in the SP-20
Notion handoff. Its sufficiency rule is still a product decision. Task Mastered
does not imply Topic Known. Homework status is not scored or inferred here;
SP-21 owns that status and consent. Personal Memory uses its existing contract.

Local verification: focused Python tests, Kotlin unit suite, fat jar and detekt.
The V64/PostgreSQL integration case is registered in
`:server:foundationIntegrationTest` and needs `TEST_POSTGRES_URL`. These fixtures
do not establish live model, Voice, Telegram, DEV or PROD quality.
