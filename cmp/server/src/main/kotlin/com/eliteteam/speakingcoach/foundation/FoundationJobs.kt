package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal val jobKinds = setOf("memory.extract", "lesson.evaluate", "lesson.prepare", "schedule.materialize", "notification.reminder", "retention.prune")

internal fun Connection.enqueueJob(student: String, kind: String, key: String, target: String?, inputs: JsonObject, now: Instant): String {
    if (kind !in jobKinds) reject("unknown_job_kind")
    val id = newId()
    update(
        """INSERT INTO foundation_job(id,student_id,data_epoch,kind,dedup_key,target_id,input_refs,due_at,deadline)
           SELECT ?,id,data_epoch,?,?,?,?::jsonb,?,? FROM student WHERE id=? AND deleted_at IS NULL
           ON CONFLICT(student_id,kind,dedup_key) DO NOTHING""", id, kind, key, target, inputs, now, now.plusSeconds(HISTORY_SECONDS), student,
    )
    return rows("SELECT id FROM foundation_job WHERE student_id=? AND kind=? AND dedup_key=?", student, kind, key) { it.getString(1) }.single()
}

internal class FoundationJobs {
    fun claim(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator")
        args.only("kind")
        val kind = args.optionalText("kind")
        if (kind != null && kind !in jobKinds) reject("unknown_job_kind")
        connection.update("UPDATE foundation_job SET status='failed',error_code='source_expired' WHERE student_id=? AND deadline<=? AND status IN ('queued','running','retry_wait')", scope.studentId, now)
        connection.update("UPDATE foundation_job SET status='failed',error_code='retry_exhausted' WHERE student_id=? AND status='running' AND lease_until<=? AND attempts>=max_attempts", scope.studentId, now)
        val selected = connection.document(
            """SELECT to_jsonb(j) FROM foundation_job j WHERE student_id=? AND data_epoch=? AND deadline>?
               AND (?::text IS NULL OR kind=?) AND attempts<max_attempts AND ((status IN ('queued','retry_wait') AND due_at<=?) OR (status='running' AND lease_until<=?))
               ORDER BY due_at,id LIMIT 1 FOR UPDATE SKIP LOCKED""", scope.studentId, scope.epoch, now, kind, kind, now, now,
        ) ?: return json("job" to null)
        val id = selected.text("id")
        if (!BackgroundContracts().current(connection, selected)) {
            connection.update("UPDATE foundation_job SET status='superseded' WHERE id=?", id)
            return json("job" to null)
        }
        if (selected.text("kind") == "notification.reminder") {
            val begun = connection.update("INSERT INTO reminder_delivery(job_id,student_id,lesson_id,lesson_version,outcome,attempted_at) VALUES (?,?,?,?,'unknown',?) ON CONFLICT(job_id) DO UPDATE SET outcome='unknown',attempted_at=EXCLUDED.attempted_at WHERE reminder_delivery.outcome='not_sent'",
                id, scope.studentId, selected.text("target_id"), selected.obj("input_refs").number("lessonVersion"), now)
            if (begun == 0) {
                connection.update("UPDATE foundation_job SET status='failed',error_code='delivery_unknown' WHERE id=?", id)
                return json("job" to null)
            }
        }
        connection.update("UPDATE foundation_job SET status='running',attempts=attempts+1,fence=fence+1,lease_until=?,error_code=NULL WHERE id=?", now.plusSeconds(60), id)
        val job = connection.document("SELECT to_jsonb(j) FROM foundation_job j WHERE id=?", id) ?: reject("not_owned")
        connection.update("INSERT INTO foundation_job_attempt(job_id,fence,started_at) VALUES (?,?,?)", id, job.number("fence"), now)
        return json("job" to job)
    }

    fun fail(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        args.only("jobId", "fence", "code")
        val job = ownedRunning(connection, scope, args, now)
        val code = args.text("code")
        if (code !in setOf("provider_timeout", "provider_error", "budget_exhausted", "handler_unavailable", "invalid_result", "source_expired")) reject("invalid_error_code")
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
            "lesson.evaluate" -> evaluate(connection, scope, target ?: reject("invalid_target"), inputs, result, now)
            "lesson.prepare" -> prepare(connection, scope, target ?: reject("invalid_target"), inputs, result, now)
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
        connection.update("UPDATE foundation_job SET status=?,lease_until=NULL WHERE id=?", status, job.text("id"))
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

    private fun evaluate(connection: Connection, scope: ExecutionScope, lessonId: String, inputs: JsonObject, result: JsonObject, now: Instant): Boolean {
        result.only("status", "observations")
        if (result.text("status") !in setOf("evaluated", "insufficient_evidence")) reject("invalid_result")
        val lesson = connection.lesson(scope, lessonId)
        if (lesson.number("transcript_revision") != inputs.number("transcriptRevision")) return false
        val observations = result["observations"]?.jsonArray ?: reject("invalid_result")
        if (observations.size > 30) reject("invalid_result")
        observations.forEach { item ->
            val observation = item.jsonObject
            observation.only("key", "dimension", "content", "uncertainty", "sourceId")
            if (observation.text("dimension") !in setOf("fluency", "confidence", "autonomy", "grammar", "vocabulary") ||
                observation.text("uncertainty") !in setOf("limited", "tentative", "supported") || observation.text("content").length > 1000
            ) reject("invalid_result")
            val source = sourceMessage(connection, scope, observation.text("sourceId"), now)
            if (connection.document("SELECT to_jsonb(e) FROM lesson_event e WHERE lesson_id=? AND event_id=?", lessonId, source.text("id")) == null) reject("invalid_evidence")
        }
        connection.update(
            """INSERT INTO lesson_evaluation(student_id,lesson_id,source_revision,analyzer_version,result,created_at)
               VALUES (?,?,?,?,?::jsonb,?) ON CONFLICT DO NOTHING""", scope.studentId, lessonId, inputs.number("transcriptRevision"),
            inputs.text("analyzerVersion"), result, now,
        )
        observations.forEach { item ->
            val observation = item.jsonObject
            connection.update(
                """INSERT INTO memory_observation(id,student_id,lesson_id,source_revision,analyzer_version,observation_key,
                   dimension,content,uncertainty,source_event_id,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING""",
                newId(), scope.studentId, lessonId, inputs.number("transcriptRevision"), inputs.text("analyzerVersion"), observation.text("key"),
                observation.text("dimension"), observation.text("content"), observation.text("uncertainty"), observation.text("sourceId"), now,
            )
        }
        // Evaluation has no permission to write the goal, profile or self-report fields.
        connection.invalidatePreparation(scope.studentId, now)
        return true
    }

    private fun prepare(connection: Connection, scope: ExecutionScope, lessonId: String, inputs: JsonObject, result: JsonObject, now: Instant): Boolean {
        result.only("objective", "topic", "strategy")
        listOf("objective", "topic", "strategy").forEach { if (result.text(it).isBlank() || result.text(it).length > 3000) reject("invalid_result") }
        val lesson = connection.lesson(scope, lessonId)
        if (lesson.text("status") != "scheduled" || inputs.number("goalRevision") != connection.goalRevision(scope.studentId) ||
            inputs.number("learningRevision") != connection.learningRevision(scope.studentId)) return false
        val missing = connection.pendingEvaluations(scope.studentId, lessonId)
        if (missing.isNotEmpty()) reject("analysis_not_ready")
        val revision = connection.rows("SELECT COALESCE(max(revision),0)+1 FROM lesson_plan WHERE lesson_id=?", lessonId) { it.getLong(1) }.single()
        val evaluations = connection.documents(
            """SELECT jsonb_build_object('lessonId',e.lesson_id,'sourceRevision',e.source_revision,'analyzerVersion',e.analyzer_version)
               FROM lesson_evaluation e JOIN lesson l ON l.id=e.lesson_id WHERE e.student_id=? AND e.source_revision=l.transcript_revision""", scope.studentId,
        )
        connection.update("UPDATE lesson_plan SET status='stale' WHERE lesson_id=?", lessonId)
        connection.update(
            """INSERT INTO lesson_plan(student_id,lesson_id,revision,goal_revision,learning_revision,evaluation_refs,status,content,created_at)
               VALUES (?,?,?,?,?,?::jsonb,'ready',?::jsonb,?)""", scope.studentId, lessonId, revision, inputs.number("goalRevision"), inputs.number("learningRevision"), JsonArray(evaluations), result, now,
        )
        return true
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
    if (pending.isNotEmpty()) return json("status" to "blocked", "reason" to "analysis_not_ready", "lessons" to JsonArray(pending.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    val plan = document("SELECT to_jsonb(p) FROM lesson_plan p WHERE lesson_id=? AND status='ready' AND goal_revision=? AND learning_revision=? ORDER BY revision DESC LIMIT 1",
        lessonId, goalRevision(scope.studentId), learningRevision(scope.studentId))
    return if (plan != null) json("status" to "ready", "plan" to plan) else json("status" to "blocked", "reason" to "plan_not_ready")
}
