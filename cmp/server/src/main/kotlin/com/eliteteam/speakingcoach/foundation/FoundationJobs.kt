package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.RuntimeDiagnostics.Event
import com.eliteteam.speakingcoach.RuntimeDiagnostics.Outcome
import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal val jobKinds = setOf("roadmap.prepare", "roadmap.generate", "roadmap.prioritize", "memory.extract", "lesson.evaluate", "lesson.review", "trial.observe", "trial.assess", "lesson.prepare", "lesson.content.prepare", "schedule.materialize", "notification.reminder", "retention.prune")

internal fun Connection.enqueueJob(student: String, kind: String, key: String, target: String?, inputs: JsonObject, now: Instant,
    sourceJobId: String? = null): String {
    if (kind !in jobKinds) reject("unknown_job_kind")
    val id = newId()
    val inserted = update(
        """INSERT INTO foundation_job(id,student_id,data_epoch,kind,dedup_key,target_id,input_refs,due_at,deadline)
           SELECT ?,id,data_epoch,?,?,?,?::jsonb,?,? FROM student WHERE id=? AND deleted_at IS NULL
           ON CONFLICT(student_id,kind,dedup_key) DO NOTHING""", id, kind, key, target, inputs, now, now.plusSeconds(HISTORY_SECONDS), student,
    )
    val jobId = rows("SELECT id FROM foundation_job WHERE student_id=? AND kind=? AND dedup_key=?", student, kind, key) { it.getString(1) }.single()
    val facts = buildMap {
        put("job_id", jobId)
        put("job_kind", kind)
        sourceJobId?.let { put("source_job_id", it) }
        if (target != null && kind in setOf("trial.observe", "trial.assess", "roadmap.generate", "lesson.review", "lesson.evaluate")) {
            put("lesson_id", target)
        }
    }
    if (inserted > 0) {
        FoundationDatabase.recordAfterCommit(Event("foundation_job_enqueue", kind, "enqueue", Outcome.COMPLETED,
            "Foundation queued $kind."), facts)
    } else {
        FoundationDatabase.recordAfterCommit(Event("foundation_job_enqueue", kind, "enqueue", Outcome.REPLAYED,
            "Foundation reused the existing $kind job.", "job_already_exists"), facts)
    }
    return jobId
}

internal class FoundationJobs {
    fun claim(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator")
        args.only("kind")
        val kind = args.optionalText("kind")
        if (kind != null && kind !in jobKinds) reject("unknown_job_kind")
        terminateUnclaimable(connection, scope.studentId, now, expired = true)
        terminateUnclaimable(connection, scope.studentId, now, expired = false)
        val selected = connection.document(
            """SELECT to_jsonb(j) FROM foundation_job j WHERE student_id=? AND data_epoch=? AND deadline>?
               AND (?::text IS NULL OR kind=?) AND attempts<max_attempts AND ((status IN ('queued','retry_wait') AND due_at<=?) OR (status='running' AND lease_until<=?))
               ORDER BY CASE kind WHEN 'lesson.evaluate' THEN 0 WHEN 'lesson.review' THEN 1 WHEN 'trial.observe' THEN 2 WHEN 'trial.assess' THEN 3 WHEN 'roadmap.generate' THEN 4 WHEN 'roadmap.prepare' THEN 5 ELSE 6 END,due_at,id LIMIT 1 FOR UPDATE SKIP LOCKED""", scope.studentId, scope.epoch, now, kind, kind, now, now,
        ) ?: return json("job" to null)
        val id = selected.text("id")
        if (!BackgroundContracts().current(connection, selected)) {
            connection.update("UPDATE foundation_job SET status='superseded' WHERE id=?", id)
            claimDecision(selected, Outcome.SUPERSEDED, "source_superseded",
                "Foundation superseded the job because its source is no longer current.")
            return json("job" to null)
        }
        if (selected.text("kind") == "lesson.prepare") {
            val target = selected.text("target_id")
            val roadmap = if (connection.lesson(scope, target).text("kind") == "regular") RoadmapPreparation().state(connection, scope.studentId) else null
            val priorityPending = roadmap != null && connection.document(
                """SELECT to_jsonb(j) FROM foundation_job j WHERE j.student_id=? AND j.kind='roadmap.prioritize'
                   AND j.status IN ('queued','running','retry_wait') LIMIT 1""", scope.studentId) != null
            val reason = when {
                connection.pendingEvaluations(scope.studentId, target).isNotEmpty() -> "pending_evaluation"
                roadmap?.optionalText("status") in setOf("queued", "running", "retry_wait", "failed") -> "roadmap_not_ready"
                priorityPending -> "roadmap_priority_pending"
                else -> null
            }
            if (reason != null) {
                connection.update("UPDATE foundation_job SET due_at=? WHERE id=?", now.plusSeconds(5), id)
                claimDecision(selected, Outcome.DEFERRED, reason,
                    "Foundation deferred lesson preparation because ${reason.replace('_', ' ')}.", coalesce = true)
                return json("job" to null)
            }
        }
        if (selected.text("kind") == "notification.reminder") {
            val begun = connection.update("INSERT INTO reminder_delivery(job_id,student_id,lesson_id,lesson_version,outcome,attempted_at) VALUES (?,?,?,?,'unknown',?) ON CONFLICT(job_id) DO UPDATE SET outcome='unknown',attempted_at=EXCLUDED.attempted_at WHERE reminder_delivery.outcome='not_sent'",
                id, scope.studentId, selected.text("target_id"), selected.obj("input_refs").number("lessonVersion"), now)
            if (begun == 0) {
                connection.update("UPDATE foundation_job SET status='failed',error_code='delivery_unknown' WHERE id=?", id)
                claimDecision(selected, Outcome.UNKNOWN, "delivery_unknown",
                    "Foundation stopped the reminder job. The earlier delivery outcome is unknown.")
                return json("job" to null)
            }
        }
        connection.update("UPDATE foundation_job SET status='running',attempts=attempts+1,fence=fence+1,lease_until=?,error_code=NULL WHERE id=?", now.plusSeconds(60), id)
        val job = connection.document("SELECT to_jsonb(j) FROM foundation_job j WHERE id=?", id) ?: reject("not_owned")
        connection.update("INSERT INTO foundation_job_attempt(job_id,fence,started_at) VALUES (?,?,?)", id, job.number("fence"), now)
        return json("job" to job)
    }

    private fun terminateUnclaimable(connection: Connection, studentId: String, now: Instant, expired: Boolean) {
        val predicate = if (expired) "deadline<=? AND status IN ('queued','running','retry_wait')"
            else "lease_until<=? AND status='running' AND attempts>=max_attempts"
        val reason = if (expired) "source_expired" else "retry_exhausted"
        val counts = connection.documents("""WITH changed AS (
            UPDATE foundation_job SET status='failed',error_code=? WHERE student_id=? AND $predicate RETURNING id,kind
        ) SELECT jsonb_build_object('kind',kind,'count',count(*),'id',CASE WHEN count(*)=1 THEN min(id::text) END)
            FROM changed GROUP BY kind""", reason, studentId, now)
        for (group in counts) {
            val kind = group.text("kind")
            val message = if (expired) "Foundation failed jobs whose source deadline expired."
                else "Foundation failed jobs whose lease expired after their final attempt."
            val fields = buildMap {
                put("count", group.number("count").toString())
                put("job_kind", kind)
                put("job_status", "failed")
                group.optionalText("id")?.let { put("job_id", it) }
            }
            FoundationDatabase.recordAfterCommit(Event("foundation_job_claim_decision", kind, "claim", Outcome.FAILED,
                message, reason), fields)
        }
    }

    private fun claimDecision(job: JsonObject, outcome: Outcome, reason: String, message: String, coalesce: Boolean = false) {
        val kind = job.text("kind")
        FoundationDatabase.recordAfterCommit(Event("foundation_job_claim_decision", kind, "claim", outcome, message, reason, coalesce),
            mapOf("job_id" to job.text("id"), "job_kind" to kind, "attempt" to job.number("attempts").toString(),
                "fence" to job.number("fence").toString()))
    }

    fun fail(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        args.only("jobId", "fence", "code")
        val job = ownedRunning(connection, scope, args, now)
        val code = args.text("code")
        if (code !in setOf("provider_timeout", "provider_error", "budget_exhausted", "handler_unavailable", "invalid_result", "source_expired", "missing_action_template")) reject("invalid_error_code")
        if (job.text("kind") == "notification.reminder" && code == "handler_unavailable") {
            connection.update("UPDATE reminder_delivery SET outcome='not_sent' WHERE job_id=?", job.text("id"))
        }
        val retry = code in setOf("provider_timeout", "provider_error") && job.number("attempts") < job.number("max_attempts")
        connection.update("UPDATE foundation_job SET status=?,error_code=?,due_at=?,lease_until=NULL WHERE id=?",
            if (retry) "retry_wait" else "failed", code, now.plusSeconds(5 * job.number("attempts")), job.text("id"))
        connection.update("UPDATE foundation_job_attempt SET finished_at=?,outcome=? WHERE job_id=? AND fence=?", now, code, job.text("id"), job.number("fence"))
        return json("status" to if (retry) "retry_wait" else "failed")
    }

    fun renew(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        args.only("jobId", "fence")
        val job = ownedRunning(connection, scope, args, now)
        val until = minOf(now.plusSeconds(60), Instant.parse(job.text("deadline")))
        connection.update("UPDATE foundation_job SET lease_until=? WHERE id=?", until, job.text("id"))
        if (scope.jobId != null) {
            connection.update("UPDATE execution_scope SET expires_at=? WHERE token_digest=? AND job_id=? AND fence=?",
                until, scope.tokenDigest, job.text("id"), job.number("fence"))
        }
        return json("renewed" to true, "leaseUntil" to until.toString())
    }

    fun apply(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        args.only("jobId", "fence", "result")
        val job = ownedRunning(connection, scope, args, now)
        val result = args.obj("result")
        val target = job.optionalText("target_id")
        val inputs = job.obj("input_refs")
        val applied = when (job.text("kind")) {
            "schedule.materialize", "notification.reminder" -> BackgroundContracts().apply(connection, scope, job, result, now)
            "roadmap.prepare" -> RoadmapPreparation().apply(connection, scope.copy(jobId = job.text("id"), fence = job.number("fence")), inputs, result, now)
            "lesson.evaluate" -> evaluate(connection, scope, target ?: reject("invalid_target"), inputs, result, now)
            "lesson.review" -> PostLessonReview().apply(connection, scope, target ?: reject("invalid_target"), inputs, result, now)
            "trial.observe" -> observeTrial(connection, scope.copy(jobId = job.text("id"), fence = job.number("fence")), target ?: reject("invalid_target"), inputs, result, now)
            "trial.assess" -> TrialAssessment().apply(connection, scope, target ?: reject("invalid_target"), inputs, result, now, job.text("id"))
            "roadmap.generate" -> PersonalizedRoadmap().apply(connection, scope, target ?: reject("invalid_target"), inputs, result, now)
            "roadmap.prioritize" -> {
                val current = PersonalizedRoadmap().read(connection, scope.studentId)
                if (current.number("revision") != inputs.number("roadmapRevision") ||
                    result.obj("decision").number("roadmapRevision") != inputs.number("roadmapRevision") ||
                    inputs.optionalText("reviewRevision")?.toLong()?.let { revision ->
                        connection.document("SELECT to_jsonb(r) FROM post_lesson_review r WHERE student_id=? AND lesson_id=?",
                            scope.studentId, target)?.number("revision") != revision
                    } == true) false
                else {
                    RoadmapPriority().save(connection, scope.copy(role = "operator"), result, now)
                    true
                }
            }
            "lesson.prepare" -> prepare(connection, scope, target ?: reject("invalid_target"), inputs, result, now)
            "lesson.content.prepare" -> prepareTeachingContent(connection, scope, target ?: reject("invalid_target"), inputs, result, now)
            "memory.extract" -> {
                result.only("candidates")
                sourceMessage(connection, scope, inputs.text("sourceId"), now)
                val candidates = result["candidates"]?.jsonArray ?: reject("invalid_result")
                if (candidates.size > 15) reject("invalid_result")
                candidates.forEach { item ->
                    val candidate = item.jsonObject
                    candidate.only("key", "value")
                    val suppressed = connection.document("SELECT to_jsonb(c) FROM memory_candidate c WHERE student_id=? AND fact_key=? AND status='rejected' LIMIT 1", scope.studentId, candidate.text("key"))
                    val forgotten = connection.document("SELECT to_jsonb(f) FROM memory_fact f WHERE student_id=? AND category='personal' AND fact_key=? AND forgotten_at IS NOT NULL", scope.studentId, candidate.text("key"))
                    if (suppressed == null && forgotten == null) StructuredMemory().propose(connection, scope,
                        json("sourceId" to inputs.text("sourceId"), "key" to candidate.text("key"), "value" to candidate.text("value")), now)
                }
                true
            }
            "retention.prune" -> {
                result.only()
                FoundationMaintenance().prune(connection, scope.copy(role = "operator"), now)
                true
            }
            else -> reject("handler_unavailable")
        }
        val status = if (applied) "succeeded" else "superseded"
        connection.update("UPDATE foundation_job SET status=?,lease_until=NULL,result=?::jsonb WHERE id=?", status,
            if (job.text("kind") in setOf("roadmap.prepare", "trial.assess", "roadmap.generate") && applied) result else null, job.text("id"))
        connection.update("UPDATE foundation_job_attempt SET finished_at=?,outcome=? WHERE job_id=? AND fence=?", now, status, job.text("id"), job.number("fence"))
        return json("status" to status)
    }

    private fun ownedRunning(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "learning", "operator")
        if (scope.jobId != null && (scope.jobId != args.text("jobId") || scope.fence != args.number("fence"))) reject("not_owned")
        val job = connection.document("SELECT to_jsonb(j) FROM foundation_job j WHERE student_id=? AND id=?",
            scope.studentId, args.text("jobId")) ?: reject("not_owned")
        if (job.number("data_epoch") != scope.epoch || job.number("fence") != args.number("fence") || job.text("status") != "running" ||
            Instant.parse(job.text("lease_until")) <= now || Instant.parse(job.text("deadline")) <= now
        ) reject("stale_job")
        return job
    }

    private fun observeTrial(c: Connection, scope: ExecutionScope, lessonId: String, inputs: JsonObject,
                             result: JsonObject, now: Instant): Boolean {
        result.only("schemaVersion", "observations", "initialMaterializationOnly")
        if (result.number("schemaVersion") != 1L) reject("invalid_result")
        val lesson = c.lesson(scope, lessonId)
        if (!trialV55(lesson) || lesson.text("status") != "completed") reject("invalid_target")
        if (lesson.number("transcript_revision") != inputs.number("transcriptRevision")) return false
        val items = result["observations"] as? JsonArray ?: reject("invalid_result")
        if (items.size > 80) reject("invalid_result")
        when (result["initialMaterializationOnly"]) {
            null -> Unit
            JsonPrimitive(true) -> {
                if (items.isEmpty()) reject("invalid_result")
                // Under the student lock, prevent a filtered result from invalidating existing evidence.
                val existing = c.rows("SELECT EXISTS (SELECT 1 FROM learning_observation WHERE student_id=? AND lesson_id=?)",
                    scope.studentId, lessonId) { it.getBoolean(1) }.single()
                if (existing) return false
            }
            else -> reject("invalid_result")
        }
        val catalogVersions = items.map { (it as? JsonObject ?: reject("invalid_result")).text("catalogVersion") }.toSet()
        if (catalogVersions.size > 1) reject("invalid_result")
        val writer = LearningObservations(learningPlanObservationStage)
        val operator = scope.copy(role = "operator")
        val seen = mutableSetOf<Pair<String, String>>()
        items.forEach { item ->
            val d = item as? JsonObject ?: reject("invalid_result")
            if (d.text("lessonId") != lessonId || d.text("practice") != "trial" || d.text("route") != "assessment_only" ||
                d.text("skillId") !in setOf("speaking", "listening", "grammar", "vocabulary") ||
                d.optionalText("taskId") != null || d["stageRef"]?.let { it != kotlinx.serialization.json.JsonNull } == true ||
                d.optionalText("actionId") != null) reject("invalid_result")
            val key = d.text("anchorEventId") to d.text("evidenceSlot")
            if (!seen.add(key)) reject("invalid_result")
            val old = c.document("SELECT to_jsonb(o) FROM learning_observation o WHERE student_id=? AND anchor_event_id=? AND evidence_slot=?",
                scope.studentId, key.first, key.second)
            if (old == null) writer.write(c, operator, "record", json("observation" to d), now)
            else if (old.obj("document").obj("provenance").text("component") != "trial-observation-extractor") reject("observation_identity_conflict")
            else if (old.text("status") != "active") reject("observation_invalidated")
            else if (canonical(old.obj("document")) != canonical(d)) writer.write(c, operator, "revise",
                json("observationId" to old.text("id"), "expectedRevision" to old.number("revision"),
                    "observation" to d, "reason" to "Trial source revision or analysis changed"), now)
        }
        val obsolete = c.documents("SELECT to_jsonb(o) FROM learning_observation o WHERE student_id=? AND lesson_id=? AND status='active' AND document->'provenance'->>'component'='trial-observation-extractor'",
            scope.studentId, lessonId)
        obsolete.filter { (it.text("anchor_event_id") to it.text("evidence_slot")) !in seen }.forEach {
            writer.invalidate(c, operator, json("observationId" to it.text("id"), "expectedRevision" to it.number("revision"),
                "component" to "trial-observation-extractor", "reason" to "No longer supported by current Trial analysis"), now)
        }
        val catalogVersion = catalogVersions.firstOrNull() ?: c.document("""SELECT result FROM foundation_job
            WHERE student_id=? AND target_id=? AND kind='trial.assess' AND status='succeeded' AND result IS NOT NULL
            ORDER BY created_at DESC LIMIT 1""", scope.studentId, lessonId)?.optionalText("catalogVersion")
            ?: c.document("SELECT to_jsonb(x) FROM learning_catalog_current x WHERE singleton=TRUE")?.text("version")
            ?: reject("catalog_not_published")
        c.enqueueJob(scope.studentId, "trial.assess", "$lessonId:${inputs.number("transcriptRevision")}:post-trial-assessment-v1", lessonId,
            json("transcriptRevision" to inputs.number("transcriptRevision"), "observationJobId" to scope.jobId,
                "catalogVersion" to catalogVersion), now)
        return true
    }

    private fun evaluate(connection: Connection, scope: ExecutionScope, lessonId: String, inputs: JsonObject, result: JsonObject, now: Instant): Boolean {
        result.only("status", "observations", "facts", "candidates", "notes")
        if (result.text("status") !in setOf("evaluated", "insufficient_evidence")) reject("invalid_result")
        val lesson = connection.lesson(scope, lessonId)
        if (lesson.number("transcript_revision") != inputs.number("transcriptRevision")) return false
        if (connection.document("SELECT to_jsonb(e) FROM lesson_evaluation e WHERE lesson_id=? AND source_revision=? AND analyzer_version=?",
            lessonId, inputs.number("transcriptRevision"), inputs.text("analyzerVersion")) != null) return true
        if (trialV55(lesson) && (inputs.text("analyzerVersion") != "trial-memory-v55" ||
            (result["observations"] as? JsonArray)?.isNotEmpty() != false ||
            (result["notes"] as? JsonArray)?.isNotEmpty() == true)) reject("legacy_trial_assessment_forbidden")
        LessonEvidence().notes(connection, scope, lessonId, result, now)
        val observations = result["observations"]?.jsonArray ?: reject("invalid_result")
        if (observations.size > 30) reject("invalid_result")
        observations.forEach { item ->
            val observation = item.jsonObject
            observation.only("key", "dimension", "content", "uncertainty", "sourceId", "evidence")
            if (observation.text("dimension") !in setOf("fluency", "confidence", "autonomy", "grammar", "vocabulary") ||
                observation.text("uncertainty") !in setOf("limited", "tentative", "supported") || observation.text("content").length > 1000
            ) reject("invalid_result")
            val source = sourceMessage(connection, scope, observation.text("sourceId"), now)
            LessonEvidence().validate(observation, source)
            if (connection.document("SELECT to_jsonb(e) FROM lesson_event e WHERE lesson_id=? AND event_id=?", lessonId, source.text("id")) == null) reject("invalid_evidence")
        }
        connection.update(
            """INSERT INTO lesson_evaluation(student_id,lesson_id,source_revision,analyzer_version,result,created_at)
               VALUES (?,?,?,?,?::jsonb,?) ON CONFLICT DO NOTHING""", scope.studentId, lessonId, inputs.number("transcriptRevision"),
            inputs.text("analyzerVersion"), JsonObject(result - "facts" - "candidates" + ("observations" to kotlinx.serialization.json.JsonArray(observations.map { stripEvidenceQuote(it.jsonObject) }))), now,
        )
        observations.forEach { item ->
            val observation = item.jsonObject
            connection.update(
                """INSERT INTO memory_observation(id,student_id,lesson_id,source_revision,analyzer_version,observation_key,
                   dimension,content,uncertainty,source_event_id,created_at,evidence) VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING""",
                newId(), scope.studentId, lessonId, inputs.number("transcriptRevision"), inputs.text("analyzerVersion"), observation.text("key"),
                observation.text("dimension"), observation.text("content"), observation.text("uncertainty"), observation.text("sourceId"), now, stripEvidenceQuote(observation)["evidence"],
            )
        }
        // Personal direct reports only; evaluation never changes the goal or learning self-assessment.
        LessonMemory().apply(connection, scope, lessonId, result, now)
        // Evaluation has no permission to write the goal or learning self-report fields.
        connection.invalidatePreparation(scope.studentId, now)
        if (lesson.text("kind") == "regular" && lesson.text("status") == "completed")
            PostLessonReview().enqueue(connection, scope.studentId, lessonId, now)
        return true
    }

    private fun prepare(connection: Connection, scope: ExecutionScope, lessonId: String, inputs: JsonObject, result: JsonObject, now: Instant): Boolean {
        result.only("objective", "topic", "strategy", "teachingPlan")
        listOf("objective", "topic", "strategy").forEach { if (result.text(it).isBlank() || result.text(it).length > 3000) reject("invalid_result") }
        val lesson = connection.lesson(scope, lessonId)
        if (inputs.optionalText("lessonVersion")?.toLong()?.let { it != lesson.number("version") } == true ||
            inputs.optionalText("roadmapRevision")?.toLong()?.let { it != connection.roadmapRevision(scope.studentId) } == true ||
            lesson.text("status") != "scheduled" || inputs.number("goalRevision") != connection.goalRevision(scope.studentId) ||
            inputs.number("learningRevision") != connection.learningRevision(scope.studentId)) return false
        val roadmap = if (lesson.text("kind") == "regular") RoadmapPreparation().state(connection, scope.studentId) else null
        if (roadmap?.optionalText("status") in setOf("queued", "running", "retry_wait", "failed")) reject("roadmap_not_ready")
        val missing = connection.pendingEvaluations(scope.studentId, lessonId)
        if (missing.isNotEmpty()) reject("analysis_not_ready")
        val revision = connection.rows("SELECT COALESCE(max(revision),0)+1 FROM lesson_plan WHERE lesson_id=?", lessonId) { it.getLong(1) }.single()
        val evaluations = connection.documents(
            """SELECT jsonb_build_object('lessonId',e.lesson_id,'sourceRevision',e.source_revision,'analyzerVersion',e.analyzer_version)
               FROM lesson_evaluation e JOIN lesson l ON l.id=e.lesson_id WHERE e.student_id=? AND e.source_revision=l.transcript_revision""", scope.studentId,
        )
        if (lesson.text("kind") == "regular" && result["teachingPlan"] != null &&
            !prepareTeachingPlan(connection, scope, lesson, result.obj("teachingPlan"), now)) return false
        connection.update("UPDATE lesson_plan SET status='stale' WHERE lesson_id=?", lessonId)
        connection.update(
            """INSERT INTO lesson_plan(student_id,lesson_id,revision,goal_revision,learning_revision,roadmap_revision,evaluation_refs,status,content,created_at)
               VALUES (?,?,?,?,?,?,?::jsonb,'ready',?::jsonb,?)""", scope.studentId, lessonId, revision, inputs.number("goalRevision"), inputs.number("learningRevision"), connection.roadmapRevision(scope.studentId), JsonArray(evaluations), JsonObject(result - "teachingPlan"), now,
        )
        return true
    }

    private fun prepareTeachingPlan(c: Connection, scope: ExecutionScope, lesson: JsonObject,
                                    choice: JsonObject, now: Instant): Boolean {
        choice.only("taskId", "taskRevision", "priorityRevision", "status", "planProposal")
        if (choice.text("status") !in setOf("ready", "template_missing")) reject("invalid_result")
        val priority = c.currentTeachingPriority(scope.studentId) ?: return false
        if (priority.number("revision") != choice.number("priorityRevision") ||
            priority.obj("document").optionalText("nextTaskId") != choice.text("taskId")) return false
        val task = LearningTasks().get(c, scope.studentId, choice.text("taskId"))
        if (task.number("revision") != choice.number("taskRevision") || task.text("lifecycle") == "Mastered") return false
        if (choice.text("status") == "template_missing") {
            if (choice["planProposal"] is JsonObject || task.obj("details")["plan"] is JsonObject) reject("invalid_result")
            return true
        }
        val linked = task.obj("details")["plan"] as? JsonObject
        val proposed = choice["planProposal"] as? JsonObject
        val plan = if (linked == null) {
            if (proposed == null) reject("invalid_result")
            proposed.only("definition", "currentStageId")
            LearningPlans().create(c, scope.copy(role = "operator"), json(
                "taskId" to task.text("id"), "expectedTaskRevision" to task.number("revision"),
                "definition" to proposed.obj("definition"), "currentStageId" to proposed.text("currentStageId"),
                "component" to "sp12b.pedagogical_plan"), now).obj("plan")
        } else {
            if (proposed != null) reject("invalid_result")
            LearningPlans().read(c, scope.studentId, linked.text("id")).obj("plan")
        }
        val state = plan.obj("currentState")
        val stageId = state.optionalText("currentStageId") ?: return true
        val key = listOf(lesson.text("id"), lesson.number("version"), plan.text("id"),
            plan.number("revision"), state.number("stateRevision"), stageId, priority.number("revision")).joinToString(":")
        c.enqueueJob(scope.studentId, "lesson.content.prepare", key, lesson.text("id"),
            json("lessonVersion" to lesson.number("version"), "taskId" to task.text("id"),
                "planId" to plan.text("id"), "planRevision" to plan.number("revision"), "planStateRevision" to state.number("stateRevision"),
                "stageId" to stageId, "priorityRevision" to priority.number("revision")), now)
        return true
    }

    private fun prepareTeachingContent(c: Connection, scope: ExecutionScope, lessonId: String,
                                       inputs: JsonObject, result: JsonObject, now: Instant): Boolean {
        result.only("document")
        val lesson = c.lesson(scope, lessonId)
        if (lesson.text("kind") != "regular" || lesson.text("status") !in setOf("scheduled", "in_progress") ||
            lesson.number("version") != inputs.number("lessonVersion")) return false
        val priority = c.currentTeachingPriority(scope.studentId) ?: return false
        if (priority.number("revision") != inputs.number("priorityRevision") ||
            priority.obj("document").optionalText("nextTaskId") != inputs.text("taskId")) return false
        val task = LearningTasks().get(c, scope.studentId, inputs.text("taskId"))
        val linked = task.obj("details")["plan"] as? JsonObject ?: return false
        if (linked.text("id") != inputs.text("planId") || linked.number("revision") != inputs.number("planRevision")) return false
        val plan = LearningPlans().read(c, scope.studentId, inputs.text("planId")).obj("plan")
        val state = plan.obj("currentState")
        if (state.optionalText("currentStageId") != inputs.text("stageId") ||
            state.number("stateRevision") != inputs.number("planStateRevision")) return false
        val document = result.obj("document")
        val ref = document.obj("stageRef")
        if (document.text("taskId") != inputs.text("taskId") || ref.text("taskId") != inputs.text("taskId") ||
            ref.text("planId") != inputs.text("planId") || ref.number("planRevision") != inputs.number("planRevision") ||
            ref.text("stageId") != inputs.text("stageId") ||
            document.obj("validation").text("component") != "sp13b.content_generation" ||
            document.obj("validation").text("status") != "passed") reject("invalid_result")
        val exists = c.document("""SELECT to_jsonb(h) FROM learning_content h JOIN learning_content_revision v
            ON v.content_id=h.id AND v.revision=h.current_revision WHERE h.student_id=? AND h.task_id=?
            AND v.document->'stageRef'=?::jsonb AND v.document->'validation'->>'status'='passed'
            AND (v.status='prepared' OR v.status='issued' AND v.issued_lesson_id=?) LIMIT 1""",
            scope.studentId, inputs.text("taskId"), ref.toString(), lessonId)
        if (exists == null) LearningContent().create(c, scope.copy(role = "operator"),
            json("document" to document, "component" to "sp13b.content_generation"), now)
        return true
    }
}

internal fun Connection.currentTeachingPriority(student: String): JsonObject? = document(
    """SELECT to_jsonb(p) FROM learning_roadmap_priority p JOIN learning_roadmap r
       ON r.student_id=p.student_id AND r.revision=p.roadmap_revision
       WHERE p.student_id=? AND p.stale_at IS NULL""", student,
)

internal fun Connection.refreshRegularTeachingPreparation(student: String, reason: String, now: Instant) {
    val lessons = rows("""SELECT id FROM lesson WHERE student_id=? AND kind='regular'
        AND status='scheduled' AND launch_mode='executable'""", student) { it.getString(1) }
    lessons.forEach { lesson ->
        update("""UPDATE foundation_job SET status='superseded' WHERE student_id=? AND target_id=?
            AND kind IN ('lesson.prepare','lesson.content.prepare') AND status IN ('queued','running','retry_wait')""", student, lesson)
        enqueueJob(student, "lesson.prepare", "$lesson:teaching:$reason", lesson, planInputs(student, lesson), now)
    }
}

internal fun Connection.pendingEvaluations(student: String, lessonId: String): List<String> = rows(
    """SELECT l.id FROM lesson l JOIN lesson target ON target.id=? WHERE l.student_id=? AND l.id<>target.id
       AND l.status='completed' AND l.starts_at<=target.starts_at
       AND NOT EXISTS (SELECT 1 FROM lesson_evaluation e WHERE e.lesson_id=l.id AND e.source_revision=l.transcript_revision)""",
    lessonId, student,
) { it.getString(1) }

internal fun Connection.preparation(scope: ExecutionScope, lessonId: String): JsonObject {
    val lesson = lesson(scope, lessonId)
    if (lesson.text("launch_mode") == "placeholder") return json("status" to "blocked", "reason" to "placeholder")
    val pending = pendingEvaluations(scope.studentId, lessonId)
    if (pending.isNotEmpty()) {
        val failed = pending.any { id -> document("""SELECT to_jsonb(j) FROM foundation_job j JOIN lesson l ON l.id=j.target_id
            WHERE j.student_id=? AND j.target_id=? AND j.kind='lesson.evaluate' AND j.status='failed'
            AND (j.input_refs->>'transcriptRevision')::bigint=l.transcript_revision LIMIT 1""", scope.studentId, id) != null }
        return json("status" to if (failed) "failed" else "blocked", "reason" to if (failed) "analysis_failed" else "analysis_not_ready",
            "lessons" to JsonArray(pending.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }
    if (lesson.text("kind") == "regular") {
        val roadmap = RoadmapPreparation().state(this, scope.studentId)
        if (roadmap?.optionalText("status") == "failed") return json("status" to "failed", "reason" to "roadmap_failed")
        if (roadmap?.optionalText("status") in setOf("queued", "running", "retry_wait")) return json("status" to "blocked", "reason" to "plan_not_ready")
    }
    val plan = document("SELECT to_jsonb(p) FROM lesson_plan p WHERE lesson_id=? AND status='ready' AND goal_revision=? AND learning_revision=? AND COALESCE(roadmap_revision,0)=? ORDER BY revision DESC LIMIT 1",
        lessonId, goalRevision(scope.studentId), learningRevision(scope.studentId), roadmapRevision(scope.studentId))
    if (plan != null) return json("status" to "ready", "plan" to plan)
    val failed = document("""SELECT to_jsonb(j) FROM foundation_job j WHERE student_id=? AND target_id=?
        AND kind='lesson.prepare' AND status='failed' AND (input_refs->>'goalRevision')::bigint=?
        AND (input_refs->>'learningRevision')::bigint=? AND ((input_refs->>'lessonVersion') IS NULL
        OR (input_refs->>'lessonVersion')::bigint=?) ORDER BY created_at DESC LIMIT 1""",
        scope.studentId, lessonId, goalRevision(scope.studentId), learningRevision(scope.studentId), lesson.number("version"))
    return if (failed != null) json("status" to "failed", "reason" to "plan_failed")
        else json("status" to "blocked", "reason" to "plan_not_ready")
}

private fun stripEvidenceQuote(observation: JsonObject): JsonObject {
    val evidence = observation["evidence"] as? JsonObject ?: return observation
    return JsonObject(observation + ("evidence" to JsonObject(evidence - "quote")))
}
