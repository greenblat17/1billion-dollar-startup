package com.eliteteam.speakingcoach

import java.security.MessageDigest
import java.sql.SQLException
import java.util.UUID
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import dev.inmo.tgbotapi.bot.exceptions.TooMuchRequestsException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/** Operational fields only: no payloads, auth scopes, SQL values or throwable messages. */
internal object RuntimeDiagnostics {
    private val context = ThreadLocal<Map<String, String>>()
    private val validId = Regex("[A-Za-z0-9_.:-]{1,128}")
    private val log = LoggerFactory.getLogger("RuntimeDiagnostics")

    fun id(value: String?): String? = value?.takeIf { validId.matches(it) }
    fun trace(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    fun current(): Map<String, String> = context.get().orEmpty()

    suspend fun <T> withContext(fields: Map<String, String>, block: suspend () -> T): T =
        withContext(context.asContextElement(current() + fields.filterValues { id(it) != null })) { block() }

    fun cleanMessage(raw: String): String {
        var value = raw.take(4096)
        System.getenv().forEach { (key, secret) ->
            if (listOf("TOKEN", "SECRET", "PASSWORD", "API_KEY").any { key.uppercase().contains(it) } && secret.length >= 6) {
                value = value.replace(secret, "[redacted]")
            }
        }
        value = value.replace(Regex("https?://\\S+"), "[url]")
        value = value.replace(Regex("(?i)bearer\\s+\\S+|sk-[\\w-]+|\\b\\d{6,}:[\\w-]+"), "[redacted]")
        value = value.replace(Regex("[\"'][^\"'\\n]*[\"']"), "[quoted]")
        value = value.replace(Regex("(?i)(token|secret|password|scope|initData|api[_-]?key)\\s*[:=]\\s*\\S+"), "[redacted]")
        return value.replace(Regex("\\s+"), " ").take(1000)
    }

    fun httpFields(status: Int, body: String): Map<String, String> {
        val fields = mutableMapOf("http_status" to status.toString())
        val payload = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return fields
        for (key in listOf("detail", "error", "error_code", "description")) {
            val value = payload[key] as? JsonPrimitive ?: continue
            if (key == "description") fields["provider_message"] = cleanMessage(value.content)
            else id(value.content)?.let { fields["provider_code"] = it }
        }
        return fields
    }

    fun fields(event: String, stage: String, error: Throwable?, extra: Map<String, String>) = buildJsonObject {
        put("service", "backend")
        put("event", event)
        put("stage", stage)
        (current() + extra).forEach { (key, value) ->
            if (key == "provider_message") put(key, cleanMessage(value)) else id(value)?.let { put(key, it) }
        }
        if (error != null) {
            put("error_id", UUID.randomUUID().toString())
            put("error_type", error.javaClass.simpleName)
            put("stack", JsonArray(error.stackTrace.take(16).map {
                JsonPrimitive("${it.className}.${it.methodName}:${it.lineNumber}")
            }))
            error.cause?.let { put("cause_type", it.javaClass.simpleName) }
            if (error is RequestException) {
                put("provider_code", error.response.errorCode)
                error.response.description?.let { put("provider_message", cleanMessage(it)) }
            }
            if (error is TooMuchRequestsException) put("retry_after", error.retryAfter.seconds)
            if (error is SQLException) {
                id(error.sqlState)?.let { put("sql_state", it) }
                put("vendor_code", error.errorCode)
            }
            if (error is com.eliteteam.speakingcoach.foundation.FoundationFailure) {
                id(error.code)?.let { put("error_code", it) }
            }
        }
    }

    fun record(event: String, stage: String, error: Throwable? = null, extra: Map<String, String> = emptyMap()) {
        try {
            val json = fields(event, stage, error, extra)
            if (error == null) log.info("runtime_event {}", json) else log.warn("runtime_event {}", json)
        } catch (_: Exception) {
            // Diagnostics must not change the outcome of a committed operation.
        }
    }
}
