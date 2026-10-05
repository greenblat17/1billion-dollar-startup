package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.analytics.CallEvent
import com.eliteteam.speakingcoach.analytics.CallEventsSnapshot
import com.eliteteam.speakingcoach.analytics.voiceAttemptId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

private val callZone = ZoneId.of("Europe/Moscow")
private val callTimeFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
internal const val CALLS_PATH = "/admin/metrics/calls"

internal data class CallDashboardFilter(
    val days: Int = 7,
    val source: String? = null,
    val status: String? = null,
    val failed: Boolean? = null,
    val offset: Int = 0,
)

internal data class CallDashboardRow(val callId: String, val events: List<CallEvent>) {
    val opened: CallEvent = events.first { it.kind == "call_open" }
    val closed: CallEvent? = events.firstOrNull { it.kind == "call_closed" }
    val voices = events.filter { it.kind == "voice_received" }.distinctBy { it.messageId }
    val processed = events.filter { it.kind == "voice_processed" }.distinctBy { it.messageId }
    val replies = events.filter { it.kind == "reply_delivered" }.distinctBy { it.messageId }
    val outcomes = events.filter { it.kind == "voice_outcome" }.distinctBy { it.messageId }
    val cards = events.filter { it.kind == "correction_card" }.distinctBy { it.messageId }
    val subtitles = events.filter { it.kind == "subtitle_click" }.distinctBy { it.id }
    val source: String = opened.state ?: "unknown"
    val audioSeconds: Double = voices.sumOf { it.seconds ?: 0.0 }
    val recognizedSeconds: Double = processed.sumOf { it.seconds ?: 0.0 }
    val audioReplies: Int = replies.count { it.state == "audio" }
    val textFallbacks: Int = replies.count { it.state == "text_fallback" }
    val failed: Boolean = outcomes.any { it.state !in setOf("delivered", "text_fallback") } || textFallbacks > 0
    val firstVoice: Instant? = voices.minOfOrNull { it.at }
    val lastVoice: CallEvent? = voices.maxByOrNull { it.at }
    val lastReply: CallEvent? = replies.maxByOrNull { it.at }
    val dialogueEnd: Instant? = when {
        lastVoice == null -> null
        lastReply == null -> lastVoice.at
        replies.any { it.messageId == lastVoice.messageId } -> replies.first { it.messageId == lastVoice.messageId }.at
        else -> lastVoice.at
    }
    val lastVoiceUnanswered: Boolean = lastVoice != null && replies.none { it.messageId == lastVoice.messageId }
    val goalSeconds: Double? = opened.seconds
    val reviewDelivered: Boolean = events.any { it.kind == "review_delivered" }
    val reviewGenerated: Boolean = events.any { it.kind == "review_generated" }
}

internal fun callDashboardRows(snapshot: CallEventsSnapshot): List<CallDashboardRow> =
    snapshot.events.filter { it.callId != null }.groupBy { it.callId!! }.mapNotNull { (id, events) ->
        if (events.none { it.kind == "call_open" }) null else CallDashboardRow(id, events.sortedBy { it.at })
    }.sortedByDescending { it.opened.at }

internal fun callDashboardPage(snapshot: CallEventsSnapshot, filter: CallDashboardFilter,
                               memoryOnly: Boolean = false): String {
    val today = LocalDate.now(callZone)
    val from = today.minusDays((filter.days - 1).toLong())
    val allRows = callDashboardRows(snapshot)
    val rows = allRows.filter { row ->
        val day = row.opened.at.atZone(callZone).toLocalDate()
        !day.isBefore(from) && !day.isAfter(today) &&
            (filter.source == null || row.source == filter.source) &&
            (filter.status == null || (if (row.closed == null) "open" else "closed") == filter.status) &&
            (filter.failed == null || row.failed == filter.failed)
    }
    val selectedIds = rows.map { it.callId }.toSet()
    val noCallFilter = filter.source == null && filter.status == null && filter.failed == null
    val starts = snapshot.events.count { it.kind == "start_pressed" &&
        !it.at.atZone(callZone).toLocalDate().isBefore(from) &&
        !it.at.atZone(callZone).toLocalDate().isAfter(today) &&
        (it.callId in selectedIds || (noCallFilter && it.callId == null)) }
    val waits = rows.flatMap { row -> row.replies.filter { it.state == "audio" }.mapNotNull { it.milliseconds } }
    val firstVoiceWaits = rows.filter { it.source == "button" }.mapNotNull { row ->
        row.firstVoice?.let { (it.toEpochMilli() - row.opened.at.toEpochMilli()).coerceAtLeast(0) }
    }
    val closed = rows.count { it.closed != null }
    val daysByChat = allRows.groupBy { it.opened.chatId to it.opened.at.atZone(callZone).toLocalDate() }
    val selectedDays = rows.map { it.opened.chatId to it.opened.at.atZone(callZone).toLocalDate() }.toSet()
    val goalDays = daysByChat.filterKeys { it in selectedDays }.values.count { group ->
        val goal = group.firstNotNullOfOrNull { it.goalSeconds?.takeIf { seconds -> seconds > 0 } }
        goal != null && group.sumOf { it.recognizedSeconds } >= goal
    }
    val sourceLinks = listOf(null to "Все", "button" to "Кнопка", "voice" to "Голосовое")
    val statusLinks = listOf(null to "Все", "open" to "Открытые", "closed" to "Закрытые")
    val filters = buildString {
        append("<form method=\"get\" action=\"$CALLS_PATH\"><label>Период <select name=\"days\">")
        for (days in listOf(1, 7, 30)) append("<option value=\"$days\"${if (filter.days == days) " selected" else ""}>${if (days == 1) "Сегодня" else "$days дней"}</option>")
        append("</select></label> <label>Начало <select name=\"source\">")
        for ((value, label) in sourceLinks) append("<option value=\"${value ?: ""}\"${if (filter.source == value) " selected" else ""}>$label</option>")
        append("</select></label> <label>Статус <select name=\"status\">")
        for ((value, label) in statusLinks) append("<option value=\"${value ?: ""}\"${if (filter.status == value) " selected" else ""}>$label</option>")
        append("</select></label> <label>Ошибки <select name=\"failed\">")
        for ((value, label) in listOf(null to "Все", true to "Есть", false to "Нет"))
            append("<option value=\"${value ?: ""}\"${if (filter.failed == value) " selected" else ""}>$label</option>")
        append("</select></label> <button>Показать</button></form>")
    }
    val table = rows.drop(filter.offset).take(25).joinToString("") { row ->
        val status = row.closed?.state ?: "открыт"
        "<tr><td><a href=\"$CALLS_PATH/${row.callId}\">${callTime(row.opened.at)}</a></td>" +
            "<td>${row.opened.chatId}${row.opened.username.takeIf { it.isNotBlank() }?.let { " (@${escapeHtml(it)})" } ?: ""}</td><td>${escapeHtml(row.source)}</td>" +
            "<td>${escapeHtml(status)}</td><td>${row.voices.size}</td>" +
            "<td>${secondsText(row.recognizedSeconds)}</td><td>${row.audioReplies}</td>" +
            "<td>${if (row.failed) "Да" else "Нет"}</td></tr>"
    }
    val next = if (filter.offset + 25 < rows.size) {
        val query = "days=${filter.days}&source=${filter.source ?: ""}&status=${filter.status ?: ""}&failed=${filter.failed ?: ""}&offset=${filter.offset + 25}"
        "<p><a href=\"$CALLS_PATH?$query\">Следующие 25 →</a></p>"
    } else ""
    val daily = rows.groupBy { it.opened.at.atZone(callZone).toLocalDate() }
        .toSortedMap(reverseOrder()).entries.joinToString("") { (day, group) ->
            "<tr><td>$day</td><td>${group.size}</td><td>${group.count { it.closed != null }}</td>" +
                "<td>${group.sumOf { it.voices.size }}</td><td>${secondsText(group.sumOf { it.recognizedSeconds })}</td>" +
                "<td>${group.sumOf { it.audioReplies }}</td></tr>"
        }
    return """
        <!doctype html><html lang="ru"><head><meta charset="utf-8"><title>Звонки · Speaky</title>${pageStyle()}</head><body>
        ${adminTabs(CALLS_PATH)}<h1>Практические звонки</h1>
        <p class="meta">Московская дата начала · данные с момента включения сбора · ${if (memoryOnly) "Локальные данные пропадут при перезапуске · " else ""}потери записей: ${snapshot.writeFailures}${if (snapshot.truncated) " · лимит 50 000 событий, отчёт неполный" else ""}</p>
        $filters
        <dl>
        ${card("Звонков", rows.size.toString())}${card("Нажатий Start call", starts.toString())}
        ${card("Кнопка / голос", "${rows.count { it.source == "button" }} / ${rows.count { it.source == "voice" }}")}
        ${card("Закрыто / открыто", "$closed / ${rows.size - closed}")}
        ${card("End call / следующий день / onboarding", "${rows.count { it.closed?.state == "end_button" }} / ${rows.count { it.closed?.state == "next_moscow_day" }} / ${rows.count { it.closed?.state == "onboarding_reset" }}")}
        ${card("Диалог начат", rows.count { it.firstVoice != null }.toString())}
        ${card("Первый голос после кнопки · p50/p95", percentileText(firstVoiceWaits))}
        ${card("Голосовых", rows.sumOf { it.voices.size }.toString())}
        ${card("Аудиофайлы / речь", "${secondsText(rows.sumOf { it.audioSeconds })} / ${secondsText(rows.sumOf { it.recognizedSeconds })}")}
        ${card("Аудиоответы / текст", "${rows.sumOf { it.audioReplies }} / ${rows.sumOf { it.textFallbacks }}")}
        ${card("Ожидание аудио · p50/p95", percentileText(waits))}
        ${card("Исправления: принято / карточек / доставлено", "${rows.sumOf { row -> row.processed.sumOf { it.amount ?: 0 } }} / ${rows.sumOf { it.cards.size }} / ${rows.sumOf { row -> row.cards.count { it.state == "delivered" } }}")}
        ${card("Subtitles: нажатий / ответов", "${rows.sumOf { it.subtitles.size }} / ${rows.sumOf { row -> row.subtitles.mapNotNull { it.botMessageId }.distinct().size }}")}
        ${card("Review сформирован / доставлен", "${rows.count { it.reviewGenerated }} / ${rows.count { it.reviewDelivered }}")}
        ${card("Дней с достигнутой целью", goalDays.toString())}
        </dl>
        <h2>По дням</h2><table><thead><tr><th>Дата начала</th><th>Звонки</th><th>Закрыто</th><th>Голоса</th><th>Речь</th><th>Аудиоответы</th></tr></thead><tbody>$daily</tbody></table>
        <h2>Звонки (${rows.size})</h2><table><thead><tr><th>Начало</th><th>Chat ID</th><th>Способ</th><th>Исход</th><th>Голоса</th><th>Речь</th><th>Ответы</th><th>Сбой</th></tr></thead><tbody>$table</tbody></table>$next
        </body></html>
    """.trimIndent()
}

internal fun callDetailPage(row: CallDashboardRow, daySpeechSeconds: Double = row.recognizedSeconds,
                            truncated: Boolean = false): String {
    val lastVoiceUnanswered = if (row.lastVoiceUnanswered) " (последнее голосовое без ответа)" else ""
    val events = row.events.joinToString("") { event ->
        val label = when (event.kind) {
            "start_pressed" -> "Нажат Start call: ${event.state ?: "—"}"
            "call_open" -> "Получено начало: ${event.state ?: "—"}"
            "call_actual_open" -> "Звонок открыт в AI-service"
            "starter_delivered" -> "Вступительный ответ бота доставлен"
            "voice_received" -> "Голосовое #${event.messageId}: файл ${event.seconds?.let(::secondsText) ?: "—"}, ID попытки ${event.messageId?.let { voiceAttemptId(event.chatId, it) } ?: "—"}"
            "voice_processed" -> "Голосовое #${event.messageId}: речь ${event.seconds?.let(::secondsText) ?: "—"}, исправлений ${event.amount ?: 0}"
            "correction_card" -> "Карточка исправления: ${event.state ?: "—"}"
            "reply_delivered" -> "Ответ на #${event.messageId}: ${event.state ?: "—"}, ожидание ${event.milliseconds?.let { "${it} мс" } ?: "—"}"
            "voice_outcome" -> "Исход голосового #${event.messageId}: ${event.state ?: "—"}"
            "voice_failure" -> "Сбой голосового #${event.messageId}: ${event.state ?: "—"}"
            "subtitle_click" -> "Subtitles для ответа #${event.botMessageId}: ${event.state ?: "—"}"
            "end_pressed" -> "Нажат End call"
            "call_closed" -> "Звонок закрыт: ${event.state ?: "—"}"
            "review_requested" -> "Запрошен review"
            "review_generated" -> "Review сформирован"
            "review_failed" -> "Review не сформирован"
            "review_delivery_failed" -> "Review не доставлен"
            "review_delivered" -> "Review доставлен"
            else -> event.kind
        }
        "<tr><td>${callTime(event.at)}</td><td>${escapeHtml(label)}</td></tr>"
    }
    val subtitleClicks = row.subtitles.size
    val uniqueSubtitles = row.subtitles.mapNotNull { it.botMessageId }.distinct().size
    val goal = row.goalSeconds?.let(::secondsText) ?: "—"
    val firstWait = if (row.source == "button" && row.firstVoice != null)
        "${(row.firstVoice.toEpochMilli() - row.opened.at.toEpochMilli()).coerceAtLeast(0)} мс" else "—"
    return """
        <!doctype html><html lang="ru"><head><meta charset="utf-8"><title>Звонок ${row.callId} · Speaky</title>${pageStyle()}</head><body>
        ${adminTabs(CALLS_PATH)}<p><a href="$CALLS_PATH">← Все звонки</a></p><h1>Звонок ${row.callId}</h1>
        <p class="meta">Chat ID ${row.opened.chatId}${row.opened.username.takeIf { it.isNotBlank() }?.let { " · @${escapeHtml(it)}" } ?: ""} · ${escapeHtml(row.source)} · ${escapeHtml(row.closed?.state ?: "открыт")}</p>
        ${if (truncated) "<p>Достигнут лимит 1000 событий: хронология может быть неполной.</p>" else ""}
        <dl>${card("Старт", callTime(row.opened.at))}${card("Конец звонка", row.closed?.at?.let(::callTime) ?: "—")}
        ${card("Начало диалога", row.firstVoice?.let(::callTime) ?: "—")}
        ${card("Конец диалога", (row.dialogueEnd?.let(::callTime) ?: "—") + lastVoiceUnanswered)}
        ${card("До первого голоса", firstWait)}
        ${card("Открытый интервал", row.closed?.let { secondsText((it.at.toEpochMilli() - row.opened.at.toEpochMilli()).coerceAtLeast(0) / 1000.0) } ?: "—")}
        ${card("Аудиофайлы / речь", "${secondsText(row.audioSeconds)} / ${secondsText(row.recognizedSeconds)}")}
        ${card("Цель / речь в звонке", "$goal / ${secondsText(row.recognizedSeconds)}")}
        ${card("Речь за московский день", secondsText(daySpeechSeconds))}
        ${card("Голосовые / аудиоответы / текст", "${row.voices.size} / ${row.audioReplies} / ${row.textFallbacks}")}
        ${card("Исправления: принято / карточек / доставлено", "${row.processed.sumOf { it.amount ?: 0 }} / ${row.cards.size} / ${row.cards.count { it.state == "delivered" }}")}
        ${card("Subtitles: нажатий / ответов / повторов", "$subtitleClicks / $uniqueSubtitles / ${subtitleClicks - uniqueSubtitles}")}
        ${card("Review сформирован / доставлен", "${if (row.reviewGenerated) "Да" else "Нет"} / ${if (row.reviewDelivered) "Да" else "Нет"}")}</dl>
        <h2>Хронология</h2><table><thead><tr><th>Время · Москва</th><th>Событие</th></tr></thead><tbody>$events</tbody></table>
        </body></html>
    """.trimIndent()
}

private fun callTime(at: Instant): String = callTimeFormat.format(at.atZone(callZone))
private fun secondsText(seconds: Double): String = "${"%.1f".format(java.util.Locale.ROOT, seconds)} с"
private fun percentileText(values: List<Long>): String {
    if (values.isEmpty()) return "— (n=0)"
    val sorted = values.sorted()
    fun pick(p: Double) = sorted[(ceil(sorted.size * p).toInt() - 1).coerceIn(0, sorted.lastIndex)]
    return "${pick(0.5)} / ${pick(0.95)} мс (n=${values.size})"
}
