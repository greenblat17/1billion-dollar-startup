package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.TrialBooking
import dev.inmo.tgbotapi.requests.abstracts.SimpleRequest
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal fun bookingWidgetData(booking: TrialBooking): String {
    val local = Instant.parse(booking.startsAtUtc).atZone(ZoneId.of(booking.timezone))
    val cancelled = booking.status == "cancelled"
    val end = local.plusMinutes(booking.durationMinutes.toLong())
    val clock = DateTimeFormatter.ofPattern("HH:mm")
    val ru = Locale.forLanguageTag("ru")
    val zone = if (booking.timezone == "Europe/Moscow") "МСК" else "UTC" + local.offset.id.replace("Z", "")
    val nextDate = if (end.toLocalDate() != local.toLocalDate()) end.format(DateTimeFormatter.ofPattern("d MMMM", ru)) + " " else ""
    return buildJsonObject {
        put("version", booking.version)
        put("cancelled", cancelled)
        put("state", if (cancelled) "Пробное занятие отменено" else "Пробное занятие назначено")
        put("month", local.format(DateTimeFormatter.ofPattern("MMM", ru)).replace(".", ""))
        put("day", local.dayOfMonth.toString())
        put("date", local.format(DateTimeFormatter.ofPattern("d MMMM yyyy · EEEE", ru)))
        put("weekday", local.format(DateTimeFormatter.ofPattern("EEEE", ru)).replaceFirstChar { it.titlecase(ru) } + " · Саша")
        put("time", local.format(clock) + "–" + nextDate + end.format(clock) + " ($zone)")
        put("zone", booking.timezone)
        put("footer", if (cancelled) "Обнови событие в календаре." else "Бесплатно · Онлайн в Speaky")
    }.toString()
}

@Serializable
internal data class BookingWidgetRequest(
    @SerialName("chat_id") val chatId: Long,
    @SerialName("message_id") val messageId: Long? = null,
    @SerialName("chat_data") val data: String? = null,
    val caption: String? = null,
    @SerialName("reply_markup") val markup: JsonObject? = null,
    @Transient val apiMethod: String = "editMessageWidgetData",
) : SimpleRequest<JsonElement> {
    override fun method() = apiMethod
    override val requestSerializer get() = serializer()
    override val resultDeserializer get() = JsonElement.serializer()
}

/** Sending has an unknown-outcome boundary. An edit failure never triggers a blind new send. */
internal suspend fun deliverBookingCard(chat: Long, booking: TrialBooking, caption: String, messageId: Long?,
    widget: Boolean, send: suspend (SimpleRequest<JsonElement>) -> JsonElement): Long {
    val markup = tutorBookingMarkup(booking.status == "scheduled", booking.id, calendar = true)
    suspend fun edit(request: SimpleRequest<JsonElement>): JsonElement = try { send(request) }
    catch (error: RequestException) {
        if (error.response.description?.contains("message is not modified", ignoreCase = true) == true) JsonPrimitive(true)
        else throw error
    }
    if (messageId == null) {
        val result = send(TutorCardRequest(chat, caption, markup))
        return result.jsonObject.getValue("message_id").jsonPrimitive.long
    }
    if (widget) {
        edit(BookingWidgetRequest(chat, messageId, data = bookingWidgetData(booking), apiMethod = "editMessageWidgetData"))
        edit(BookingWidgetRequest(chat, messageId, caption = "", markup = markup, apiMethod = "editMessageCaption"))
    } else edit(TutorCardRequest(chat, caption, markup, messageId))
    return messageId
}
