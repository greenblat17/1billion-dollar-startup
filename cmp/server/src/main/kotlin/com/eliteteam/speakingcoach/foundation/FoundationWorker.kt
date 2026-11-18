package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.RuntimeDiagnostics

import io.ktor.client.plugins.timeout
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal interface LearningComputer {
    suspend fun compute(scope: String, job: JsonObject): JsonObject
}

internal class HttpLearningComputer(private val http: HttpClient, private val root: String, private val serviceToken: String) : LearningComputer {
    override suspend fun compute(scope: String, job: JsonObject): JsonObject {
        val started = System.nanoTime()
        val response = http.post("${root.trimEnd('/')}/internal/foundation/compute") {
            timeout { requestTimeoutMillis = 140_000; socketTimeoutMillis = 140_000 }
            header("X-Internal-Token", serviceToken)
            header("X-Diagnostic-Trace", RuntimeDiagnostics.trace(job.text("id")))
            RuntimeDiagnostics.current()["job_id"]?.let { header("X-Diagnostic-Job", it) }
            RuntimeDiagnostics.current()["lesson_id"]?.let { header("X-Diagnostic-Lesson", it) }
            header("X-Execution-Scope", scope)
            contentType(ContentType.Application.Json)
            setBody(job.toString())
        }
        RuntimeDiagnostics.record("foundation_job_ai_call", "response", extra = mapOf(
            "operation" to job.text("kind"), "provider" to "ai",
            "http_status" to response.status.value.toString(), "duration_ms" to RuntimeDiagnostics.elapsed(started),
            "outcome" to if (response.status.value in 200..299) "completed" else "failed"
        ) + (RuntimeDiagnostics.ingressId(response.headers["X-Error-ID"])
            ?.let { mapOf("upstream_error_id" to it) } ?: emptyMap()))
        if (response.status.value == 402) reject("budget_exhausted")
        if (response.status.value == 410) reject("source_expired")
        if (response.status.value == 400) reject("invalid_result")
        if (response.status.value == 422) {
            val detail = runCatching { foundationJson.parseToJsonElement(response.bodyAsText()).jsonObject.optionalText("detail") }.getOrNull()
            if (detail == "missing_action_template") reject("missing_action_template")
            reject("invalid_result")
        }
        if (response.status.value == 503) {
            val detail = runCatching { foundationJson.parseToJsonElement(response.bodyAsText()).jsonObject.optionalText("detail") }.getOrNull()
            reject(if (detail in setOf("handler_unavailable", "provider_timeout")) detail!! else "provider_error")
        }
        if (response.status.value !in 200..299) reject("provider_error")
        return foundationJson.parseToJsonElement(response.bodyAsText()).jsonObject
    }
}

/** One bounded task at a time; leases permit multiple independently running processes. */
internal class FoundationWorker(
    private val service: FoundationService,
    private val computer: LearningComputer,
    private val businessHandlers: Map<String, LearningComputer> = emptyMap(),
) {
    fun launch(scope: CoroutineScope) = scope.launch {
        var nextSweep = java.time.Instant.MIN
        while (isActive) {
            try {
                if (service.database.clock.instant() >= nextSweep) {
                    scheduleRetention()
                    nextSweep = service.database.clock.instant().plusSeconds(3600)
                }
                if (!runOnce()) delay(1000)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                RuntimeDiagnostics.record("foundation_worker_unavailable", "worker", error)
                delay(1000)
            }
        }
    }

    private suspend fun scheduleRetention() {
        service.database.transaction { connection, now ->
            val bucket = now.epochSecond / 3600
            connection.update("""INSERT INTO foundation_job(id,student_id,data_epoch,kind,dedup_key,input_refs,due_at,deadline)
                SELECT 'retention:' || id || ':' || ?,id,data_epoch,'retention.prune',?,'{}'::jsonb,?,?
                FROM student WHERE deleted_at IS NULL ON CONFLICT(student_id,kind,dedup_key) DO NOTHING""",
                bucket.toString(), bucket.toString(), now, now.plusSeconds(86400))
        }
    }

    suspend fun runOnce(): Boolean {
        val candidates = service.database.transaction(readOnly = true) { connection, now ->
            connection.rows("SELECT DISTINCT student_id FROM foundation_job WHERE status IN ('queued','retry_wait','running') AND due_at<=? AND deadline>? LIMIT 20", now, now) { it.getString(1) }
        }
        for (student in candidates) {
            val operator = service.identity.issue(student, "operator")
            val claimed = service.command(operator, json("version" to 1, "operationId" to newId(), "action" to "job.claim", "args" to json()))
            val job = claimed["job"]?.takeUnless { it == kotlinx.serialization.json.JsonNull }?.jsonObject ?: continue
            val jobId = job.text("id")
            val context = mutableMapOf("trace_id" to RuntimeDiagnostics.trace(jobId),
                "job_id" to (RuntimeDiagnostics.ingressId(jobId) ?: RuntimeDiagnostics.trace(jobId)))
            if (job.text("kind").startsWith("lesson.") || job.text("kind").startsWith("trial.") ||
                job.text("kind") == "roadmap.generate") {
                RuntimeDiagnostics.ingressId(job.optionalText("target_id"))?.let { context["lesson_id"] = it }
            }
            RuntimeDiagnostics.withContext(context) { runJob(job) }
            return true
        }
        return false
    }

    private suspend fun runJob(job: JsonObject) {
        val fields = mapOf("operation" to job.text("kind"), "job_kind" to job.text("kind"),
            "attempt" to job.number("attempts").toString(), "fence" to job.number("fence").toString())
        RuntimeDiagnostics.record("foundation_job", "claim", extra = fields + ("job_status" to "running"))
        val execution = service.identity.issueJob(job)
        val started = System.nanoTime()
        var stage = "compute"
        try {
            val result = if (job.text("kind") == "retention.prune") json()
                else kotlinx.coroutines.withTimeout(150_000) { computeWithRenewal(execution, job, fields) }
            stage = "persist"
            val receipt = service.command(execution, json("version" to 1,
                "operationId" to "job:${job.text("id")}:${job.number("fence")}:apply",
                "action" to "job.apply", "args" to json("jobId" to job.text("id"), "fence" to job.number("fence"), "result" to result)))
            RuntimeDiagnostics.record("foundation_job", stage, extra = fields + mapOf(
                "job_status" to receipt.text("status"), "duration_ms" to RuntimeDiagnostics.elapsed(started)))
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            RuntimeDiagnostics.record("foundation_job_failed", stage, extra = fields +
                mapOf("reason_code" to "provider_timeout", "duration_ms" to RuntimeDiagnostics.elapsed(started)))
            fail(execution, job, "provider_timeout", fields)
        } catch (error: CancellationException) {
            RuntimeDiagnostics.record("foundation_job", stage, extra = fields + ("outcome" to "cancelled"))
            throw error // Leave the durable lease for recovery by another worker.
        } catch (error: Exception) {
            val failure = (error as? FoundationFailure)?.code
            val code = when (failure) {
                "budget_exhausted", "handler_unavailable", "source_expired", "invalid_result", "provider_timeout", "missing_action_template" -> failure
                "invalid_evidence", "insufficient_evidence", "confirmation_required", "invalid_steps", "invalid_reason" -> "invalid_result"
                else -> "provider_error"
            }
            RuntimeDiagnostics.record("foundation_job_failed", stage, error, fields + mapOf(
                "outcome" to "failed", "reason_code" to when {
                    error is java.sql.SQLException -> "storage_unavailable"
                    stage == "persist" && error !is FoundationFailure -> "internal_error"
                    else -> code
                },
                "duration_ms" to RuntimeDiagnostics.elapsed(started)))
            fail(execution, job, code, fields)
        }
    }

    private suspend fun computeWithRenewal(execution: String, job: JsonObject, fields: Map<String, String>): JsonObject = kotlinx.coroutines.coroutineScope {
        val renewal = launch {
            while (isActive) {
                delay(20_000)
                service.command(execution, json("version" to 1, "operationId" to newId(), "action" to "job.renew",
                    "args" to json("jobId" to job.text("id"), "fence" to job.number("fence"))))
                RuntimeDiagnostics.record("foundation_job", "renew", extra = fields + ("job_status" to "running"))
            }
        }
        try {
            val handler = if (job.text("kind") in setOf("schedule.materialize", "notification.reminder"))
                businessHandlers[job.text("kind")] ?: reject("handler_unavailable") else computer
            handler.compute(execution, job)
        } finally {
            renewal.cancel()
        }
    }

    private suspend fun fail(scope: String, job: JsonObject, code: String, fields: Map<String, String>) {
        try {
            val receipt = service.command(scope, json("version" to 1, "operationId" to newId(), "action" to "job.fail",
                "args" to json("jobId" to job.text("id"), "fence" to job.number("fence"), "code" to code)))
            RuntimeDiagnostics.record("foundation_job", "failure_persisted", extra = fields + mapOf(
                "job_status" to receipt.text("status"), "reason_code" to code))
        } catch (error: FoundationFailure) {
            RuntimeDiagnostics.record("foundation_job", "failure_persist", error, fields + ("outcome" to "rejected"))
        }
    }
}
