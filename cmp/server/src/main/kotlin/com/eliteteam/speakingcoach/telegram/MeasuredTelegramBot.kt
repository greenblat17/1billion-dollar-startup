package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.TelegramOperationalMetrics
import com.eliteteam.speakingcoach.RuntimeDiagnostics
import dev.inmo.tgbotapi.abstracts.types.ChatRequest
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import dev.inmo.tgbotapi.requests.abstracts.MultipartRequest
import dev.inmo.tgbotapi.requests.abstracts.Request
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One log line and one counter increment per outbound Telegram Bot API call. */
internal class MeasuredTelegramBot(
    private val delegate: TelegramBot,
    private val metrics: TelegramOperationalMetrics,
    private val userIdHashKey: String,
) : TelegramBot by delegate {
    override suspend fun <T : Any> execute(request: Request<T>): T {
        val started = System.nanoTime()
        try {
            val response = delegate.execute(request)
            record(request, "success", null, started)
            return response
        } catch (error: Throwable) {
            record(request, if (error is CancellationException) "cancelled" else "error", error, started)
            throw error
        }
    }

    private fun record(request: Request<*>, result: String, error: Throwable?, started: Long) {
        metrics.recordApiCall(result)
        try {
            val chatId = (request as? ChatRequest)?.chatId?.let(::telegramChatNumber)
                ?: (request as? MultipartRequest<*>)?.paramsJson?.get("chat_id")?.jsonPrimitive?.longOrNull
            val userId = if (chatId == null || userIdHashKey.isBlank()) null else telegramApiUserId(chatId, userIdHashKey)
            val event = buildJsonObject {
                put("source", "telegram")
                put("user_id", userId?.let(::JsonPrimitive) ?: JsonNull)
                put("type", "telegram_api")
                put("purpose", request.method())
                put("provider", "telegram")
                put("model", JsonNull)
                put("http_status", (error as? RequestException)?.response?.errorCode?.let(::JsonPrimitive) ?: JsonNull)
                put("result", result)
                val telegramCode = (error as? RequestException)?.response?.errorCode
                val errorCode = telegramCode?.let { "telegram_$it" } ?: error?.javaClass?.simpleName
                put("error_code", errorCode?.let(::JsonPrimitive) ?: JsonNull)
                put("cost_micro_usd", JsonNull)
            }
            val status = (error as? RequestException)?.response?.errorCode
            val extra = mapOf("operation" to "telegram_api", "provider" to "telegram",
                "duration_ms" to RuntimeDiagnostics.elapsed(started),
                "outcome" to when (result) {
                    "success" -> "completed"
                    "cancelled" -> "cancelled"
                    else -> "failed"
                }) + (status?.let { mapOf("http_status" to it.toString()) } ?: emptyMap())
            RuntimeDiagnostics.record(if (result == "error") "provider_failed" else "provider_call",
                if (error != null && status == null) "transport" else "response",
                error.takeUnless { it is CancellationException }, extra, event)
        } catch (loggingError: Exception) {
            RuntimeDiagnostics.record("provider_log_failed", "serialize", loggingError)
        }
    }

}

internal fun telegramApiUserId(chatId: Long, key: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal("telegram:$chatId".toByteArray(Charsets.UTF_8))
        .take(10).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
