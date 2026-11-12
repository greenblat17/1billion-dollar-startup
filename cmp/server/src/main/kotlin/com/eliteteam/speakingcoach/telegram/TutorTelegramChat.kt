package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.RuntimeDiagnostics
import com.eliteteam.speakingcoach.ai.TutorClient
import com.eliteteam.speakingcoach.ai.TutorRequest
import com.eliteteam.speakingcoach.ai.TutorDelivery
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.api.send.sendBotAction
import dev.inmo.tgbotapi.types.actions.TypingAction
import dev.inmo.tgbotapi.types.buttons.ReplyKeyboardRemove
import dev.inmo.tgbotapi.types.message.abstracts.ChatMessage
import dev.inmo.tgbotapi.requests.abstracts.SimpleRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import java.time.Instant

/** Native disabled button is absent from tgbotapi 36.1.0; use the Bot API wire shape. */
internal fun tutorBookingMarkup(active: Boolean, lessonId: String? = null, miniUrl: String = System.getenv("MINI_APP_URL").orEmpty()): JsonObject = buildJsonObject {
    put("inline_keyboard", buildJsonArray {
        if (active) add(buildJsonArray { add(buildJsonObject {
            put("text", "Открыть урок")
            if (lessonId != null && miniUrl.startsWith("https://")) {
                put("web_app", buildJsonObject { put("url", miniUrl + (if ('?' in miniUrl) "&" else "?") + "lesson=" + java.net.URLEncoder.encode(lessonId, "UTF-8")) })
            } else put("disabled", buildJsonObject {})
        }) })
    })
}

@Serializable
internal data class TutorCardRequest(
    @SerialName("chat_id") val chatId: Long,
    val text: String,
    @SerialName("reply_markup") val markup: JsonObject,
    @SerialName("message_id") val messageId: Long? = null,
) : SimpleRequest<JsonElement> {
    override fun method() = if (messageId == null) "sendMessage" else "editMessageText"
    override val requestSerializer get() = serializer()
    override val resultDeserializer get() = JsonElement.serializer()
}

internal class TutorTelegramChat(private val bot: BehaviourContext, private val client: TutorClient) {

    suspend fun respond(message: ChatMessage, text: String, at: Instant, start: Boolean = false) =
        RuntimeDiagnostics.withContext(mapOf("trace_id" to RuntimeDiagnostics.trace(
            "tg-${telegramChatNumber(message.chat.id)}:message:${message.messageId.long}"))) {
            try { respondCurrent(message, text, at, start) }
            catch (error: Exception) {
                if (error !is CancellationException) RuntimeDiagnostics.record("telegram_turn_failed", "respond_or_deliver", error)
                throw error
            }
        }

    private suspend fun respondCurrent(message: ChatMessage, text: String, at: Instant, start: Boolean) = coroutineScope {
        require(message.chat is dev.inmo.tgbotapi.types.chat.PrivateChat) { "Tutor requires a private Telegram chat" }
        val typing = launch {
            while (true) {
                try { bot.sendBotAction(message.chat.id, TypingAction) }
                catch (e: CancellationException) { throw e }
                catch (error: Exception) { RuntimeDiagnostics.record("telegram_typing_failed", "typing", error) }
                delay(4000)
            }
        }
        try {
            val sessionId = "tg-${requireNotNull(telegramChatNumber(message.chat.id))}"
            val requestId = "message:${message.messageId.long}"
            val response = client.respond(TutorRequest(sessionId, requestId, text, message.date.unixMillis / 1000.0), start)
            if (response.replyMessageId == null && client.beginDelivery(TutorDelivery(sessionId, requestId))) {
                var lastMessageId: Long? = null
                for (part in response.reply.chunked(3500)) {
                    lastMessageId = bot.reply(message, part, allowSendingWithoutReply = true,
                        replyMarkup = ReplyKeyboardRemove()).messageId.long
                }
                client.delivered(TutorDelivery(sessionId, requestId, replyMessageId = lastMessageId))
            }
            response.booking?.let { booking ->
                if (!client.beginDelivery(TutorDelivery(sessionId, requestId, bookingId = booking.id, version = booking.version))) return@let
                val chatId = telegramChatNumber(message.chat.id) ?: return@let
                val card = TutorCardRequest(chatId, requireNotNull(response.bookingText),
                    tutorBookingMarkup(booking.status == "scheduled", booking.id), response.cardMessageId)
                val sent = try { bot.execute(card) }
                catch (e: CancellationException) { throw e }
                catch (error: Exception) {
                    if (card.messageId != null && error is RequestException &&
                        error.response.description?.contains("message is not modified", ignoreCase = true) == true
                    ) JsonPrimitive(true)
                    else {
                        RuntimeDiagnostics.record("telegram_card_failed", "edit_card_fallback", error)
                        bot.execute(card.copy(messageId = null))
                    }
                }
                val cardId = (sent as? JsonObject)?.get("message_id")?.jsonPrimitive?.long ?: response.cardMessageId
                if (cardId != null) client.delivered(TutorDelivery(sessionId, requestId,
                    bookingId = booking.id, cardMessageId = cardId, version = booking.version))
            }
        } finally { typing.cancelAndJoin() }
    }
}
