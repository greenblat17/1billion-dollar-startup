package com.eliteteam.speakingcoach

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.OutputStreamAppender
import ch.qos.logback.core.status.Status
import com.eliteteam.speakingcoach.foundation.FoundationFailure
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import dev.inmo.tgbotapi.bot.exceptions.TooMuchRequestsException
import java.io.IOException
import java.security.MessageDigest
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/** Operational fields only. Untrusted IDs are checked at ingress. */
internal object RuntimeDiagnostics {
    enum class Outcome(val code: String) {
        STARTED("started"), COMPLETED("completed"), REPLAYED("replayed"), SKIPPED("skipped"),
        SUPERSEDED("superseded"), DEFERRED("deferred"), FAILED("failed"), REJECTED("rejected"),
        CANCELLED("cancelled"), UNKNOWN("unknown"), RETRY_WAIT("retry_wait"),
    }

    data class Event(val name: String, val operation: String, val stage: String, val outcome: Outcome,
        val message: String, val reason: String? = null, val coalesce: Boolean = false)

    private data class RepeatWindow(val started: Long, var suppressed: Long = 0)
    private val repeats = LinkedHashMap<List<String?>, RepeatWindow>(128, 0.75f, true)

    private val context = ThreadLocal<Map<String, String>>()
    private val validId = Regex("[A-Za-z0-9_.:-]{1,128}")
    private val opaqueId = Regex("[0-9a-f]{32}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    private val contextKeys = setOf("trace_id", "request_id", "operation_id", "lesson_id", "call_session_id", "notice_id", "job_id", "delivery_id",
        "source_job_id", "source_job_fence", "attempt", "fence", "roadmap_revision")
    private val labelKeys = contextKeys + setOf("event_kind", "operation", "reason_code", "outcome", "provider",
        "provider_code", "upstream_error_id", "job_kind", "job_status", "catalog_version")
    private val numericKeys = setOf("attempt", "duration_ms", "http_status", "fence", "source_job_fence", "part",
        "count", "renewals", "roadmap_revision", "suppressed_count", "audio_marked_for_purge",
        "trial_events_deleted", "conversation_events_cleared", "inbox_responses_cleared",
        "operation_results_cleared", "memory_candidates_expired", "tutor_summaries_deleted",
        "jobs_expired", "execution_scopes_deleted", "learning_revision")
    private val environment = System.getenv("SPEAKY_ENV")?.takeIf { it in setOf("dev", "prod", "local", "test") } ?: "unknown"
    private val release = System.getenv("SPEAKY_RELEASE")?.takeIf { Regex("[a-f0-9]{7,64}").matches(it) } ?: "unknown"
    private val lost = AtomicLong()
    private val internalResponseCodes = setOf("unauthorized", "scope_expired", "not_permitted", "not_owned",
        "invalid_payload", "invalid_job", "source_expired", "provider_timeout", "budget_exhausted",
        "handler_unavailable", "missing_action_template", "provider_unavailable", "internal_error",
        "stale_roadmap", "initial_priority_not_ready", "incomplete_roadmap_order", "storage_unavailable")
    private val errorIds = WeakHashMap<Throwable, String>()
    private val log = LoggerFactory.getLogger("RuntimeDiagnostics")

    init {
        (LoggerFactory.getILoggerFactory() as? LoggerContext)?.statusManager?.add { status ->
            if (status.level == Status.ERROR && status.origin is OutputStreamAppender<*> && status.throwable is IOException) {
                lost.incrementAndGet()
            }
        }
    }

    fun id(value: String?): String? = value?.takeIf(validId::matches)
    fun ingressId(value: String?): String? = value?.takeIf(opaqueId::matches)
    fun trace(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
    fun newId(): String = UUID.randomUUID().toString().replace("-", "")
    fun current(): Map<String, String> = context.get().orEmpty()
    fun elapsed(started: Long): String = ((System.nanoTime() - started) / 1_000_000).toString()

    suspend fun <T> withContext(fields: Map<String, String>, block: suspend () -> T): T =
        withContext(context.asContextElement(current() + fields.filter { (key, value) ->
            key in contextKeys && id(value) != null
        })) { block() }

    fun httpFields(status: Int, body: String, errorId: String? = null): Map<String, String> = buildMap {
        put("http_status", status.toString())
        ingressId(errorId)?.let { put("upstream_error_id", it) }
        if (body.length > 8_192) return@buildMap
        val payload = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return@buildMap
        for (key in listOf("detail", "error", "error_code")) {
            val value = (payload[key] as? JsonPrimitive)?.content ?: continue
            if (value in internalResponseCodes || Regex("[1-5][0-9]{2}").matches(value)) {
                put("provider_code", value)
                break
            }
        }
    }

    private fun failureClass(outcome: String, status: Int?, error: Throwable?, reason: String?): String? = when {
        outcome == "cancelled" -> "cancelled"
        outcome !in setOf("failed", "rejected", "unknown") && error == null -> null
        reason == "internal_error" -> "unexpected"
        status in setOf(408, 429) || (status ?: 0) >= 500 || error is SQLException || error is IOException -> "transient"
        reason in setOf("provider_timeout", "provider_error", "handler_unavailable") -> "transient"
        outcome == "rejected" || (status ?: 0) >= 400 || error is FoundationFailure -> "expected"
        reason != null || error != null -> "unexpected"
        else -> null
    }

    fun fields(event: String, stage: String, error: Throwable?, extra: Map<String, String>): JsonObject {
        val values = current() + extra
        val labels = values.filter { (key, value) -> key in labelKeys && key !in numericKeys && id(value) != null }
        val outcome = labels["outcome"] ?: when {
            error is FoundationFailure -> "rejected"
            error != null || event.endsWith("_failed") -> "failed"
            event.endsWith("_unknown") -> "unknown"
            else -> "completed"
        }
        val reason = labels["reason_code"] ?: (error as? FoundationFailure)?.code ?: when (error) {
            is SQLException -> "storage_unavailable"
            is IOException -> "transport_error"
            null -> if (outcome in setOf("unknown", "failed")) "operation_failed" else null
            else -> "unexpected_error"
        }
        val failed = outcome in setOf("failed", "rejected", "unknown")
        val previous = (error as? FoundationFailure)?.diagnosticErrorId
            ?: error?.let { synchronized(errorIds) { errorIds[it] ?: errorIds[it.cause] } }
        val errorId = previous ?: ingressId(extra["error_id"]) ?: if (failed || error != null) newId() else null
        if (error != null && errorId != null) synchronized(errorIds) { errorIds[error] = errorId }
        val status = extra["http_status"]?.toIntOrNull()?.takeIf { it in 100..599 }

        return buildJsonObject {
            labels.forEach { (key, value) -> put(key, value) }
            for (key in numericKeys) values[key]?.toLongOrNull()?.takeIf { it >= 0 }?.let { put(key, it) }
            put("schema_version", 1)
            put("timestamp", Instant.now().toString())
            put("level", if (failed) "WARNING" else "INFO")
            put("service", "backend")
            put("environment", environment)
            put("release", release)
            put("component", event.substringBefore('_'))
            put("event", event)
            put("operation", labels["operation"] ?: event)
            put("stage", stage)
            put("outcome", outcome)
            put("trace_id", labels["trace_id"] ?: newId())
            put("request_id", labels["request_id"]?.let(::JsonPrimitive) ?: JsonNull)
            put("error_id", errorId?.let(::JsonPrimitive) ?: JsonNull)
            put("duration_ms", extra["duration_ms"]?.toLongOrNull()?.takeIf { it >= 0 }?.let(::JsonPrimitive) ?: JsonNull)
            put("reason_code", reason?.let(::JsonPrimitive) ?: JsonNull)
            val operation = labels["operation"] ?: event
            val why = reason?.let { " Reason: ${it.replace('_', ' ')}." }.orEmpty()
            put("message", "${operation.replace('_', ' ')} at ${stage.replace('_', ' ')}: ${outcome.replace('_', ' ')}.$why".take(280))
            failureClass(outcome, status, error, reason)?.let { put("failure_class", it) }
            if (error != null) {
                put("error_type", error.javaClass.simpleName)
                reason?.let { put("error_code", it) }
                if (previous == null && error !is FoundationFailure) {
                    put("stack", JsonArray(error.stackTrace.take(16).map {
                        JsonPrimitive("${it.className}.${it.methodName}:${it.lineNumber}")
                    }))
                }
                error.cause?.let { put("cause_type", it.javaClass.simpleName) }
                if (error is RequestException) put("provider_code", error.response.errorCode)
                if (error is TooMuchRequestsException) put("retry_after", error.retryAfter.seconds)
                if (error is SQLException) {
                    error.sqlState?.takeIf { Regex("[A-Z0-9]{5}").matches(it) }?.let { put("sql_state", it) }
                    put("vendor_code", error.errorCode)
                }
            }
        }
    }

    fun record(event: Event, extra: Map<String, String> = emptyMap(), error: Throwable? = null): String? {
        val fields = if (event.coalesce) recordWindow(event, extra) ?: return null else extra
        return record(event.name, event.stage, error, fields + mapOf("operation" to event.operation, "outcome" to event.outcome.code) +
            (event.reason?.let { mapOf("reason_code" to it) } ?: emptyMap()), message = event.message)
    }

    private fun recordWindow(event: Event, fields: Map<String, String>): Map<String, String>? = synchronized(repeats) {
        val key = listOf(event.name, event.operation, event.reason, fields["job_id"])
        val now = System.nanoTime()
        val window = repeats[key]
        if (window != null && now - window.started < 60_000_000_000L) {
            window.suppressed++
            return@synchronized null
        }
        if (window == null && repeats.size == 128) repeats.remove(repeats.keys.first())
        repeats[key] = RepeatWindow(now)
        if (window == null || window.suppressed == 0L) fields else fields + ("suppressed_count" to window.suppressed.toString())
    }

    fun record(
        event: String, stage: String, error: Throwable? = null, extra: Map<String, String> = emptyMap(),
        providerMetrics: JsonObject? = null, message: String? = null,
    ): String? {
        try {
            val json = fields(event, stage, error, extra)
            val marker = if (providerMetrics == null) "runtime_event" else "provider_call"
            val overrides = if (message == null) emptyMap() else mapOf("message" to JsonPrimitive(message.take(280)))
            val record = JsonObject(json + providerMetrics?.filter { (key, value) -> safeProviderField(key, value) }.orEmpty() + overrides)
            val recovered = lost.getAndSet(0)
            if (recovered > 0) {
                try {
                    log.warn("{} {}", "runtime_event", fields("diagnostic_records_lost", "write", null,
                        mapOf("count" to recovered.toString(), "outcome" to "failed", "reason_code" to "writer_error")))
                } catch (_: Exception) {
                    lost.addAndGet(recovered)
                }
            }
            if ((json["level"] as? JsonPrimitive)?.content == "WARNING") log.warn("{} {}", marker, record)
            else log.info("{} {}", marker, record)
            return (json["error_id"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
        } catch (_: Exception) {
            // Diagnostics must not change the outcome of a committed operation.
            lost.incrementAndGet()
            return ingressId(extra["error_id"])
        }
    }

    private fun safeProviderField(key: String, value: kotlinx.serialization.json.JsonElement): Boolean {
        val primitive = value as? JsonPrimitive ?: return false
        return when (key) {
            "source", "provider" -> primitive.content == "telegram"
            "type" -> primitive.content == "telegram_api"
            "purpose" -> primitive.isString && Regex("[A-Za-z][A-Za-z0-9]{0,63}").matches(primitive.content)
            "result" -> primitive.content in setOf("success", "error", "cancelled")
            "user_id" -> primitive == JsonNull || Regex("[0-9a-f]{20}").matches(primitive.content)
            "http_status" -> primitive == JsonNull || primitive.intOrNull?.let { it in 100..599 } == true
            "cost_micro_usd", "model" -> primitive == JsonNull
            "error_code" -> primitive == JsonNull || Regex("telegram_[1-5][0-9]{2}|[A-Za-z][A-Za-z0-9]*Exception").matches(primitive.content)
            else -> false
        }
    }
}
