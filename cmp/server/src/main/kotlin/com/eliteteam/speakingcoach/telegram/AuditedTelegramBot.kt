package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.analytics.InteractionAudit
import com.eliteteam.speakingcoach.analytics.InteractionEvent
import com.eliteteam.speakingcoach.analytics.voiceAttemptId
import dev.inmo.tgbotapi.abstracts.types.ChatRequest
import dev.inmo.tgbotapi.abstracts.TextedOutput
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.requests.abstracts.MultipartRequest
import dev.inmo.tgbotapi.requests.abstracts.Request
import dev.inmo.tgbotapi.types.message.abstracts.ChatMessage
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/** Captures sends from handlers, reminders, and campaigns through one Telegram API boundary. */
internal class AuditedTelegramBot(
    private val delegate: TelegramBot,
    private val audit: InteractionAudit,
) : TelegramBot by delegate {
    private val log = LoggerFactory.getLogger(AuditedTelegramBot::class.java)

    override suspend fun <T : Any> execute(request: Request<T>): T {
        val method = request.method()
        if (!method.startsWith("send") && !method.startsWith("edit") &&
            method != "deleteMessage" && method != "setMessageReaction") return delegate.execute(request)
        if (method == "sendChatAction") return delegate.execute(request)

        val chatId = (request as? ChatRequest)?.chatId?.let(::telegramChatNumber)
            ?: (request as? MultipartRequest<*>)?.paramsJson?.get("chat_id")?.jsonPrimitive?.longOrNull
        val requestId = MDC.get("request")?.removePrefix("message:")?.toLongOrNull()
        val attemptId = if (chatId != null && requestId != null) voiceAttemptId(chatId, requestId) else null
        val content = when (request) {
            is TextedOutput -> request.text
            is MultipartRequest<*> -> request.paramsJson["caption"]?.jsonPrimitive?.content
            else -> null
        }
        val eventId = UUID.randomUUID().toString()
        val startedAt = Instant.now()
        val receivedAt = if (chatId != null && requestId != null) {
            try { audit.receiptTime(chatId, requestId) ?: startedAt }
            catch (error: Throwable) { log.warn("Could not read audit receipt time", error); startedAt }
        } else startedAt
        safeRecord(InteractionEvent(
            id = "send:$eventId", chatId = chatId, direction = "outgoing", kind = method,
            status = "attempted", occurredAt = startedAt, receivedAt = receivedAt,
            attemptId = attemptId, content = content,
        ))
        try {
            val result = delegate.execute(request)
            val sentMessageId = (result as? ChatMessage)?.messageId?.long
            safeRecord(InteractionEvent(
                id = "sent:$eventId", chatId = chatId, direction = "outgoing", kind = method,
                status = "accepted", receivedAt = receivedAt, messageId = sentMessageId,
                attemptId = attemptId, content = content,
            ))
            return result
        } catch (error: CancellationException) {
            safeRecord(InteractionEvent(
                id = "failed:$eventId", chatId = chatId, direction = "outgoing", kind = method,
                status = "uncertain", receivedAt = receivedAt, attemptId = attemptId,
            ))
            throw error
        } catch (error: Throwable) {
            safeRecord(InteractionEvent(
                id = "failed:$eventId", chatId = chatId, direction = "outgoing", kind = method,
                status = "failed", receivedAt = receivedAt, attemptId = attemptId,
            ))
            throw error
        }
    }

    private fun safeRecord(event: InteractionEvent) {
        if (!audit.enqueue(event)) {
            log.warn("Telegram audit queue full method={} event={}", event.kind, event.id)
        }
    }
}
