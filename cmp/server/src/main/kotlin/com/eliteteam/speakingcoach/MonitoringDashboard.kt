package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.analytics.VoiceAttemptRecorder
import com.eliteteam.speakingcoach.ai.LegacyCampaignStatus
import com.eliteteam.speakingcoach.ai.MetricsSnapshot
import com.eliteteam.speakingcoach.ai.MetricsV2Client
import com.eliteteam.speakingcoach.telegram.LegacyCampaignAdmin
import com.eliteteam.speakingcoach.telegram.ReminderAdmin
import com.eliteteam.speakingcoach.telegram.reminderTemplateById
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.Locale

internal const val MONITORING_PATH = "/admin/monitoring"

internal fun monitoringRequestAllowed(localPort: Int, monitoringPort: Int): Boolean =
    monitoringPort == 0 || localPort == monitoringPort

internal class MonitoringDashboard(
    val source: MetricsSource,
    val reminders: ReminderAdmin? = null,
    val campaign: LegacyCampaignAdmin? = null,
    val monitoringPort: Int = 0,
    val voiceAttempts: VoiceAttemptRecorder? = null,
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
        val html = try {
            remindersPageHtml(
                dashboard.source.load(),
                notice = call.request.queryParameters["notice"],
                controls = dashboard.reminders != null,
                summaryRoot = root,
            )
        } catch (error: Throwable) {
            log.warn("Monitoring snapshot failed", error)
            metricsUnavailableHtml()
        }
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
