package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.foundation.telegramTextTrace
import com.eliteteam.speakingcoach.RuntimeDiagnostics
import com.eliteteam.speakingcoach.RuntimeDiagnostics.Event
import com.eliteteam.speakingcoach.RuntimeDiagnostics.Outcome
import com.eliteteam.speakingcoach.ai.TutorClient
import com.eliteteam.speakingcoach.ai.TutorRequest
import com.eliteteam.speakingcoach.ai.TutorResponse
import com.eliteteam.speakingcoach.ai.TutorDelivery
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import dev.inmo.tgbotapi.extensions.api.send.sendBotAction
import dev.inmo.tgbotapi.types.actions.TypingAction
import dev.inmo.tgbotapi.types.message.abstracts.ChatMessage
import dev.inmo.tgbotapi.requests.abstracts.SimpleRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeoutOrNull
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
        if (active) add(buildJsonArray { add(buildJsonObject {
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
        RuntimeDiagnostics.withContext(mapOf("trace_id" to telegramTextTrace(telegramChatNumber(message.chat.id), message.messageId.long))) {
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
                typing = { withTimeoutOrNull(900) { bot.sendBotAction(message.chat.id, TypingAction) } },
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
                catch (error: Exception) { recordTelegramTypingFailure("typing", error) }
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
                val widget = response.cardMessageId != null && response.cardIsWidget
                val caption = if (foundation != null && booking.status == "scheduled") {
                    response.reply
                } else requireNotNull(response.bookingText)
                val cardId = try {
                    checkNotNull(withTimeoutOrNull(8_000) {
                        deliverBookingCard(chatId, booking, caption, response.cardMessageId, widget) { bot.execute(it) }
                    }) { "Telegram booking delivery timed out" }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // The booking is committed. Leave the delivery receipt unknown and release intake;
                    // a replay cannot safely resend, and a new booking inquiry reads the current lesson.
                    if (foundation == null) throw error
                    recordTelegramSendFailure("telegram_booking_delivery_unknown", "telegram.card", "send_or_edit", error)
                    return@coroutineScope
                }
                client.delivered(TutorDelivery(sessionId, requestId,
                    bookingId = booking.id, cardMessageId = cardId, version = booking.version, cardIsWidget = widget))
            }
            val alreadyConfirmed = response.booking?.startsImmediately == true ||
                (foundation != null && response.booking?.status == "scheduled")
            if (!alreadyConfirmed) {
                deliverReply(response, sessionId, requestId, chatId, message.messageId.long)
            }
            client.foundation?.beginPayment(sessionId, requestId)?.let { payment ->
                val id = payment.getValue("id").jsonPrimitive.content
                bot.execute(TutorCardRequest(requireNotNull(telegramChatNumber(message.chat.id)),
                    payment.getValue("terms").jsonPrimitive.content, mockPaymentMarkup(id)))
                client.foundation?.paymentDelivered(sessionId, id)
            }

        } finally { typing.cancelAndJoin() }
    }

    private suspend fun deliverReply(response: TutorResponse, session: String, request: String, chat: Long, incoming: Long) {
        if (response.reply.isBlank() || response.replyMessageId != null || !client.beginDelivery(TutorDelivery(session, request))) return
        val parts = if (response.splitIntroductionReply) introductionTextParts(response.reply)
            else tutorTextParts(response.reply)
        var lastMessageId: Long? = null
        var activePart = 0
        try {
            for ((index, part) in parts.withIndex()) {
                activePart = index
                val markup = when {
                    response.joinLessonId != null && index == parts.lastIndex -> tutorBookingMarkup(true, response.joinLessonId)
                    response.showProgressNavigation -> tutorProgressMarkup()
                    else -> tutorProgressMarkup(miniUrl = "")
                }
                val sent = bot.execute(TutorTextRequest(chat, part.text, part.entities, markup,
                    if (client.foundation == null) TutorReplyParameters(incoming, true) else null))
                lastMessageId = (sent as JsonObject).getValue("message_id").jsonPrimitive.long
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (client.foundation == null) throw error
            recordTelegramSendFailure("telegram_reply_delivery_unknown", "telegram.reply", "send", error,
                mapOf("part" to activePart.toString()))
            return
        }
        client.delivered(TutorDelivery(session, request, replyMessageId = lastMessageId))
    }
}

internal fun recordTelegramTypingFailure(stage: String, error: Exception) {
    RuntimeDiagnostics.record(Event("telegram_typing_failed", "telegram.typing", stage, Outcome.FAILED,
        "Telegram did not confirm the typing indicator.", "typing_action_failed", coalesce = true),
        mapOf("provider" to "telegram"), error)
}

internal fun recordTelegramSendFailure(name: String, operation: String, stage: String, error: Exception,
    fields: Map<String, String> = emptyMap()) {
    val code = (error as? RequestException)?.response?.errorCode
    val rejected = code != null && code in 400..499
    val event = if (rejected) Event(name, operation, stage, Outcome.REJECTED,
        "Telegram explicitly rejected the send request.", "telegram_request_rejected")
    else Event(name, operation, stage, Outcome.UNKNOWN,
        "Telegram delivery could not be confirmed.", "telegram_delivery_unconfirmed")
    RuntimeDiagnostics.record(event, fields + ("provider" to "telegram"), error)
}
