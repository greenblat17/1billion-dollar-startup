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

/** Only the state changes on a sent widget. Never embed learner input in HTML. */
internal val bookingWidgetHtml = """
<!doctype html><html><head><meta charset="utf-8"><style>
body{font-family:system-ui;margin:0;padding:18px;color:var(--tg-theme-text-color)}
header{display:flex;justify-content:space-between;align-items:center;gap:12px}
.brand{font-size:.8em;letter-spacing:.12em;color:var(--tg-theme-hint-color)}
#state{font-size:.8em;color:var(--tg-theme-accent-text-color)}
h2{font-size:1.3em;line-height:1.2;margin:20px 0 16px;font-weight:650}
.details{border-top:1px solid var(--tg-theme-section-separator-color);padding-top:16px}
#date{font-size:1em;font-weight:600;margin:0 0 8px}
#time{font-size:1.35em;font-weight:600;margin:0 0 6px}
#zone,.footer{font-size:.85em;color:var(--tg-theme-hint-color);margin:0}
.footer{margin-top:18px;padding-bottom:6px}
</style></head><body><header><span class="brand">SPEAKY</span><span id="state"></span></header>
<h2>Пробное занятие<br>с Сашей</h2><div class="details"><p id="date"></p><p id="time"></p><p id="zone"></p></div>
<p class="footer" id="footer"></p><script>
const W=window.TelegramWidget;let revision=-1;
function render(){let d;try{d=JSON.parse(W.chatData);}catch(e){return;}if(!d||d.version<revision)return;revision=d.version;
for(const key of ['state','date','time','zone','footer'])document.getElementById(key).textContent=d[key]||'';}
W.onEvent('chatDataChanged',render);render();
</script></body></html>
""".trimIndent()

internal fun bookingWidgetData(booking: TrialBooking): String {
    val local = Instant.parse(booking.startsAtUtc).atZone(ZoneId.of(booking.timezone))
    val cancelled = booking.status == "cancelled"
    return buildJsonObject {
        put("version", booking.version)
        put("state", if (cancelled) "Отменено" else "Подтверждено")
        put("date", local.format(DateTimeFormatter.ofPattern("d MMMM yyyy · EEEE", Locale.forLanguageTag("ru"))))
        put("time", local.format(DateTimeFormatter.ofPattern("HH:mm")) + " · ${booking.durationMinutes} минут")
        put("zone", booking.timezone)
        put("footer", if (cancelled) "Обнови событие в своём календаре." else "Бесплатно · Звонок в Speaky")
    }.toString()
}

@Serializable
internal data class BookingWidgetRequest(
    @SerialName("chat_id") val chatId: Long,
    @SerialName("message_id") val messageId: Long? = null,
    val html: String? = null,
    @SerialName("chat_data") val data: String? = null,
    val caption: String? = null,
    @SerialName("reply_markup") val markup: JsonObject? = null,
    @Transient val apiMethod: String = "sendWidget",
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
        val result = send(BookingWidgetRequest(chat, html = bookingWidgetHtml, data = bookingWidgetData(booking), caption = caption, markup = markup))
        return result.jsonObject.getValue("message_id").jsonPrimitive.long
    }
    if (widget) {
        edit(BookingWidgetRequest(chat, messageId, data = bookingWidgetData(booking), apiMethod = "editMessageWidgetData"))
        edit(BookingWidgetRequest(chat, messageId, caption = caption, markup = markup, apiMethod = "editMessageCaption"))
    } else edit(TutorCardRequest(chat, caption, markup, messageId))
    return messageId
}
