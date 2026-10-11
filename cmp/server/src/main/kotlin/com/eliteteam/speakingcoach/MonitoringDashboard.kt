package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.analytics.VoiceAttemptRecorder
import com.eliteteam.speakingcoach.analytics.OnboardingAnalytics
import com.eliteteam.speakingcoach.analytics.InteractionAudit
import com.eliteteam.speakingcoach.analytics.InteractionChat
import com.eliteteam.speakingcoach.analytics.InteractionEvent
import com.eliteteam.speakingcoach.ai.LegacyCampaignStatus
import com.eliteteam.speakingcoach.ai.MetricsSnapshot
import com.eliteteam.speakingcoach.ai.MetricsV2Client
import com.eliteteam.speakingcoach.telegram.LegacyCampaignAdmin
import com.eliteteam.speakingcoach.telegram.ReminderAdmin
import com.eliteteam.speakingcoach.telegram.reminderTemplateById
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.Locale
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.json.Json

internal const val MONITORING_PATH = "/admin/monitoring"

internal fun monitoringRequestAllowed(localPort: Int, monitoringPort: Int): Boolean =
    monitoringPort == 0 || localPort == monitoringPort

internal class MonitoringDashboard(
    val source: MetricsSource,
    val reminders: ReminderAdmin? = null,
    val campaign: LegacyCampaignAdmin? = null,
    val monitoringPort: Int = 0,
    val voiceAttempts: VoiceAttemptRecorder? = null,
    val audit: InteractionAudit? = null,
    val onboarding: OnboardingAnalytics? = null,
)

@OptIn(ExperimentalKtorApi::class)
internal fun Route.installMonitoringDashboard(dashboard: MonitoringDashboard) {
    val log = LoggerFactory.getLogger("MonitoringDashboard")
    val root = MONITORING_PATH
    get(root) {
        if (call.blockPublicMonitoring(dashboard.monitoringPort)) return@get
        val html = try {
            monitoringReportHtml(dashboard.source.load())
        } catch (error: Throwable) {
            log.warn("Monitoring snapshot failed", error)
            metricsUnavailableHtml()
        }
        call.respondText(html, ContentType.Text.Html)
    }.hide()
    get("$root/reminders") {
        if (call.blockPublicMonitoring(dashboard.monitoringPort)) return@get
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val days = call.request.queryParameters["days"]?.toIntOrNull()?.takeIf { it == 7 || it == 30 } ?: 7
        val snapshot = try { dashboard.source.load() }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { log.warn("Monitoring reminder snapshot failed", error); null }
        val clock = snapshot?.reminders?.clockSummary ?: try { dashboard.source.reminderSummary() }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { log.warn("Monitoring reminder clocks unavailable", error); null }
        val offers = try { dashboard.onboarding?.reminderOffers(days) }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { log.warn("Monitoring reminder offers unavailable", error); null }
        val html = remindersPageHtml(snapshot, notice = call.request.queryParameters["notice"],
            controls = dashboard.reminders != null, summaryRoot = root, clock = clock, offers = offers, days = days)
        call.respondText(html, ContentType.Text.Html)
    }.hide()
    get("$root/streaks") {
        if (call.blockPublicMonitoring(dashboard.monitoringPort)) return@get
        val html = try {
            streaksPageHtml(dashboard.source.load(), summaryRoot = root)
        } catch (error: Throwable) {
            log.warn("Monitoring snapshot failed", error)
            metricsUnavailableHtml()
        }
        call.respondText(html, ContentType.Text.Html)
    }.hide()
    get("$root/errors") {
        if (call.blockPublicMonitoring(dashboard.monitoringPort)) return@get
        val metrics = try { dashboard.source.load() } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { log.warn("Monitoring snapshot failed", error); null }
        val voice = try { dashboard.voiceAttempts?.report() } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { log.warn("Voice attempt snapshot failed", error); null }
        val html = errorsPageHtml(metrics, voice, summaryRoot = root)
        call.respondText(html, ContentType.Text.Html)
    }.hide()
    get("$root/history") {
        if (call.blockPublicMonitoring(dashboard.monitoringPort)) return@get
        call.response.headers.append("Cache-Control", "no-store")
        val chatId = call.request.queryParameters["chatId"]?.toLongOrNull()
        val attemptId = call.request.queryParameters["attemptId"]?.takeIf {
            runCatching { UUID.fromString(it) }.isSuccess
        }
        val jobId = call.request.queryParameters["jobId"]?.takeIf { it.isNotBlank() && it.length <= 100 }
        val from = call.request.queryParameters["from"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val to = call.request.queryParameters["to"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val before = call.request.queryParameters["before"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val beforeId = call.request.queryParameters["beforeId"]?.takeIf { it.length <= 160 }
        val browsing = chatId == null && attemptId == null && jobId == null
        val search = call.request.queryParameters["q"]?.trim()?.take(80).orEmpty()
        val beforeChatAt = call.request.queryParameters["beforeChatAt"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val beforeChatId = call.request.queryParameters["beforeChatId"]?.toLongOrNull()
        var historyError = false
        val rows = try { if (browsing) emptyList() else dashboard.audit?.list(chatId, attemptId, jobId, from, to, before, beforeId, 101).orEmpty() }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { log.warn("Interaction history read failed", error); historyError = true; emptyList() }
        val chats = try { if (browsing) dashboard.audit?.recentChats(search, beforeChatAt, beforeChatId).orEmpty() else emptyList() }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { log.warn("Recent chats read failed", error); historyError = true; emptyList() }
        val selectedChat = try { chatId?.let { dashboard.audit?.findChat(it) } }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { log.warn("Chat identity read failed", error); null }
        call.respondText(interactionHistoryHtml(chatId, attemptId, jobId, from, to, rows,
            dashboard.audit != null && !historyError, dashboard.audit?.writeFailures?.get() ?: 0,
            dashboard.audit?.pendingWrites?.get() ?: 0, chats, selectedChat, search, browsing), ContentType.Text.Html)
    }.hide()
    get("$root/history/audio/{attemptId}") {
        if (call.blockPublicMonitoring(dashboard.monitoringPort)) return@get
        call.response.headers.append("Cache-Control", "no-store")
        val attemptId = call.parameters["attemptId"].orEmpty()
        val reply = call.request.queryParameters["role"] == "reply"
        val stored = dashboard.audit?.readAudio(attemptId, reply)
        if (stored == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            val (bytes, contentType) = stored
            call.response.headers.append("Accept-Ranges", "bytes")
            val range = call.request.headers["Range"]
            val slice = parseAudioRange(range, bytes.size)
            when {
                range != null && slice == null -> {
                    call.response.headers.append("Content-Range", "bytes */${bytes.size}")
                    call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
                }
                slice != null -> {
                    call.response.headers.append("Content-Range", "bytes ${slice.first}-${slice.last}/${bytes.size}")
                    call.respondBytes(bytes.copyOfRange(slice.first, slice.last + 1),
                        ContentType.parse(contentType), HttpStatusCode.PartialContent)
                }
                else -> call.respondBytes(bytes, ContentType.parse(contentType))
            }
        }
    }.hide()
    get("$root/onboarding-campaign") {
        if (call.blockPublicMonitoring(dashboard.monitoringPort)) return@get
        val html = try {
            legacyCampaignPageHtml(
                dashboard.campaign?.status() ?: LegacyCampaignStatus(),
                call.request.queryParameters["notice"],
                controls = dashboard.campaign != null,
                summaryRoot = root,
            )
        } catch (error: Throwable) {
            log.warn("Campaign status failed", error)
            metricsUnavailableHtml()
        }
        call.respondText(html, ContentType.Text.Html)
    }.hide()
    post("$root/reminders/send") {
        if (call.blockPublicMonitoring(dashboard.monitoringPort)) return@post
        val admin = dashboard.reminders ?: run {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        val notice = if (admin.startAll()) NOTICE_STARTED else NOTICE_BUSY
        call.respondRedirect("$root/reminders?notice=$notice")
    }.hide()
    post("$root/reminders/test") {
        if (call.blockPublicMonitoring(dashboard.monitoringPort)) return@post
        val admin = dashboard.reminders ?: run {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        val parameters = call.receiveParameters()
        val chatId = parameters["chatId"]?.trim()?.toLongOrNull()
        val templateId = parameters["template"]?.trim()?.takeIf { it.isNotEmpty() && it != "today" }
        if (chatId == null || (templateId != null && reminderTemplateById(templateId) == null)) {
            call.respondRedirect("$root/reminders?notice=$NOTICE_TEST_INVALID")
            return@post
        }
        val sent = try {
            admin.sendTest(chatId, templateId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Test reminder failed for tg-{}", chatId, error)
            false
        }
        call.respondRedirect("$root/reminders?notice=${if (sent) NOTICE_TEST_SENT else NOTICE_TEST_FAILED}")
    }.hide()
    post("$root/onboarding-campaign/send") {
        if (call.blockPublicMonitoring(dashboard.monitoringPort)) return@post
        val admin = dashboard.campaign ?: run {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        val status = admin.status()
        val notice = when {
            !status.ready -> "not-ready"
            status.remaining == 0 -> "busy"
            admin.startAll() -> "started"
            else -> "busy"
        }
        call.respondRedirect("$root/onboarding-campaign?notice=$notice")
    }.hide()
    post("$root/onboarding-campaign/test") {
        if (call.blockPublicMonitoring(dashboard.monitoringPort)) return@post
        val admin = dashboard.campaign ?: run {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        val chatId = call.receiveParameters()["chatId"]?.trim()?.toLongOrNull()
        if (chatId == null || chatId <= 0) {
            call.respondRedirect("$root/onboarding-campaign?notice=test-invalid")
            return@post
        }
        val sent = admin.sendTest(chatId)
        call.respondRedirect("$root/onboarding-campaign?notice=${if (sent) "test-sent" else "test-failed"}")
    }.hide()
}

internal fun parseAudioRange(header: String?, size: Int): IntRange? {
    if (header == null || size <= 0) return null
    val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header) ?: return null
    val first = match.groupValues[1]
    val last = match.groupValues[2]
    if (first.isEmpty() && last.isEmpty()) return null
    val start: Long
    val end: Long
    if (first.isEmpty()) {
        val suffix = last.toLongOrNull()?.takeIf { it > 0 } ?: return null
        start = (size.toLong() - suffix).coerceAtLeast(0)
        end = size.toLong() - 1
    } else {
        start = first.toLongOrNull() ?: return null
        end = if (last.isEmpty()) size.toLong() - 1 else last.toLongOrNull() ?: return null
    }
    if (start >= size || end < start) return null
    return start.toInt()..minOf(end, size.toLong() - 1).toInt()
}

private fun interactionHistoryHtml(
    chatId: Long?, attemptId: String?, jobId: String?, from: Instant?, to: Instant?,
    rows: List<InteractionEvent>, enabled: Boolean, writeFailures: Long, pendingWrites: Long,
    chats: List<InteractionChat>, selectedChat: InteractionChat?, search: String, browsing: Boolean,
): String {
    val page = rows.take(100)
    val items = page.joinToString("\n") { event ->
        val content = event.content?.let { "<p>${escapeHtml(it)}</p>" }
            ?: if (event.receivedAt.isBefore(java.time.Instant.now().minusSeconds(7L * 86_400L)))
                "<p>Содержимое удалено по сроку хранения</p>" else ""
        val audio = if (event.kind == "audio" && event.audioFile != null &&
            event.receivedAt.isAfter(java.time.Instant.now().minusSeconds(7L * 86_400L))) {
            val role = if (event.audioFile.contains("-reply.")) "?role=reply" else ""
            "<audio controls preload=\"none\" src=\"$MONITORING_PATH/history/audio/${escapeHtml(event.attemptId.orEmpty())}$role\"></audio>"
        } else ""
        val voice = if (event.kind == "voice") {
            "<small> · итог: ${escapeHtml(event.voiceOutcome ?: "ещё обрабатывается или итог не записан")}" +
                " · этап: ${escapeHtml(event.voiceStage.orEmpty())}" +
                " · причина: ${escapeHtml(event.voiceReason.orEmpty())}</small>"
        } else ""
        val logs = event.attemptId?.let { id ->
            val expression = Json.encodeToString("{host=~\"cmp|ai\"} |= \"attempt_id=$id\"")
            val start = event.occurredAt.minusSeconds(300).toEpochMilli()
            val end = event.occurredAt.plusSeconds(900).toEpochMilli()
            val panes = """{"A":{"datasource":"speaky-loki","queries":[{"refId":"A","datasource":{"uid":"speaky-loki","type":"loki"},"expr":$expression}],"range":{"from":"$start","to":"$end"}}}"""
            " <a href=\"/explore?panes=${URLEncoder.encode(panes, StandardCharsets.UTF_8)}&amp;schemaVersion=1&amp;orgId=1\">Логи</a>"
        }.orEmpty()
        "<li><strong>${escapeHtml(event.occurredAt.toString())} · ${escapeHtml(event.direction)} · " +
            "${escapeHtml(event.kind)} · ${escapeHtml(event.status)}</strong>" +
            "<small> attempt_id=${escapeHtml(event.attemptId.orEmpty())} " +
            "job=${escapeHtml(event.jobId.orEmpty())}</small>$logs$voice$content$audio</li>"
    }
    val next = if (rows.size > 100) page.last().let { last ->
        val query = listOfNotNull(
            chatId?.let { "chatId" to it.toString() },
            attemptId?.let { "attemptId" to it }, jobId?.let { "jobId" to it },
            from?.let { "from" to it.toString() }, to?.let { "to" to it.toString() },
            "before" to last.occurredAt.toString(), "beforeId" to last.id,
        ).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}" }
        "<p><a href=\"$MONITORING_PATH/history?$query\">Следующие события</a></p>"
    } else ""
    val chatList = if (browsing && enabled) recentChatsHtml(chats, search) else ""
    val chatTitle = selectedChat?.let { chat ->
        val name = chat.displayName?.let(::escapeHtml) ?: chat.username?.let { "@${escapeHtml(it)}" } ?: "Чат"
        "<h2>$name · ${chat.chatId}</h2>"
    }.orEmpty()
    return """
        <!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="robots" content="noindex">
        <title>Speaky · история</title>${pageStyle()}</head><body>
        <h1>История взаимодействий</h1>${adminTabs("$MONITORING_PATH/history", MONITORING_PATH)}
        $chatList
        ${if (browsing) "<h2>Поиск по идентификатору</h2>" else "<p><a href=\"$MONITORING_PATH/history\">← Все чаты</a></p>"}
        $chatTitle
        <form method="get" action="$MONITORING_PATH/history"><label>Telegram chat ID
        <input name="chatId" type="number" value="${chatId ?: ""}"></label>
        <label>Attempt ID <input name="attemptId" value="${escapeHtml(attemptId.orEmpty())}"></label>
        <label>Job ID <input name="jobId" value="${escapeHtml(jobId.orEmpty())}"></label>
        <label>От (UTC ISO 8601) <input name="from" value="${escapeHtml(from?.toString().orEmpty())}"></label>
        <label>До (UTC ISO 8601) <input name="to" value="${escapeHtml(to?.toString().orEmpty())}"></label>
        <button type="submit">Найти</button></form>
        <p>Аудио, расшифровки и тексты хранятся 7 суток; технические события — 30 дней.
        Доставка означает приём сообщения Telegram, а не прослушивание.</p>
        ${if (writeFailures > 0) "<p>Ошибок записи журнала с последнего запуска: $writeFailures. История может быть неполной.</p>" else ""}
        ${if (pendingWrites > 0) "<p>Событий в очереди записи: $pendingWrites.</p>" else ""}
        ${if (!enabled) "<p>Хранилище истории недоступно.</p>" else if (chatId != null && rows.isEmpty()) "<p>Событий нет.</p>" else ""}
        ${if (browsing) "" else "<ol>$items</ol>$next"}</body></html>
    """.trimIndent()
}

private val chatTimeFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    .withZone(ZoneId.of("Europe/Moscow"))

private fun recentChatsHtml(chats: List<InteractionChat>, search: String): String {
    val page = chats.take(50)
    val rows = page.joinToString("\n") { chat ->
        val label = chat.displayName?.let(::escapeHtml) ?: "Без имени"
        val username = chat.username?.let { "@${escapeHtml(it)}" } ?: "—"
        "<tr><td><a href=\"$MONITORING_PATH/history?chatId=${chat.chatId}\">$label</a></td>" +
            "<td>$username</td><td><a href=\"$MONITORING_PATH/history?chatId=${chat.chatId}\">${chat.chatId}</a></td>" +
            "<td>${chatTimeFormat.format(chat.lastIncomingAt)} МСК</td></tr>"
    }.ifEmpty { "<tr><td colspan=\"4\">${if (search.isBlank()) "Чатов пока нет." else "По запросу чаты не найдены."}</td></tr>" }
    val next = if (chats.size > 50) page.last().let { last ->
        val query = listOf(
            "q" to search,
            "beforeChatAt" to last.lastIncomingAt.toString(),
            "beforeChatId" to last.chatId.toString(),
        ).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}" }
        "<p><a href=\"$MONITORING_PATH/history?$query\">Следующие чаты</a></p>"
    } else ""
    return """
        <h2>Последние чаты</h2>
        <form method="get" action="$MONITORING_PATH/history" class="inline">
          <label>Имя, @username или ID <input name="q" value="${escapeHtml(search)}"></label>
          <button type="submit">Найти чат</button>
        </form>
        <p class="meta">Чаты с входящими сообщениями за последние 30 дней. Последнее обращение — по Москве.</p>
        <table><thead><tr><th>Имя</th><th>Username</th><th>Chat ID</th><th>Последнее обращение</th></tr></thead>
        <tbody>$rows</tbody></table>$next
    """.trimIndent()
}

private suspend fun ApplicationCall.blockPublicMonitoring(monitoringPort: Int): Boolean {
    if (monitoringRequestAllowed(request.local.localPort, monitoringPort)) {
        return false
    }
    respond(HttpStatusCode.NotFound)
    return true
}

internal fun monitoringReportHtml(snapshot: MetricsSnapshot): String {
    val columns = snapshot.v2?.clients.orEmpty()
    val columnsHtml = if (columns.isEmpty()) {
        "<p class=\"meta\">Новый разрез ещё пуст. Старые общие счётчики сюда не подставляются.</p>"
    } else {
        columns.joinToString("\n") { clientColumnHtml(it) }
    }
    return """
        <!doctype html>
        <html lang="ru">
        <head>
        <meta charset="utf-8">
        <meta name="robots" content="noindex">
        <title>Speaky monitoring</title>
        ${pageStyle()}
        </head>
        <body>
        <h1>Speaky</h1>
        ${adminTabs(MONITORING_PATH, MONITORING_PATH)}
        <p class="meta">${escapeHtml(snapshot.day)} · ${escapeHtml(snapshot.timezone)}. Деньги — суммы провайдера. Пустое место остаётся прочерком.</p>
        <h2>Сейчас</h2>
        <p class="meta">TPM и TPS за 60 секунд. Этот датчик общий, без клиента и платформы.</p>
        <dl>
        ${card("TPM (60 с)", snapshot.tpm.toString())}
        ${card("TPS (60 с)", formatTps(snapshot.tps))}
        </dl>
        $columnsHtml
        ${telegramJourneyHtml(snapshot)}
        <h2>Telegram · воронка</h2>
        <p class="meta">Первый start, голос и обмен. Только Telegram. Activated за 7 дней: ${snapshot.activated7}</p>
        <table>
        <thead><tr><th>День</th><th>Start</th><th>Activated</th><th>Engaged</th><th>Returned</th></tr></thead>
        <tbody>${funnelDayRows(snapshot)}</tbody>
        </table>
        <h2>Telegram · источники</h2>
        <table>
        <thead><tr><th>Источник</th><th>Start</th><th>Activated</th><th>Engaged</th><th>Returned</th></tr></thead>
        <tbody>${funnelSourceRows(snapshot)}</tbody>
        </table>
        <h2>Telegram · исправления</h2>
        ${correctionReport(snapshot)}
        </body>
        </html>
    """.trimIndent()
}

private fun telegramJourneyHtml(snapshot: MetricsSnapshot): String {
    val journey = snapshot.v2?.telegramJourney ?: return ""
    val since = if (journey.since.isBlank()) "Учёт ещё не начался." else "Учёт с ${escapeHtml(journey.since)}."
    return """
        <h2>Telegram · путь от Start до голосовых</h2>
        <p class="meta">$since Накопительные числа пользователей с момента включения учёта, без восстановления прошлых сообщений. Пороги голосовых включают друг друга.</p>
        <dl>
        ${card("Только Start, без ГС", journey.onlyStart.toString())}
        ${card("≥1 ГС", (journey.atLeast["1"] ?: 0).toString())}
        ${card("≥3 ГС", (journey.atLeast["3"] ?: 0).toString())}
        ${card("≥5 ГС", (journey.atLeast["5"] ?: 0).toString())}
        ${card("≥10 ГС", (journey.atLeast["10"] ?: 0).toString())}
        ${card("20+ ГС", (journey.atLeast["20"] ?: 0).toString())}
        </dl>
    """.trimIndent()
}

private fun clientColumnHtml(column: MetricsV2Client): String {
    val title = when (column.client) {
        "telegram" -> "Telegram"
        "android" -> "Android"
        "ios" -> "iOS"
        "desktop" -> "desktop"
        else -> column.client
    }
    val money = if (column.costCurrency.isBlank()) "—" else formatProviderCost(column.costMicro, column.costCurrency)
    val chats = if (column.chats.isEmpty()) {
        "<tr><td colspan=\"2\">Пока нет ходов.</td></tr>"
    } else {
        column.chats.joinToString("\n") { chat ->
            "<tr><td>${escapeHtml(chat.session)}</td><td>${chat.turns}</td></tr>"
        }
    }
    val actions = column.actions.entries.sortedBy { it.key }.joinToString("\n") { (name, count) ->
        "<tr><td>${escapeHtml(name)}</td><td>$count</td></tr>"
    }.ifEmpty { "<tr><td colspan=\"2\">—</td></tr>" }
    val errors = column.errors.entries.sortedBy { it.key }.joinToString("\n") { (name, count) ->
        "<tr><td>${escapeHtml(name)}</td><td>$count</td></tr>"
    }.ifEmpty { "<tr><td colspan=\"2\">—</td></tr>" }
    return """
        <h2>${escapeHtml(title)}</h2>
        <h3>Списано</h3>
        <dl>
        ${card("Списано", money)}
        ${card("Вызовы с ответом", column.calls.toString())}
        </dl>
        <h3>Токены</h3>
        <dl>
        ${card("Prompt", column.promptTokens.toString())}
        ${card("Completion", column.completionTokens.toString())}
        ${card("Звонок, текст in", column.realtimeInText.toString())}
        ${card("Звонок, аудио in", column.realtimeInAudio.toString())}
        ${card("Звонок, текст out", column.realtimeOutText.toString())}
        ${card("Звонок, аудио out", column.realtimeOutAudio.toString())}
        ${card("Звонок, кэш текст", column.realtimeCachedText.toString())}
        ${card("Звонок, кэш аудио", column.realtimeCachedAudio.toString())}
        </dl>
        <h3>Объём</h3>
        <dl>
        ${card("Секунды STT", formatSeconds(column.sttSeconds))}
        ${card("Символы TTS", column.ttsChars.toString())}
        ${card("Ходы", column.turns.toString())}
        ${card("DAU", column.dau.toString())}
        </dl>
        <h3>Чаты</h3>
        <table>
        <thead><tr><th>Сессия</th><th>Ходы</th></tr></thead>
        <tbody>$chats</tbody>
        </table>
        <h3>Частота</h3>
        <table>
        <thead><tr><th>Действие</th><th>Раз</th></tr></thead>
        <tbody>$actions</tbody>
        </table>
        <h3>Ошибки сервисов</h3>
        <table>
        <thead><tr><th>Сервис и исход</th><th>Раз</th></tr></thead>
        <tbody>$errors</tbody>
        </table>
    """.trimIndent()
}

private fun formatProviderCost(micro: Long, currency: String): String =
    String.format(Locale.US, "%.6f %s", micro / 1_000_000.0, currency)
