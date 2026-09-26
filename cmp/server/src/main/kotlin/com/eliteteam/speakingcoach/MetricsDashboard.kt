package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.ai.MetricsChat
import com.eliteteam.speakingcoach.ai.MetricsSnapshot
import com.eliteteam.speakingcoach.telegram.ReminderAdmin
import com.eliteteam.speakingcoach.telegram.reminderTemplateById
import io.ktor.http.ContentType
import io.ktor.http.Cookie
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
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val METRICS_COOKIE = "metrics_session"
internal const val METRICS_PATH = "/admin/metrics"
internal const val REMINDERS_PATH = "/admin/metrics/reminders"
internal const val STREAKS_PATH = "/admin/metrics/streaks"
internal const val NOTICE_STARTED = "started"
internal const val NOTICE_BUSY = "busy"
internal const val NOTICE_TEST_SENT = "test-sent"
internal const val NOTICE_TEST_FAILED = "test-failed"
internal const val NOTICE_TEST_INVALID = "test-invalid"
private const val TODAY_TEMPLATE = "today"
private const val METRICS_COOKIE_PAYLOAD = "metrics-ok"
private const val METRICS_COOKIE_MAX_AGE_SECONDS = 12 * 60 * 60

internal fun interface MetricsSource {
    suspend fun load(): MetricsSnapshot
}

internal class MetricsDashboard(
    val password: String,
    val source: MetricsSource,
    val secureCookie: Boolean,
    val reminders: ReminderAdmin? = null,
)

@OptIn(ExperimentalKtorApi::class)
internal fun Route.installMetricsDashboard(dashboard: MetricsDashboard) {
    val log = LoggerFactory.getLogger("MetricsDashboard")
    get("/admin/metrics") {
        if (!call.hasMetricsSession(dashboard.password)) {
            call.respondText(metricsLoginHtml(), ContentType.Text.Html)
            return@get
        }
        val html = try {
            metricsReportHtml(dashboard.source.load())
        } catch (error: Throwable) {
            log.warn("Metrics snapshot failed", error)
            metricsUnavailableHtml()
        }
        call.respondText(html, ContentType.Text.Html)
    }.hide()
    get(REMINDERS_PATH) {
        if (!call.hasMetricsSession(dashboard.password)) {
            call.respondText(metricsLoginHtml(), ContentType.Text.Html)
            return@get
        }
        val html = try {
            remindersPageHtml(
                dashboard.source.load(),
                notice = call.request.queryParameters["notice"],
                controls = dashboard.reminders != null,
            )
        } catch (error: Throwable) {
            log.warn("Metrics snapshot failed", error)
            metricsUnavailableHtml()
        }
        call.respondText(html, ContentType.Text.Html)
    }.hide()
    get(STREAKS_PATH) {
        if (!call.hasMetricsSession(dashboard.password)) {
            call.respondText(metricsLoginHtml(), ContentType.Text.Html)
            return@get
        }
        val html = try {
            streaksPageHtml(dashboard.source.load())
        } catch (error: Throwable) {
            log.warn("Metrics snapshot failed", error)
            metricsUnavailableHtml()
        }
        call.respondText(html, ContentType.Text.Html)
    }.hide()
    post("/admin/metrics/login") {
        val provided = call.receiveParameters()["password"].orEmpty()
        if (!metricsPasswordMatches(dashboard.password, provided)) {
            call.respondText(metricsLoginHtml(rejected = true), ContentType.Text.Html, HttpStatusCode.Unauthorized)
            return@post
        }
        call.response.cookies.append(metricsSessionCookie(dashboard.password, dashboard.secureCookie))
        call.respondRedirect("/admin/metrics")
    }.hide()
    post("/admin/metrics/reminders/send") {
        val admin = dashboard.reminders
        if (!call.hasMetricsSession(dashboard.password) || admin == null) {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        val notice = if (admin.startAll()) NOTICE_STARTED else NOTICE_BUSY
        call.respondRedirect("$REMINDERS_PATH?notice=$notice")
    }.hide()
    post("/admin/metrics/reminders/test") {
        val admin = dashboard.reminders
        if (!call.hasMetricsSession(dashboard.password) || admin == null) {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        val parameters = call.receiveParameters()
        val chatId = parameters["chatId"]?.trim()?.toLongOrNull()
        val templateId = parameters["template"]?.trim()?.takeIf { it.isNotEmpty() && it != TODAY_TEMPLATE }
        if (chatId == null || (templateId != null && reminderTemplateById(templateId) == null)) {
            call.respondRedirect("$REMINDERS_PATH?notice=$NOTICE_TEST_INVALID")
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
        call.respondRedirect("$REMINDERS_PATH?notice=${if (sent) NOTICE_TEST_SENT else NOTICE_TEST_FAILED}")
    }.hide()
}

internal fun metricsSessionToken(password: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(METRICS_COOKIE_PAYLOAD.toByteArray(Charsets.UTF_8)).toHex()
}

internal fun metricsPasswordMatches(expected: String, provided: String): Boolean {
    return MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        provided.toByteArray(Charsets.UTF_8),
    )
}

private fun ApplicationCall.hasMetricsSession(password: String): Boolean {
    val cookie = request.cookies[METRICS_COOKIE]
    if (cookie.isNullOrBlank()) {
        return false
    }
    val expected = metricsSessionToken(password).toByteArray(Charsets.UTF_8)
    return MessageDigest.isEqual(expected, cookie.toByteArray(Charsets.UTF_8))
}

private fun metricsSessionCookie(password: String, secure: Boolean): Cookie = Cookie(
    name = METRICS_COOKIE,
    value = metricsSessionToken(password),
    maxAge = METRICS_COOKIE_MAX_AGE_SECONDS,
    path = "/admin/metrics",
    secure = secure,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Strict"),
)

private fun metricsLoginHtml(rejected: Boolean = false): String {
    val message = if (rejected) "<p>Неверный пароль.</p>" else ""
    return """
        <!doctype html>
        <html lang="ru">
        <head>
        <meta charset="utf-8">
        <meta name="robots" content="noindex">
        <title>Speaky metrics</title>
        ${pageStyle()}
        </head>
        <body>
        <h1>Speaky</h1>
        $message
        <form method="post" action="/admin/metrics/login">
        <label>Пароль <input type="password" name="password" autocomplete="current-password" autofocus></label>
        <button type="submit">Войти</button>
        </form>
        </body>
        </html>
    """.trimIndent()
}

private fun metricsUnavailableHtml(): String = """
    <!doctype html>
    <html lang="ru">
    <head>
    <meta charset="utf-8">
    <meta name="robots" content="noindex">
    <title>Speaky metrics</title>
    ${pageStyle()}
    </head>
    <body>
    <h1>Speaky</h1>
    <p>Сводка сейчас недоступна.</p>
    </body>
    </html>
""".trimIndent()

internal fun metricsReportHtml(snapshot: MetricsSnapshot): String {
    val rubPerTurn = if (snapshot.ratesConfigured) formatRub(snapshot.rubPerTurn) else "—"
    val rubPerDau = if (snapshot.ratesConfigured) formatRub(snapshot.rubPerDau) else "—"
    return """
        <!doctype html>
        <html lang="ru">
        <head>
        <meta charset="utf-8">
        <meta name="robots" content="noindex">
        <title>Speaky metrics</title>
        ${pageStyle()}
        </head>
        <body>
        <h1>Speaky</h1>
        ${adminTabs(METRICS_PATH)}
        <p class="meta">${escapeHtml(snapshot.day)} · ${escapeHtml(snapshot.timezone)}. Счёт с момента выкладки.</p>
        <dl>
        ${card("Токены prompt", snapshot.promptTokens.toString())}
        ${card("Токены completion", snapshot.completionTokens.toString())}
        ${card("TPM (60 с)", snapshot.tpm.toString())}
        ${card("TPS (60 с)", formatTps(snapshot.tps))}
        ${card("Ходы за день", snapshot.turns.toString())}
        ${card("DAU", snapshot.dau.toString())}
        ${card("Секунды STT", formatSeconds(snapshot.sttSeconds))}
        ${card("Символы TTS", snapshot.ttsChars.toString())}
        ${card("₽ на ход", rubPerTurn)}
        ${card("₽ на DAU", rubPerDau)}
        </dl>
        <h2>Воронка</h2>
        <p class="meta">Activated за 7 дней: ${snapshot.activated7}</p>
        <table>
        <thead><tr><th>День</th><th>Start</th><th>Activated</th><th>Engaged</th><th>Returned</th></tr></thead>
        <tbody>
        ${funnelDayRows(snapshot)}
        </tbody>
        </table>
        <h2>Источники</h2>
        <table>
        <thead><tr><th>Источник</th><th>Start</th><th>Activated</th><th>Engaged</th><th>Returned</th></tr></thead>
        <tbody>
        ${funnelSourceRows(snapshot)}
        </tbody>
        </table>
        <h2>Чаты</h2>
        <table>
        <thead><tr><th>Чат</th><th>Пользователь</th><th>Ходы</th><th>Последний ход</th><th>Напоминание</th><th>Игнор подряд</th></tr></thead>
        <tbody>
        ${chatRows(snapshot)}
        </tbody>
        </table>
        </body>
        </html>
    """.trimIndent()
}

private fun funnelDayRows(snapshot: MetricsSnapshot): String {
    if (snapshot.funnelDays.isEmpty()) {
        return "<tr><td colspan=\"5\">Пока нет данных.</td></tr>"
    }
    return snapshot.funnelDays.joinToString("\n") { day ->
        "<tr><td>${escapeHtml(day.day)}</td><td>${day.start}</td><td>${day.activated}</td><td>${day.engaged}</td><td>${day.returned}</td></tr>"
    }
}

private fun funnelSourceRows(snapshot: MetricsSnapshot): String {
    if (snapshot.funnelSources.isEmpty()) {
        return "<tr><td colspan=\"5\">Пока нет источников.</td></tr>"
    }
    return snapshot.funnelSources.joinToString("\n") { source ->
        "<tr><td>${escapeHtml(source.source)}</td><td>${source.start}</td><td>${source.activated}</td><td>${source.engaged}</td><td>${source.returned}</td></tr>"
    }
}

private fun chatRows(snapshot: MetricsSnapshot): String {
    if (snapshot.chats.isEmpty()) {
        return "<tr><td colspan=\"6\">Пока нет ходов.</td></tr>"
    }
    return snapshot.chats.joinToString("\n") { chat ->
        "<tr><td>${escapeHtml(chat.sessionId)}</td><td>${escapeHtml(chatUser(chat))}</td><td>${chat.turns}</td>" +
            "<td>${escapeHtml(chat.lastAt)}</td><td>${escapeHtml(chat.lastReminderAt?.let(::shortTime) ?: "—")}</td><td>${chat.reminderIgnored}</td></tr>"
    }
}

private fun chatUser(chat: MetricsChat): String {
    val parts = listOfNotNull(
        chat.username?.takeIf { it.isNotBlank() }?.let { "@$it" },
        chat.name?.takeIf { it.isNotBlank() },
    )
    return parts.joinToString(" · ").ifEmpty { "—" }
}

internal fun card(label: String, value: String): String {
    return "<div class=\"card\"><dt>${escapeHtml(label)}</dt><dd>${escapeHtml(value)}</dd></div>"
}

internal fun adminTabs(active: String): String {
    val tabs = listOf(METRICS_PATH to "Сводка", REMINDERS_PATH to "Напоминания", STREAKS_PATH to "Стрики")
    val links = tabs.joinToString("") { (path, label) ->
        val current = if (path == active) " aria-current=\"page\"" else ""
        "<a href=\"$path\"$current>$label</a>"
    }
    return "<nav class=\"tabs\">$links</nav>"
}

internal fun pageStyle(): String = """
    <style>
    body { font: 16px/1.45 ui-sans-serif, system-ui, sans-serif; margin: 32px auto; max-width: 960px; color: #1c1917; background: #fafaf9; }
    h1 { font-size: 1.5rem; font-weight: 650; margin-bottom: 0.25rem; }
    h2 { font-size: 1.1rem; margin-top: 2rem; }
    .meta { color: #57534e; }
    dl { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 12px; padding: 0; }
    .card { background: #fff; border: 1px solid #e7e5e4; border-radius: 12px; padding: 12px 14px; }
    dt { color: #57534e; font-size: 0.8rem; }
    dd { margin: 4px 0 0; font-size: 1.25rem; font-weight: 650; }
    table { width: 100%; border-collapse: collapse; background: #fff; }
    th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #e7e5e4; }
    form { display: flex; flex-direction: column; gap: 12px; max-width: 320px; }
    form.inline { flex-direction: row; flex-wrap: wrap; align-items: end; max-width: none; margin: 12px 0; }
    input, button, select { font: inherit; padding: 8px 10px; }
    .notice { background: #ecfdf5; border: 1px solid #a7f3d0; border-radius: 12px; padding: 10px 14px; }
    .notice.warn { background: #fef2f2; border-color: #fecaca; }
    .template { color: #57534e; font-size: 0.85rem; }
    .tabs { display: flex; gap: 4px; border-bottom: 1px solid #e7e5e4; margin: 12px 0 16px; }
    .tabs a { padding: 8px 14px; color: #57534e; text-decoration: none; border-bottom: 2px solid transparent; margin-bottom: -1px; }
    .tabs a[aria-current="page"] { color: #1c1917; font-weight: 650; border-bottom-color: #1c1917; }
    </style>
""".trimIndent()

internal fun escapeHtml(text: String): String = buildString {
    for (char in text) {
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(char)
        }
    }
}

private fun formatTps(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun formatSeconds(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun formatRub(value: Double?): String {
    if (value == null) {
        return "—"
    }
    return String.format(Locale.US, "%.2f", value)
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
