package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.analytics.OnboardingAnalytics
import com.eliteteam.speakingcoach.analytics.ReminderOfferSummary
import com.eliteteam.speakingcoach.analytics.VoiceAttemptRecorder
import com.eliteteam.speakingcoach.analytics.CallEventRecorder
import com.eliteteam.speakingcoach.analytics.OnboardingFilter
import com.eliteteam.speakingcoach.analytics.JourneyMode
import com.eliteteam.speakingcoach.analytics.onboardingAgentJson
import com.eliteteam.speakingcoach.ai.MetricsChat
import com.eliteteam.speakingcoach.ai.MetricsSnapshot
import com.eliteteam.speakingcoach.ai.CorrectionMetrics
import com.eliteteam.speakingcoach.ai.LegacyCampaignStatus
import com.eliteteam.speakingcoach.ai.LlmRequestPeriod
import com.eliteteam.speakingcoach.ai.ReminderClockSummary
import com.eliteteam.speakingcoach.telegram.ReminderAdmin
import com.eliteteam.speakingcoach.telegram.LegacyCampaignAdmin
import com.eliteteam.speakingcoach.telegram.reminderTemplateById
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.header
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val METRICS_COOKIE = "metrics_session"
internal const val METRICS_PATH = "/admin/metrics"
internal const val REMINDERS_PATH = "/admin/metrics/reminders"
internal const val STREAKS_PATH = "/admin/metrics/streaks"
internal const val ERRORS_PATH = "/admin/metrics/errors"
internal const val ONBOARDING_ANALYTICS_PATH = "/admin/metrics/onboarding"
internal const val ONBOARDING_AGENT_PATH = "$ONBOARDING_ANALYTICS_PATH/agent.json"
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
    suspend fun llmRange(range: LlmRange): LlmRequestPeriod? = null
    suspend fun reminderSummary(): ReminderClockSummary? = null
    suspend fun callFeedback(offset: Int, limit: Int): com.eliteteam.speakingcoach.ai.CallFeedbackList? = null
}

internal data class LlmRange(val from: LocalDate, val to: LocalDate)

internal class MetricsDashboard(
    val password: String,
    val source: MetricsSource,
    val secureCookie: Boolean,
    val reminders: ReminderAdmin? = null,
    val campaign: LegacyCampaignAdmin? = null,
    val onboarding: OnboardingAnalytics? = null,
    val voiceAttempts: VoiceAttemptRecorder? = null,
    val callEvents: CallEventRecorder? = null,
)

@OptIn(ExperimentalKtorApi::class)
internal fun Route.installMetricsDashboard(dashboard: MetricsDashboard) {
    val log = LoggerFactory.getLogger("MetricsDashboard")
    get("/admin/metrics") {
        if (!call.hasMetricsSession(dashboard.password)) {
            call.respondText(metricsLoginHtml(), ContentType.Text.Html)
            return@get
        }
        val range = call.llmRangeOrRespond() ?: return@get
        val html = try {
            val snapshot = dashboard.source.load()
            val llm = try {
                dashboard.source.llmRange(range)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.warn("LLM range unavailable", error)
                null
            }
            metricsReportHtml(snapshot, llm, range)
        } catch (error: Throwable) {
            log.warn("Metrics snapshot failed", error)
            metricsUnavailableHtml()
        }
        call.respondText(html, ContentType.Text.Html)
    }.hide()
    get(REMINDERS_PATH) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (!call.hasMetricsSession(dashboard.password)) {
            call.respondText(metricsLoginHtml(), ContentType.Text.Html)
            return@get
        }
        val days = call.request.queryParameters["days"]?.toIntOrNull()?.takeIf { it == 7 || it == 30 } ?: 7
        val snapshot = try { dashboard.source.load() }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { log.warn("Reminder metrics unavailable", error); null }
        val clock = snapshot?.reminders?.clockSummary ?: try { dashboard.source.reminderSummary() }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { log.warn("Reminder clock summary unavailable", error); null }
        val offers: ReminderOfferSummary? = try { dashboard.onboarding?.reminderOffers(days) }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { log.warn("Reminder offers unavailable", error); null }
        val html = remindersPageHtml(snapshot,
            notice = call.request.queryParameters["notice"], controls = dashboard.reminders != null,
            clock = clock, offers = offers, days = days)
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
    get(ERRORS_PATH) {
        if (!call.hasMetricsSession(dashboard.password)) {
            call.respondText(metricsLoginHtml(), ContentType.Text.Html)
            return@get
        }
        val metrics = try { dashboard.source.load() } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { log.warn("Metrics snapshot failed", error); null }
        val voice = try { dashboard.voiceAttempts?.report() } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { log.warn("Voice attempt snapshot failed", error); null }
        val html = errorsPageHtml(metrics, voice)
        call.respondText(html, ContentType.Text.Html)
    }.hide()
    get(CALLS_PATH) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (!call.hasMetricsSession(dashboard.password)) {
            call.respondText(metricsLoginHtml(), ContentType.Text.Html)
            return@get
        }
        val query = call.request.queryParameters
        val days = query["days"]?.toIntOrNull()?.takeIf { it in setOf(1, 7, 30) } ?: 7
        val filter = CallDashboardFilter(
            days = days,
            source = query["source"]?.takeIf { it in setOf("button", "voice") },
            status = query["status"]?.takeIf { it in setOf("open", "closed") },
            failed = query["failed"]?.toBooleanStrictOrNull(),
            offset = query["offset"]?.toIntOrNull()?.coerceIn(0, 10_000) ?: 0,
        )
        val feedbackOffset = query["feedbackOffset"]?.toIntOrNull()?.coerceIn(0, 10_000) ?: 0
        val html = try {
            val firstDay = LocalDate.now(ZoneId.of("Europe/Moscow")).minusDays(29)
            val snapshot = dashboard.callEvents?.snapshot(firstDay.atStartOfDay(ZoneId.of("Europe/Moscow")).toInstant())
            val feedback = try { dashboard.source.callFeedback(feedbackOffset, 25) }
                catch (error: CancellationException) { throw error }
                catch (error: Throwable) { log.warn("First-call feedback unavailable", error); null }
            if (snapshot == null) metricsUnavailableHtml() else
                callDashboardPage(snapshot, filter, memoryOnly = dashboard.callEvents.memoryOnly,
                    feedback = feedback, feedbackOffset = feedbackOffset)
        } catch (error: CancellationException) { throw error }
          catch (error: Throwable) { log.warn("Call dashboard unavailable", error); metricsUnavailableHtml() }
        call.respondText(html, ContentType.Text.Html)
    }.hide()
    get("$CALLS_PATH/{callId}") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (!call.hasMetricsSession(dashboard.password)) {
            call.respondText(metricsLoginHtml(), ContentType.Text.Html)
            return@get
        }
        val callId = call.parameters["callId"].orEmpty()
        if (!Regex("[a-f0-9]{32}").matches(callId)) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        try {
            val events = dashboard.callEvents?.byCall(callId).orEmpty()
            val row = callDashboardRows(com.eliteteam.speakingcoach.analytics.CallEventsSnapshot(events, false, 0))
                .firstOrNull()
            if (row == null) call.respond(HttpStatusCode.NotFound)
            else {
                val zone = ZoneId.of("Europe/Moscow")
                val date = row.opened.at.atZone(zone).toLocalDate()
                val all = dashboard.callEvents?.snapshot(date.atStartOfDay(zone).toInstant())
                val daySpeech = all?.let { callDashboardRows(it).filter { other ->
                    other.opened.chatId == row.opened.chatId && other.opened.at.atZone(zone).toLocalDate() == date
                }.sumOf { it.recognizedSeconds } } ?: row.recognizedSeconds
                call.respondText(callDetailPage(row, daySpeech, truncated = events.size >= 1000), ContentType.Text.Html)
            }
        } catch (error: CancellationException) { throw error }
          catch (error: Throwable) { log.warn("Call detail unavailable", error); call.respondText(metricsUnavailableHtml(), ContentType.Text.Html) }
    }.hide()
    get(LEGACY_CAMPAIGN_PATH) {
        if (!call.hasMetricsSession(dashboard.password)) {
            call.respondText(metricsLoginHtml(), ContentType.Text.Html)
            return@get
        }
        val html = try {
            legacyCampaignPageHtml(
                dashboard.campaign?.status() ?: LegacyCampaignStatus(),
                call.request.queryParameters["notice"], controls = dashboard.campaign != null,
            )
        } catch (error: Throwable) {
            log.warn("Campaign status failed", error)
            metricsUnavailableHtml()
        }
        call.respondText(html, ContentType.Text.Html)
    }.hide()
    get(ONBOARDING_ANALYTICS_PATH) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (!call.hasMetricsSession(dashboard.password)) {
            call.respondText(metricsLoginHtml(), ContentType.Text.Html)
            return@get
        }
        val range = call.llmRangeOrRespond() ?: return@get
        val html = try {
            val report = dashboard.onboarding?.report(filter = onboardingFilter(call.request.queryParameters))
            val reminders = if (report == null) null else try {
                dashboard.source.reminderSummary()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.warn("Reminder summary unavailable", error)
                null
            }
            val llm = if (report == null) null else try {
                dashboard.source.llmRange(range)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.warn("LLM summary unavailable", error)
                null
            }
            onboardingReportHtml(report, reminders, llm, range)
        } catch (error: Throwable) {
            log.warn("Onboarding analytics failed", error)
            metricsUnavailableHtml()
        }
        call.respondText(html, ContentType.Text.Html)
    }.hide()
    get(ONBOARDING_AGENT_PATH) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (!call.hasMetricsSession(dashboard.password)) {
            call.respond(HttpStatusCode.Unauthorized)
            return@get
        }
        val range = call.llmRangeOrRespond() ?: return@get
        val analytics = dashboard.onboarding
        if (analytics == null) {
            call.respondText("{\"error\":\"analytics_unavailable\"}", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            return@get
        }
        try {
            val now = Instant.now()
            val report = analytics.report(now, onboardingFilter(call.request.queryParameters))
            val reminders = try {
                dashboard.source.reminderSummary()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.warn("Reminder summary unavailable", error)
                null
            }
            val llm = try {
                dashboard.source.llmRange(range)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.warn("LLM summary unavailable", error)
                null
            }
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=onboarding-analytics.json")
            call.respondText(onboardingAgentJson(report, now, reminders, llm, range), ContentType.Application.Json)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Onboarding agent export failed", error)
            call.respondText("{\"error\":\"analytics_unavailable\"}", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
        }
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
    post("$LEGACY_CAMPAIGN_PATH/send") {
        val admin = dashboard.campaign
        if (!call.hasMetricsSession(dashboard.password) || admin == null) {
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
        call.respondRedirect("$LEGACY_CAMPAIGN_PATH?notice=$notice")
    }.hide()
    post("$LEGACY_CAMPAIGN_PATH/test") {
        val admin = dashboard.campaign
        if (!call.hasMetricsSession(dashboard.password) || admin == null) {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        val chatId = call.receiveParameters()["chatId"]?.trim()?.toLongOrNull()
        if (chatId == null || chatId <= 0) {
            call.respondRedirect("$LEGACY_CAMPAIGN_PATH?notice=test-invalid")
            return@post
        }
        val sent = admin.sendTest(chatId)
        call.respondRedirect("$LEGACY_CAMPAIGN_PATH?notice=${if (sent) "test-sent" else "test-failed"}")
    }.hide()
}

private fun onboardingFilter(query: Parameters): OnboardingFilter = OnboardingFilter(
    days = query["days"]?.toIntOrNull()?.takeIf { it in 1..90 } ?: 30,
    version = query["version"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,32}")) },
    source = query["source"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) },
    trigger = query["trigger"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,32}")) },
    journeyMode = if (query["journey"] == "repeats") JourneyMode.REPEATS else JourneyMode.PRIMARY,
)

private suspend fun ApplicationCall.llmRangeOrRespond(): LlmRange? {
    val today = LocalDate.now(ZoneId.of("Europe/Moscow"))
    val fromText = request.queryParameters["llmFrom"]
    val toText = request.queryParameters["llmTo"]
    val range = try {
        if (fromText == null && toText == null) {
            LlmRange(today, today)
        } else {
            LlmRange(LocalDate.parse(requireNotNull(fromText)), LocalDate.parse(requireNotNull(toText)))
        }
    } catch (_: Exception) {
        null
    }
    if (range == null || range.to < range.from || range.to > today ||
        java.time.temporal.ChronoUnit.DAYS.between(range.from, range.to) >= 366
    ) {
        respondText("Неверный период LLM", status = HttpStatusCode.BadRequest)
        return null
    }
    return range
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

internal fun metricsUnavailableHtml(): String = """
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

internal fun metricsReportHtml(
    snapshot: MetricsSnapshot, llm: LlmRequestPeriod? = null,
    range: LlmRange = LlmRange(LocalDate.parse(snapshot.day), LocalDate.parse(snapshot.day)),
): String {
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
        <h2>Запросы к LLM</h2>
        ${llmRangeForm(METRICS_PATH, range)}
        <p class="meta">${range.from} — ${range.to} включительно · Europe/Moscow · все пользователи.</p>
        <dl>
        ${card("Запросы к LLM", llm?.requests?.toString() ?: "—")}
        ${card("Ошибки LLM", llm?.failures?.toString() ?: "—")}
        ${card("LLM · онбординг", llm?.byPurpose?.get("onboarding")?.toString() ?: "—")}
        ${card("LLM · ответы", llm?.byPurpose?.get("reply")?.toString() ?: "—")}
        ${card("LLM · исправления", llm?.byPurpose?.get("notes")?.toString() ?: "—")}
        ${card("LLM · review", llm?.byPurpose?.get("session_review")?.toString() ?: "—")}
        </dl>
        <p class="meta">Попытки вызова модели, включая ошибки и повторы приложения. Внутренние повторы SDK могут не учитываться. Вызовы до внедрения счётчика не восстановлены.</p>
        <h2>Остальные метрики сегодня</h2>
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
        <h2>Исправления</h2>
        ${correctionReport(snapshot)}
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

private val correctionLabels = linkedMapOf(
    "shown" to "Показано исправление",
    "empty" to "Ошибок не найдено",
    "filtered" to "Отклонено проверкой",
    "deadline" to "Превышен лимит 8 с",
    "provider_timeout" to "Таймаут провайдера",
    "rate_limit" to "Лимит запросов провайдера",
    "provider_5xx" to "Ошибка провайдера 5xx",
    "provider_4xx" to "Ошибка провайдера 4xx",
    "network" to "Сетевая ошибка",
    "no_choices" to "Ответ без choices",
    "empty_text" to "Пустой текст ответа",
    "invalid_json" to "Невалидный JSON",
    "invalid_schema" to "Неверная структура JSON",
    "token_limit" to "Лимит токенов ответа",
    "other_error" to "Другая ошибка",
)

private val nonfailureCorrectionOutcomes = setOf("shown", "empty", "filtered")

internal fun correctionReport(snapshot: MetricsSnapshot): String {
    val rows = correctionLabels.mapNotNull { (key, label) ->
        snapshot.corrections[key]?.takeIf { it.count > 0 }?.let { Triple(key, label, it) }
    }
    if (rows.isEmpty()) return "<p class=\"meta\">Пока нет данных.</p>"
    val total = rows.sumOf { it.third.count }
    val failures = rows.filter { it.first !in nonfailureCorrectionOutcomes }.sumOf { it.third.count }
    val secondAttempts = rows.sumOf { it.third.secondAttempts }
    val failureRate = String.format(Locale.US, "%.1f%%", failures * 100.0 / total)
    val tableRows = rows.joinToString("\n") { (_, label, metrics) ->
        "<tr><td>${escapeHtml(label)}</td><td>${metrics.count}</td><td>${averageCorrectionMs(metrics)}</td></tr>"
    }
    return """
        <dl>
        ${card("Запросы исправлений", total.toString())}
        ${card("Сбои", failures.toString())}
        ${card("Доля сбоев", failureRate)}
        ${card("Повторная попытка", secondAttempts.toString())}
        </dl>
        <table>
        <thead><tr><th>Исход</th><th>Количество</th><th>Среднее время, мс</th></tr></thead>
        <tbody>$tableRows</tbody>
        </table>
    """.trimIndent()
}

private fun averageCorrectionMs(metrics: CorrectionMetrics): String =
    String.format(Locale.US, "%.0f", metrics.elapsedMs.toDouble() / metrics.count)

internal fun llmRangeForm(action: String, range: LlmRange, hidden: String = ""): String = """
    <form class="inline" method="get" action="$action">
    $hidden
    <label>LLM с <input type="date" name="llmFrom" value="${range.from}" max="${LocalDate.now(ZoneId.of("Europe/Moscow"))}" required></label>
    <label>по <input type="date" name="llmTo" value="${range.to}" max="${LocalDate.now(ZoneId.of("Europe/Moscow"))}" required></label>
    <button type="submit">Показать</button>
    </form>
""".trimIndent()

internal fun funnelDayRows(snapshot: MetricsSnapshot): String {
    if (snapshot.funnelDays.isEmpty()) {
        return "<tr><td colspan=\"5\">Пока нет данных.</td></tr>"
    }
    return snapshot.funnelDays.joinToString("\n") { day ->
        "<tr><td>${escapeHtml(day.day)}</td><td>${day.start}</td><td>${day.activated}</td><td>${day.engaged}</td><td>${day.returned}</td></tr>"
    }
}

internal fun funnelSourceRows(snapshot: MetricsSnapshot): String {
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

internal fun adminTabs(active: String, root: String = "/admin/metrics"): String {
    val tabs = buildList {
        add(root to "Сводка")
        if (root == METRICS_PATH) add(ONBOARDING_ANALYTICS_PATH to "Онбординг")
        if (root == METRICS_PATH) add(CALLS_PATH to "Звонки")
        if (root == METRICS_PATH) add("$MONITORING_PATH/history" to "История")
        add("$root/reminders" to "Напоминания")
        add("$root/streaks" to "Стрики")
        add("$root/errors" to "Ошибки")
        if (root == MONITORING_PATH) add("$root/history" to "История")
        add("$root/onboarding-campaign" to "Onboarding рассылка")
    }
    val links = tabs.joinToString("") { (path, label) ->
        val current = if (path == active) " aria-current=\"page\"" else ""
        val historyLink = root == METRICS_PATH && path == "$MONITORING_PATH/history"
        val href = if (historyLink) "#" else path
        val id = if (historyLink) " id=\"history-tab\"" else ""
        "<a href=\"$href\"$id$current>$label</a>"
    }
    val historyTarget = if (root == METRICS_PATH) """
        <script type="text/javascript">
          document.getElementById('history-tab').href =
            'https://' + window.location.hostname + ':8443$MONITORING_PATH/history';
        </script>
    """.trimIndent() else ""
    return "<nav class=\"tabs\">$links</nav>$historyTarget"
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

internal fun formatTps(value: Double): String = String.format(Locale.US, "%.1f", value)

internal fun formatSeconds(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun formatRub(value: Double?): String {
    if (value == null) {
        return "—"
    }
    return String.format(Locale.US, "%.2f", value)
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
