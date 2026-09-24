package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.ai.MetricsSnapshot
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val METRICS_COOKIE = "metrics_session"
private const val METRICS_COOKIE_PAYLOAD = "metrics-ok"
private const val METRICS_COOKIE_MAX_AGE_SECONDS = 12 * 60 * 60

internal fun interface MetricsSource {
    suspend fun load(): MetricsSnapshot
}

internal class MetricsDashboard(
    val password: String,
    val source: MetricsSource,
    val secureCookie: Boolean,
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
    post("/admin/metrics/login") {
        val provided = call.receiveParameters()["password"].orEmpty()
        if (!metricsPasswordMatches(dashboard.password, provided)) {
            call.respondText(metricsLoginHtml(rejected = true), ContentType.Text.Html, HttpStatusCode.Unauthorized)
            return@post
        }
        call.response.cookies.append(metricsSessionCookie(dashboard.password, dashboard.secureCookie))
        call.respondRedirect("/admin/metrics")
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
        <h2>Чаты</h2>
        <table>
        <thead><tr><th>Чат</th><th>Ходы</th><th>Последний ход</th></tr></thead>
        <tbody>
        ${chatRows(snapshot)}
        </tbody>
        </table>
        </body>
        </html>
    """.trimIndent()
}

private fun chatRows(snapshot: MetricsSnapshot): String {
    if (snapshot.chats.isEmpty()) {
        return "<tr><td colspan=\"3\">Пока нет ходов.</td></tr>"
    }
    return snapshot.chats.joinToString("\n") { chat ->
        "<tr><td>${escapeHtml(chat.sessionId)}</td><td>${chat.turns}</td><td>${escapeHtml(chat.lastAt)}</td></tr>"
    }
}

private fun card(label: String, value: String): String {
    return "<div class=\"card\"><dt>${escapeHtml(label)}</dt><dd>${escapeHtml(value)}</dd></div>"
}

private fun pageStyle(): String = """
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
    input, button { font: inherit; padding: 8px 10px; }
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
