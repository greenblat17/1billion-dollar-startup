package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Applies already persisted SP-17/18/19 results once per current regular-lesson evidence revision. */
internal class PostLessonReview {
    fun enqueue(c: Connection, student: String, lessonId: String, now: Instant) {
        val lesson = c.document("SELECT to_jsonb(l) FROM lesson l WHERE student_id=? AND id=?", student, lessonId) ?: return
        if (lesson.text("kind") != "regular" || lesson.text("status") != "completed") return
        val fingerprint = fingerprint(c, student, lessonId, lesson.number("transcript_revision"))
        c.enqueueJob(student, "lesson.review", "$lessonId:$fingerprint", lessonId,
            json("transcriptRevision" to lesson.number("transcript_revision"), "evidenceDigest" to fingerprint), now)
    }

    private fun fingerprint(c: Connection, student: String, lessonId: String, transcript: Long): String {
        val revisions = c.documents("""SELECT jsonb_build_object('id',id,'revision',revision,'status',status)
            FROM learning_observation WHERE student_id=? AND lesson_id=? ORDER BY id""", student, lessonId)
        val evaluation = c.document("""SELECT jsonb_build_object('analyzerVersion',analyzer_version,'sourceRevision',source_revision)
            FROM lesson_evaluation WHERE student_id=? AND lesson_id=? AND source_revision=?
            ORDER BY created_at DESC LIMIT 1""", student, lessonId, transcript)
        return digest(canonical(json("transcriptRevision" to transcript, "observations" to JsonArray(revisions),
            "evaluation" to evaluation)))
    }

    fun apply(c: Connection, scope: ExecutionScope, lessonId: String, inputs: JsonObject, result: JsonObject, now: Instant): Boolean {
        result.only()
        val lesson = c.lesson(scope, lessonId)
        if (lesson.text("kind") != "regular" || lesson.text("status") != "completed" ||
            lesson.number("transcript_revision") != inputs.number("transcriptRevision") ||
            fingerprint(c, scope.studentId, lessonId, lesson.number("transcript_revision")) != inputs.text("evidenceDigest")) return false
        val previous = c.document("SELECT to_jsonb(r) FROM post_lesson_review r WHERE student_id=? AND lesson_id=?", scope.studentId, lessonId)
        if (previous?.text("evidence_digest") == inputs.text("evidenceDigest")) return true

        val rows = c.documents("""SELECT to_jsonb(o) FROM learning_observation o WHERE student_id=? AND lesson_id=?
            ORDER BY created_at,id""", scope.studentId, lessonId)
        val observations = rows.map { row ->
            val projection = LearningObservations(learningPlanObservationStage).read(c, scope.studentId, row.text("id"), now).obj("observation")
            json("id" to row.text("id"), "revision" to row.number("revision"), "status" to row.text("status"),
                "taskId" to row.obj("document").optionalText("taskId"),
                "topicId" to row.obj("document").optionalText("topicId"),
                "actionId" to row.obj("document").optionalText("actionId"),
                "scoringEligible" to (projection.text("scoringEligible") == "true"),
                "evidenceUsable" to (projection.text("evidenceUsable") == "true"))
        }
        val taskIds = rows.mapNotNull { it.obj("document").optionalText("taskId") }.distinct()
        val taskResults = taskIds.map { id ->
            var task = LearningTasks().get(c, scope.studentId, id)
            val score = if (c.document("SELECT to_jsonb(p) FROM learning_plan p WHERE student_id=? AND task_id=?",
                    scope.studentId, id) != null) LearningScores().read(c, scope.studentId, id) else null
            val sources = observations.filter { it.text("evidenceUsable") == "true" && it.optionalText("taskId") == id }.take(20).map {
                json("type" to "learning_observation", "id" to it.text("id"), "revision" to it.number("revision"))
            }
            val realScore = c.document("""SELECT jsonb_build_object('count',count(*)) FROM learning_score_event e
                WHERE e.student_id=? AND e.task_id=? AND e.observation_id IS NOT NULL""", scope.studentId, id)?.number("count") ?: 0
            val syntheticStages = if (score == null) 0 else c.rows("""SELECT count(*) FROM learning_score_stage
                WHERE student_id=? AND task_id=? AND plan_id=? AND plan_revision=? AND score_source='fixture'""",
                scope.studentId, id, score.text("planId"), score.number("planRevision")) { it.getLong(1) }.single()
            if (score?.text("masteryReady") == "true" && realScore > 0 && syntheticStages == 0L && sources.isNotEmpty() &&
                task.text("lifecycle") in setOf("In Progress", "Reopened")) {
                task = LearningTasks().change(c, scope.copy(role = "operator"), "transition", json(
                    "taskId" to id, "expectedRevision" to task.number("revision"),
                    "component" to "sp20.post_lesson_review", "reason" to "sp19_mastery_ready",
                    "decisionId" to "$lessonId:${inputs.text("evidenceDigest")}", "sources" to JsonArray(sources),
                    "lifecycle" to "Mastered"), now).obj("task")
            }
            json("taskId" to id, "lifecycle" to task.text("lifecycle"), "taskRevision" to task.number("revision"),
                "progress" to task["progress"], "scorePlanId" to score?.optionalText("planId"),
                "scorePlanRevision" to score?.get("planRevision"), "masteryReady" to (score?.text("masteryReady") == "true"))
        }
        val proposals = mutableListOf<kotlinx.serialization.json.JsonElement>()
        var offset = 0
        do {
            val page = ProblemDetection().read(c, scope.studentId, null, null, offset)
            (page["items"] as JsonArray).forEach { item ->
                (item as JsonObject)["proposal"]?.takeUnless { it == kotlinx.serialization.json.JsonNull }?.let(proposals::add)
            }
            offset = page.optionalText("nextOffset")?.toInt() ?: break
        } while (offset <= 100000)
        val priority = c.document("SELECT to_jsonb(p) FROM learning_roadmap_priority p WHERE student_id=?", scope.studentId)
        val evaluation = c.document("""SELECT jsonb_build_object('sourceRevision',e.source_revision,'analyzerVersion',e.analyzer_version)
            FROM lesson_evaluation e WHERE e.student_id=? AND e.lesson_id=? AND e.source_revision=?
            ORDER BY e.created_at DESC LIMIT 1""", scope.studentId, lessonId, lesson.number("transcript_revision"))
        val document = json("schemaVersion" to 1, "lessonId" to lessonId,
            "transcriptRevision" to lesson.number("transcript_revision"), "outcomeReason" to lesson.optionalText("outcome_reason"),
            "endedEarly" to lesson["ended_early"], "observations" to JsonArray(observations),
            "tasks" to JsonArray(taskResults), "problemProposals" to JsonArray(proposals),
            "topicKnowledgeDecision" to "deferred_no_approved_sp17_conclusion",
            "evaluationRef" to evaluation,
            "priorityRevisionBefore" to priority?.get("revision"),
            "homework" to "not_assessed_by_sp20")
        val revision = (previous?.number("revision") ?: 0L) + 1
        c.update("""INSERT INTO post_lesson_review_history(student_id,lesson_id,revision,transcript_revision,evidence_digest,document,recorded_at)
            VALUES (?,?,?,?,?,?::jsonb,?)""", scope.studentId, lessonId, revision,
            lesson.number("transcript_revision"), inputs.text("evidenceDigest"), document, now)
        c.update("""INSERT INTO post_lesson_review(student_id,lesson_id,revision,transcript_revision,evidence_digest,document,updated_at)
            VALUES (?,?,?,?,?,?::jsonb,?) ON CONFLICT (student_id,lesson_id) DO UPDATE SET
            revision=EXCLUDED.revision,transcript_revision=EXCLUDED.transcript_revision,
            evidence_digest=EXCLUDED.evidence_digest,document=EXCLUDED.document,updated_at=EXCLUDED.updated_at""",
            scope.studentId, lessonId, revision, lesson.number("transcript_revision"), inputs.text("evidenceDigest"), document, now)
        val roadmap = c.document("SELECT to_jsonb(r) FROM learning_roadmap r WHERE student_id=?", scope.studentId)
        if (roadmap != null) {
            c.update("UPDATE learning_roadmap_priority SET stale_at=? WHERE student_id=?", now, scope.studentId)
            c.enqueueJob(scope.studentId, "roadmap.prioritize", "review:$lessonId:$revision", lessonId,
                json("roadmapRevision" to roadmap.number("revision"), "reviewRevision" to revision,
                    "trigger" to "post_lesson_review"), now)
        }
        c.update("UPDATE lesson_plan SET status='stale' WHERE student_id=? AND status='ready'", scope.studentId)
        return true
    }

    fun latest(c: Connection, student: String): JsonObject? = c.document("""SELECT to_jsonb(r) FROM post_lesson_review r
        JOIN lesson l ON l.id=r.lesson_id WHERE r.student_id=? ORDER BY l.starts_at DESC,r.updated_at DESC LIMIT 1""", student)

    fun nextContext(c: Connection, student: String, now: Instant): JsonObject? {
        val review = latest(c, student) ?: return null
        val priority = c.document("SELECT to_jsonb(p) FROM learning_roadmap_priority p WHERE student_id=?", student)
        val roadmap = c.document("SELECT to_jsonb(r) FROM learning_roadmap r WHERE student_id=?", student)
        val priorNext = priority?.obj("document")?.optionalText("nextTaskId")
        val nextMastered = priorNext != null && c.document("SELECT to_jsonb(t) FROM learning_task t WHERE student_id=? AND id=?",
            student, priorNext)?.text("lifecycle") == "Mastered"
        val current = priority != null && roadmap != null && priority.number("roadmap_revision") == roadmap.number("revision") &&
            priority.optionalText("stale_at") == null && !nextMastered
        val evidence = (review.obj("document")["observations"] as JsonArray).map { item ->
            val ref = item as JsonObject
            val current = LearningObservations(learningPlanObservationStage).read(c, student, ref.text("id"), now).obj("observation")
            json("id" to ref.text("id"), "reviewedRevision" to ref.number("revision"),
                "currentRevision" to current.number("revision"), "status" to current.text("status"),
                "sourceRefs" to current["sources"], "evidenceUsable" to current["evidenceUsable"])
        }
        val notes = (c.document("""SELECT result FROM lesson_evaluation WHERE student_id=? AND lesson_id=?
            AND source_revision=? ORDER BY created_at DESC LIMIT 1""", student, review.text("lesson_id"),
            review.number("transcript_revision"))?.get("notes") as? JsonArray ?: JsonArray(emptyList())).filter { item ->
            val refs = (item as JsonObject)["sourceIds"] as? JsonArray ?: return@filter false
            refs.all { ref ->
                val source = c.document("SELECT to_jsonb(e) FROM conversation_event e WHERE e.student_id=? AND e.id=?",
                    student, (ref as kotlinx.serialization.json.JsonPrimitive).content)
                source?.optionalText("body") != null && Instant.parse(source.text("expires_at")) > now
            }
        }
        return json("reviewLessonId" to review.text("lesson_id"), "reviewRevision" to review.number("revision"),
            "previousTasks" to review.obj("document")["tasks"],
            "previousObservations" to JsonArray(evidence),
            "qualitativeOutcome" to JsonArray(notes),
            "problemProposals" to review.obj("document")["problemProposals"],
            "homework" to review.obj("document")["homework"],
            "priorityReady" to current, "nextTaskId" to if (current) priority.obj("document").optionalText("nextTaskId") else null,
            "focusTaskIds" to if (current) priority.obj("document")["focusTaskIds"] else null)
    }
}
