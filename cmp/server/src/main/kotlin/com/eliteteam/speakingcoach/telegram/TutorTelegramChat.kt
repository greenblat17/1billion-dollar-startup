package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.RuntimeDiagnostics
import com.eliteteam.speakingcoach.ai.TutorClient
import com.eliteteam.speakingcoach.ai.TutorRequest
import com.eliteteam.speakingcoach.ai.TutorDelivery
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.api.send.sendBotAction
import dev.inmo.tgbotapi.types.actions.TypingAction
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Instant

/** Native disabled button is absent from tgbotapi 36.1.0; use the Bot API wire shape. */
internal fun tutorBookingMarkup(active: Boolean, lessonId: String? = null, miniUrl: String = System.getenv("MINI_APP_URL").orEmpty(), calendar: Boolean = false): JsonObject = buildJsonObject {
    put("inline_keyboard", buildJsonArray {
        if (active && !calendar) add(buildJsonArray { add(buildJsonObject {
            put("text", "Начать урок")
            if (lessonId != null && miniUrl.startsWith("https://")) {
                put("style", "primary")
                put("web_app", buildJsonObject { put("url", miniUrl + (if ('?' in miniUrl) "&" else "?") + "lesson=" + java.net.URLEncoder.encode(lessonId, "UTF-8")) })
            } else put("disabled", buildJsonObject {})
        }) })
        if (active && calendar && lessonId != null && miniUrl.startsWith("https://")) add(buildJsonArray {
            add(buildJsonObject {
                put("text", "Добавить в календарь")
                put("web_app", buildJsonObject { put("url", miniUrl + (if ('?' in miniUrl) "&" else "?") +
                    "view=calendar&lesson=" + java.net.URLEncoder.encode(lessonId, "UTF-8")) })
            })
        })
    })
}

/** Persistent navigation is independent of lesson admission and payment. */
internal fun tutorProgressMarkup(miniUrl: String = System.getenv("MINI_APP_URL").orEmpty()): JsonObject = buildJsonObject {
    if (miniUrl.startsWith("https://")) {
        put("keyboard", buildJsonArray { add(buildJsonArray { add(buildJsonObject {
            put("text", "My Progress")
            put("web_app", buildJsonObject { put("url", miniUrl + (if ('?' in miniUrl) "&" else "?") + "view=progress") })
        }) }) })
        put("resize_keyboard", true)
        put("is_persistent", true)
    } else put("remove_keyboard", true)
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
        val chatId = requireNotNull(telegramChatNumber(message.chat.id))
        val sessionId = "tg-$chatId"
        val requestId = "message:${message.messageId.long}"
        val request = TutorRequest(sessionId, requestId, text, message.date.unixMillis / 1000.0)
        val foundation = client.foundation
        if (start && foundation != null && foundation.prepareIntroduction(request, chatId, message.messageId.long)) {
            deliverIntroduction(foundation.introduction, chatId, requestId,
                typing = { bot.sendBotAction(message.chat.id, TypingAction) },
                send = { introText ->
                    val sent = bot.execute(TutorTextRequest(chatId, introText, emptyList(), tutorProgressMarkup(miniUrl = "")))
                    (sent as JsonObject).getValue("message_id").jsonPrimitive.long
                })
            return@coroutineScope
        }
        val typing = launch {
            while (true) {
                try { bot.sendBotAction(message.chat.id, TypingAction) }
                catch (e: CancellationException) { throw e }
                catch (error: Exception) { RuntimeDiagnostics.record("telegram_typing_failed", "typing", error) }
                delay(4000)
            }
        }
        try {
            val response = client.respond(request, start)
            response.booking?.takeUnless { it.startsImmediately }?.let { booking ->
                if (!client.beginDelivery(TutorDelivery(sessionId, requestId, bookingId = booking.id, version = booking.version))) {
                    // Unknown/stale delivery must not be followed by a claim that confirmation arrived.
                    if (!response.cardDelivered) return@coroutineScope
                    return@let
                }
                val widget = response.cardMessageId == null || response.cardIsWidget
                val cardId = deliverBookingCard(chatId, booking, requireNotNull(response.bookingText),
                    response.cardMessageId, widget) { bot.execute(it) }
                client.delivered(TutorDelivery(sessionId, requestId,
                    bookingId = booking.id, cardMessageId = cardId, version = booking.version, cardIsWidget = widget))
            }
            if (response.booking?.startsImmediately != true && response.reply.isNotBlank() && response.replyMessageId == null && client.beginDelivery(TutorDelivery(sessionId, requestId))) {
                var lastMessageId: Long? = null
                for (part in tutorTextParts(response.reply)) {
                    val sent = bot.execute(TutorTextRequest(requireNotNull(telegramChatNumber(message.chat.id)),
                        part.text, part.entities, if (response.showProgressNavigation) tutorProgressMarkup() else tutorProgressMarkup(miniUrl = ""),
                        TutorReplyParameters(message.messageId.long, true)))
                    lastMessageId = (sent as JsonObject).getValue("message_id").jsonPrimitive.long
                }
                client.delivered(TutorDelivery(sessionId, requestId, replyMessageId = lastMessageId))
            }
            client.foundation?.beginPayment(sessionId, requestId)?.let { payment ->
                val id = payment.getValue("id").jsonPrimitive.content
                bot.execute(TutorCardRequest(requireNotNull(telegramChatNumber(message.chat.id)),
                    payment.getValue("terms").jsonPrimitive.content, mockPaymentMarkup(id)))
                client.foundation?.paymentDelivered(sessionId, id)
            }

        } finally { typing.cancelAndJoin() }
    }
}
