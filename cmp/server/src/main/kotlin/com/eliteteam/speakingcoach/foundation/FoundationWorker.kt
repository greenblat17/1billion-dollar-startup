package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.RuntimeDiagnostics
import com.eliteteam.speakingcoach.RuntimeDiagnostics.Event
import com.eliteteam.speakingcoach.RuntimeDiagnostics.Outcome

import io.ktor.client.plugins.timeout
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.util.concurrent.atomic.AtomicInteger
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

private class LeaseRenewalFailure(cause: Exception) : RuntimeException(cause)

internal class HttpLearningComputer(private val http: HttpClient, private val root: String, private val serviceToken: String) : LearningComputer {
    override suspend fun compute(scope: String, job: JsonObject): JsonObject {
        val started = System.nanoTime()
        val response = http.post("${root.trimEnd('/')}/internal/foundation/compute") {
            timeout { requestTimeoutMillis = 140_000; socketTimeoutMillis = 140_000 }
            header("X-Internal-Token", serviceToken)
            header("X-Diagnostic-Trace", RuntimeDiagnostics.trace(job.text("id")))
            RuntimeDiagnostics.current()["job_id"]?.let { header("X-Diagnostic-Job", it) }
            RuntimeDiagnostics.current()["lesson_id"]?.let { header("X-Diagnostic-Lesson", it) }
            header("X-Diagnostic-Fence", job.number("fence").toString())
            header("X-Diagnostic-Attempt", job.number("attempts").toString())
            header("X-Execution-Scope", scope)
            contentType(ContentType.Application.Json)
            setBody(job.toString())
        }
        val status = response.status.value
        val upstreamId = RuntimeDiagnostics.ingressId(response.headers["X-Error-ID"])
        val linked = if (upstreamId == null) emptyMap()
            else mapOf("upstream_error_id" to upstreamId, "error_id" to upstreamId)
        val errorId = RuntimeDiagnostics.record("foundation_job_ai_call", "response", extra = mapOf(
            "operation" to job.text("kind"), "provider" to "ai",
            "http_status" to status.toString(), "duration_ms" to RuntimeDiagnostics.elapsed(started),
            "outcome" to if (status in 200..299) "completed" else "failed"
        ) + linked)
        if (status in 200..299) return foundationJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val detail = if (status == 422 || status == 503) {
            runCatching { foundationJson.parseToJsonElement(response.bodyAsText()).jsonObject.optionalText("detail") }.getOrNull()
        } else null
        val code = when (status) {
            400 -> "invalid_result"
            402 -> "budget_exhausted"
            410 -> "source_expired"
            422 -> if (detail == "missing_action_template") detail else "invalid_result"
            503 -> when (detail) {
                "handler_unavailable", "provider_timeout" -> detail
                else -> "provider_error"
            }
            else -> "provider_error"
        }
        throw FoundationFailure(code, upstreamId ?: errorId)
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
                "job_id" to (RuntimeDiagnostics.ingressId(jobId) ?: RuntimeDiagnostics.trace(jobId)),
                "attempt" to job.number("attempts").toString(), "fence" to job.number("fence").toString())
            RuntimeDiagnostics.ingressId(job.optionalText("source_job_id"))?.let { context["source_job_id"] = it }
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
        val kind = job.text("kind")
        val retention = kind == "retention.prune"
        val fields = mapOf("operation" to kind, "job_kind" to kind)
        if (!retention) RuntimeDiagnostics.record(Event("foundation_job", kind, "claim", Outcome.STARTED,
            "Worker claimed a $kind job attempt."), fields + ("job_status" to "running"))
        val execution = service.identity.issueJob(job)
        val started = System.nanoTime()
        val renewals = AtomicInteger()
        fun attemptFields() = fields + mapOf("duration_ms" to RuntimeDiagnostics.elapsed(started), "renewals" to renewals.toString())
        var stage = "compute"
        try {
            val result = if (retention) json()
                else kotlinx.coroutines.withTimeout(150_000) { computeWithRenewal(execution, job, renewals) }
            stage = "persist"
            val receipt = service.command(execution, json("version" to 1,
                "operationId" to "job:${job.text("id")}:${job.number("fence")}:apply",
                "action" to "job.apply", "args" to json("jobId" to job.text("id"), "fence" to job.number("fence"), "result" to result)))
            val status = receipt.text("status")
            if (retention && status == "succeeded") return
            val event = if (status == "superseded") Event("foundation_job", kind, stage, Outcome.SUPERSEDED,
                "Foundation kept its current state because the $kind result was superseded.", "result_superseded")
            else Event("foundation_job", kind, stage, Outcome.COMPLETED, "Foundation saved the $kind job result.")
            RuntimeDiagnostics.record(event, attemptFields() + ("job_status" to status))
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            val errorId = RuntimeDiagnostics.record(Event("foundation_job_failed", kind, stage, Outcome.FAILED,
                "$kind compute exceeded the worker timeout.", "provider_timeout"), attemptFields())
            fail(execution, job, "provider_timeout", fields, errorId)
        } catch (error: CancellationException) {
            RuntimeDiagnostics.record(Event("foundation_job", kind, stage, Outcome.CANCELLED,
                "Worker cancelled the $kind attempt. Its lease remains available for recovery.", "worker_cancelled"), attemptFields())
            throw error // Leave the durable lease for recovery by another worker.
        } catch (error: Exception) {
            val cause = if (error is LeaseRenewalFailure) error.cause else error
            val failure = (cause as? FoundationFailure)?.code
            val code = when (failure) {
                "budget_exhausted", "handler_unavailable", "source_expired", "invalid_result", "provider_timeout", "missing_action_template" -> failure
                "invalid_evidence", "insufficient_evidence", "confirmation_required", "invalid_steps", "invalid_reason" -> "invalid_result"
                else -> "provider_error"
            }
            val failedStage = if (error is LeaseRenewalFailure) "renew" else stage
            val errorId = RuntimeDiagnostics.record("foundation_job_failed", failedStage, error, attemptFields() + mapOf(
                "outcome" to "failed", "reason_code" to when {
                    error is LeaseRenewalFailure && failure == "stale_job" -> "lease_lost"
                    error is LeaseRenewalFailure -> "lease_renewal_failed"
                    error is java.sql.SQLException -> "storage_unavailable"
                    stage == "persist" && error !is FoundationFailure -> "internal_error"
                    else -> code
                }))
            fail(execution, job, code, fields, errorId)
        }
    }

    private suspend fun computeWithRenewal(execution: String, job: JsonObject, renewals: AtomicInteger): JsonObject = kotlinx.coroutines.coroutineScope {
        val renewal = launch {
            while (isActive) {
                delay(20_000)
                try {
                    service.command(execution, json("version" to 1, "operationId" to newId(), "action" to "job.renew",
                        "args" to json("jobId" to job.text("id"), "fence" to job.number("fence"))))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    throw LeaseRenewalFailure(error)
                }
                renewals.incrementAndGet()
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

    private suspend fun fail(scope: String, job: JsonObject, code: String, fields: Map<String, String>, errorId: String?) {
        val linkedFields = if (errorId == null) fields else fields + ("upstream_error_id" to errorId)
        try {
            val receipt = service.command(scope, json("version" to 1, "operationId" to newId(), "action" to "job.fail",
                "args" to json("jobId" to job.text("id"), "fence" to job.number("fence"), "code" to code)))
            val status = receipt.text("status")
            val retry = status == "retry_wait"
            val message = if (retry) "Foundation saved the failure and scheduled another attempt."
                else "Foundation saved the terminal job failure."
            val decision = linkedFields + ("job_status" to status)
            RuntimeDiagnostics.record(Event("foundation_job", job.text("kind"), "failure_persisted",
                if (retry) Outcome.RETRY_WAIT else Outcome.FAILED, message, code),
                if (errorId == null || retry) decision else decision + ("error_id" to errorId))
        } catch (error: FoundationFailure) {
            RuntimeDiagnostics.record("foundation_job", "failure_persist", error, linkedFields + ("outcome" to "rejected"))
        }
    }
}
