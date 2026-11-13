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
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>
*{box-sizing:border-box}body{font:15px/1.4 system-ui;margin:0;padding:16px;color:var(--tg-theme-text-color,#202124);background:var(--tg-theme-bg-color,#fff)}
header{display:flex;align-items:center;gap:10px;font-size:16px;font-weight:600;margin-bottom:18px}
.status{width:19px;height:19px;flex:none;color:#16a65b}body.cancelled .status{color:var(--tg-theme-hint-color,#888)}
.details{display:flex;align-items:center;gap:16px}.date{flex:none;text-align:center;min-width:64px;padding:10px 12px;border-radius:14px;background:var(--tg-theme-secondary-bg-color,#f3f4f5)}
#month{font-size:12px;color:var(--tg-theme-hint-color,#707579);text-transform:uppercase}#day{font-size:32px;font-weight:600;line-height:1.15;margin-top:4px}
#time{font-size:18px;font-weight:600;margin:0 0 5px}#weekday,.footer{color:var(--tg-theme-hint-color,#707579);font-size:14px;margin:0}.footer{margin-top:4px}
@media(max-width:300px){body{padding:12px}.details{gap:12px}header{font-size:15px}#time{font-size:16px}}
</style></head><body><header><svg class="status" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="m8 12 3 3 5-6"/></svg><span id="state"></span></header>
<div class="details"><div class="date"><div id="month"></div><div id="day"></div></div><div><p id="time"></p><p id="weekday"></p><p class="footer" id="footer"></p></div></div><script>
const W=window.TelegramWidget;let revision=-1;
function render(){let d;try{d=JSON.parse(W.chatData);}catch(e){return;}if(!d||d.version<revision)return;revision=d.version;
for(const key of ['state','month','day','weekday','time','footer'])document.getElementById(key).textContent=d[key]||'';
document.body.classList.toggle('cancelled',!!d.cancelled);}
W.onEvent('chatDataChanged',render);render();
</script></body></html>
""".trimIndent()

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
        if (booking.status == "scheduled") send(TutorTextRequest(chat, "Отлично, договорились! 🙂", emptyList(), tutorProgressMarkup(miniUrl = "")))
        val result = send(BookingWidgetRequest(chat, html = bookingWidgetHtml, data = bookingWidgetData(booking), markup = markup))
        return result.jsonObject.getValue("message_id").jsonPrimitive.long
    }
    if (widget) {
        edit(BookingWidgetRequest(chat, messageId, data = bookingWidgetData(booking), apiMethod = "editMessageWidgetData"))
        edit(BookingWidgetRequest(chat, messageId, caption = "", markup = markup, apiMethod = "editMessageCaption"))
    } else edit(TutorCardRequest(chat, caption, markup, messageId))
    return messageId
}
