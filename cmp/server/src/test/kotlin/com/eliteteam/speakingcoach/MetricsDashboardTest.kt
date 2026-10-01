package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.analytics.MemoryOnboardingAnalytics
import com.eliteteam.speakingcoach.ai.FunnelDay
import com.eliteteam.speakingcoach.ai.FunnelSource
import com.eliteteam.speakingcoach.ai.MetricsChat
import com.eliteteam.speakingcoach.ai.MetricsSnapshot
import com.eliteteam.speakingcoach.ai.LlmRequestPeriod
import com.eliteteam.speakingcoach.ai.ReminderDay
import com.eliteteam.speakingcoach.ai.ReminderRun
import com.eliteteam.speakingcoach.ai.ReminderSegment
import com.eliteteam.speakingcoach.ai.ReminderTemplateStats
import com.eliteteam.speakingcoach.ai.ReminderTotals
import com.eliteteam.speakingcoach.ai.RemindersSnapshot
import com.eliteteam.speakingcoach.ai.ReminderClockSummary
import com.eliteteam.speakingcoach.ai.RetentionCohort
import com.eliteteam.speakingcoach.ai.RetentionSlice
import com.eliteteam.speakingcoach.ai.RetentionSnapshot
import com.eliteteam.speakingcoach.ai.StreakBucket
import com.eliteteam.speakingcoach.ai.StreakReminderBucket
import com.eliteteam.speakingcoach.ai.StreaksSnapshot
import com.eliteteam.speakingcoach.telegram.ReminderAdmin
import java.time.LocalDate
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetricsDashboardTest {
    @Test
    fun onboardingPageAndAgentExportPreserveSelectedLlmPeriod() = testApplication {
        application {
            installSpeakingCoachHttp(MetricsDashboard(
                password = PASSWORD,
                source = FixedMetricsSource(sampleSnapshot()),
                secureCookie = false,
                onboarding = MemoryOnboardingAnalytics(),
            ))
        }
        val selected = "llmFrom=2026-09-23&llmTo=2026-09-24"
        val page = client.get("$ONBOARDING_ANALYTICS_PATH?days=7&$selected") {
            cookie(METRICS_COOKIE, metricsSessionToken(PASSWORD))
        }.bodyAsText()
        assertTrue(page.contains("Запросы к LLM · все пользователи"))
        assertTrue(page.contains("name=\"llmFrom\" value=\"2026-09-23\""))
        assertTrue(page.contains("name=\"llmTo\" value=\"2026-09-24\""))
        assertTrue(page.contains("llmFrom=2026-09-23&amp;llmTo=2026-09-24"))
        assertTrue(page.contains("<tr><td>8</td><td>1</td><td>3</td>"))

        val exported = client.get("$ONBOARDING_AGENT_PATH?days=7&$selected") {
            cookie(METRICS_COOKIE, metricsSessionToken(PASSWORD))
        }
        val root = Json.parseToJsonElement(exported.bodyAsText()).jsonObject
        val llm = root.getValue("llm_requests_period").jsonObject
        assertEquals("2026-09-23", llm.getValue("from").jsonPrimitive.content)
        assertEquals("2026-09-24", llm.getValue("to").jsonPrimitive.content)
        assertEquals(JsonNull, root.getValue("llm_requests_today"))
        assertEquals("8", llm.getValue("requests").jsonPrimitive.content)
        assertEquals("3", llm.getValue("by_purpose").jsonObject.getValue("onboarding").jsonPrimitive.content)

        val mainPage = client.get("$METRICS_PATH?$selected") {
            cookie(METRICS_COOKIE, metricsSessionToken(PASSWORD))
        }.bodyAsText()
        assertTrue(mainPage.contains("2026-09-23 — 2026-09-24 включительно"))
        assertTrue(mainPage.contains("<dt>Запросы к LLM</dt><dd>8</dd>"))
        assertTrue(mainPage.contains("<dt>Токены prompt</dt><dd>100</dd>"))

        val current = client.get(ONBOARDING_AGENT_PATH) {
            cookie(METRICS_COOKIE, metricsSessionToken(PASSWORD))
        }
        assertEquals("all_users_today", Json.parseToJsonElement(current.bodyAsText()).jsonObject
            .getValue("llm_requests_today").jsonObject.getValue("scope").jsonPrimitive.content)
    }

    @Test
    fun llmRequestCountsAppearOnMetricsPage() {
        val range = LlmRange(LocalDate.parse("2026-09-23"), LocalDate.parse("2026-09-24"))
        val html = metricsReportHtml(sampleSnapshot(), sampleLlmPeriod(range), range)
        assertTrue(html.contains("2026-09-23 — 2026-09-24 включительно"))
        assertTrue(html.contains("<dt>Запросы к LLM</dt><dd>8</dd>"))
        assertTrue(html.contains("<dt>Ошибки LLM</dt><dd>1</dd>"))
        assertTrue(html.contains("<dt>LLM · онбординг</dt><dd>3</dd>"))
    }

    @Test
    fun invalidLlmPeriodIsRejectedBeforeReadingMetrics() = testApplication {
        application {
            installSpeakingCoachHttp(MetricsDashboard(
                password = PASSWORD, source = FixedMetricsSource(sampleSnapshot()),
                secureCookie = false, onboarding = MemoryOnboardingAnalytics(),
            ))
        }
        for (path in listOf(
            "$METRICS_PATH?llmFrom=2026-09-24&llmTo=2026-09-23",
            "$ONBOARDING_ANALYTICS_PATH?llmFrom=2026-09-23",
            "$ONBOARDING_AGENT_PATH?llmFrom=2020-01-01&llmTo=2026-10-01",
        )) {
            val response = client.get(path) { cookie(METRICS_COOKIE, metricsSessionToken(PASSWORD)) }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun agentExportUsesMetricsSessionAndKeepsFilters() = testApplication {
        application {
            installSpeakingCoachHttp(MetricsDashboard(
                password = PASSWORD,
                source = object : MetricsSource {
                    override suspend fun load(): MetricsSnapshot = error("unused for onboarding")
                    override suspend fun reminderSummary() = ReminderClockSummary(
                        "Europe/Moscow", 2, mapOf("08" to 1, "13" to 1))
                },
                secureCookie = false,
                onboarding = MemoryOnboardingAnalytics(),
            ))
        }
        val anonymous = client.get("$ONBOARDING_AGENT_PATH?days=7")
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        assertEquals("no-store", anonymous.headers[HttpHeaders.CacheControl])

        val exported = client.get("$ONBOARDING_AGENT_PATH?days=7&source=campaign") {
            cookie(METRICS_COOKIE, metricsSessionToken(PASSWORD))
        }
        assertEquals(HttpStatusCode.OK, exported.status)
        assertTrue(exported.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/json"))
        assertTrue(exported.headers[HttpHeaders.ContentDisposition].orEmpty().contains("attachment"))
        val root = Json.parseToJsonElement(exported.bodyAsText()).jsonObject
        assertEquals(JsonNull, root["llm_requests_today"])
        assertEquals(JsonNull, root["llm_requests_period"])
        assertEquals("7", root.getValue("filters").jsonObject.getValue("start_days").jsonPrimitive.content)
        assertEquals("campaign", root.getValue("filters").jsonObject.getValue("start_source").jsonPrimitive.content)
        assertEquals("2", root.getValue("current_reminders").jsonObject.getValue("active").jsonPrimitive.content)

        val page = client.get("$ONBOARDING_ANALYTICS_PATH?days=7&source=campaign") {
            cookie(METRICS_COOKIE, metricsSessionToken(PASSWORD))
        }.bodyAsText()
        assertTrue(page.contains("$ONBOARDING_AGENT_PATH?days=7&amp;source=campaign"))
        assertTrue(page.contains("Активных: 2"))
        assertTrue(page.contains("08:00–08:59"))
    }

    @Test
    fun reminderSourceFailureDoesNotHideOnboardingAnalytics() = testApplication {
        application {
            installSpeakingCoachHttp(MetricsDashboard(
                password = PASSWORD,
                source = object : MetricsSource {
                    override suspend fun load(): MetricsSnapshot = error("unused")
                    override suspend fun reminderSummary(): ReminderClockSummary = error("ai-service unavailable")
                },
                secureCookie = false,
                onboarding = MemoryOnboardingAnalytics(),
            ))
        }
        val exported = client.get(ONBOARDING_AGENT_PATH) {
            cookie(METRICS_COOKIE, metricsSessionToken(PASSWORD))
        }
        assertEquals(HttpStatusCode.OK, exported.status)
        assertEquals(JsonNull, Json.parseToJsonElement(exported.bodyAsText()).jsonObject["current_reminders"])
        val page = client.get(ONBOARDING_ANALYTICS_PATH) {
            cookie(METRICS_COOKIE, metricsSessionToken(PASSWORD))
        }.bodyAsText()
        assertTrue(page.contains("Текущие настройки напоминаний недоступны"))
    }

    @Test
    fun metricsRoutesStayHiddenWithoutPassword() = testApplication {
        application {
            module(dashboardConfig(password = null))
        }
        assertEquals(HttpStatusCode.NotFound, client.get("/admin/metrics").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/admin/metrics/login").status)
        assertEquals(HttpStatusCode.NotFound, client.get(ONBOARDING_AGENT_PATH).status)
    }

    @Test
    fun agentExportWithoutAnalyticsReturnsJsonUnavailable() = testApplication {
        application {
            module(dashboardConfig(password = PASSWORD), metricsSource = FixedMetricsSource(sampleSnapshot()))
        }
        val response = client.get(ONBOARDING_AGENT_PATH) {
            cookie(METRICS_COOKIE, metricsSessionToken(PASSWORD))
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/json"))
        assertEquals("{\"error\":\"analytics_unavailable\"}", response.bodyAsText())
    }

    @Test
    fun metricsPageAsksForPasswordUntilLogin() = testApplication {
        application {
            module(dashboardConfig(password = PASSWORD), metricsSource = FixedMetricsSource(sampleSnapshot()))
        }
        val anonymous = createClient { followRedirects = false }
        val form = anonymous.get("/admin/metrics")
        assertEquals(HttpStatusCode.OK, form.status)
        assertTrue(form.bodyAsText().contains("<form"))

        val rejected = anonymous.post("/admin/metrics/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("password=wrong")
        }
        assertEquals(HttpStatusCode.Unauthorized, rejected.status)
        assertEquals(null, rejected.headers[HttpHeaders.SetCookie])
        assertTrue(rejected.bodyAsText().contains("Неверный пароль."))

        val login = anonymous.post("/admin/metrics/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("password=$PASSWORD")
        }
        assertEquals(HttpStatusCode.Found, login.status)
        val setCookie = login.headers[HttpHeaders.SetCookie].orEmpty()
        assertTrue(setCookie.contains("HttpOnly"))
        assertFalse(setCookie.contains(PASSWORD))
        val token = setCookie.substringAfter("$METRICS_COOKIE=").substringBefore(";")
        assertEquals(metricsSessionToken(PASSWORD), token)

        val page = anonymous.get("/admin/metrics") {
            cookie(METRICS_COOKIE, token)
        }
        val html = page.bodyAsText()
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(html.contains("tg-9"))
        assertTrue(html.contains("<td>@alex_g · Alex &lt;Green&gt;</td>"))
        assertTrue(html.contains("<tr><td>tg-10</td><td>—</td>"))
        assertTrue(html.contains("Activated за 7 дней"))
        assertTrue(html.contains("clubs"))
        assertTrue(html.contains("1.50"))
        assertTrue(html.contains("3.00"))
        assertFalse(html.contains("transcript"))
        assertFalse(html.contains("I walked on weekends"))
        assertFalse(html.contains(PASSWORD))
    }

    @Test
    fun metricsPageHidesAiFailureBody() = testApplication {
        application {
            module(dashboardConfig(password = PASSWORD), metricsSource = FailingMetricsSource())
        }
        val page = client.get("/admin/metrics") {
            cookie(METRICS_COOKIE, metricsSessionToken(PASSWORD))
        }
        val html = page.bodyAsText()
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(html.contains("Сводка сейчас недоступна."))
        assertFalse(html.contains("secret-transcript"))
    }

    @Test
    fun reminderPostsNeedASession() = testApplication {
        val admin = FakeReminderAdmin()
        application {
            module(dashboardConfig(password = PASSWORD), metricsSource = FixedMetricsSource(sampleSnapshot()), reminderAdmin = admin)
        }
        val anonymous = createClient { followRedirects = false }
        assertEquals(HttpStatusCode.Forbidden, anonymous.post("/admin/metrics/reminders/send").status)
        val test = anonymous.post("/admin/metrics/reminders/test") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("chatId=42&template=today")
        }
        assertEquals(HttpStatusCode.Forbidden, test.status)
        assertEquals(0, admin.started)
        assertTrue(admin.tests.isEmpty())
    }

    @Test
    fun reminderPostsRedirectWithNotice() = testApplication {
        val admin = FakeReminderAdmin()
        application {
            module(dashboardConfig(password = PASSWORD), metricsSource = FixedMetricsSource(sampleSnapshot()), reminderAdmin = admin)
        }
        val browser = createClient { followRedirects = false }
        suspend fun post(path: String, body: String = "") = browser.post(path) {
            cookie(METRICS_COOKIE, metricsSessionToken(PASSWORD))
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(body)
        }.headers[HttpHeaders.Location]

        assertEquals("/admin/metrics/reminders?notice=started", post("/admin/metrics/reminders/send"))
        admin.canStart = false
        assertEquals("/admin/metrics/reminders?notice=busy", post("/admin/metrics/reminders/send"))
        assertEquals("/admin/metrics/reminders?notice=test-invalid", post("/admin/metrics/reminders/test", "chatId=abc"))
        assertEquals("/admin/metrics/reminders?notice=test-invalid", post("/admin/metrics/reminders/test", "chatId=42&template=missing"))
        assertEquals("/admin/metrics/reminders?notice=test-sent", post("/admin/metrics/reminders/test", "chatId=42&template=today"))
        assertEquals("/admin/metrics/reminders?notice=test-sent", post("/admin/metrics/reminders/test", "chatId=42&template=weekend_plan"))
        admin.testResult = false
        assertEquals("/admin/metrics/reminders?notice=test-failed", post("/admin/metrics/reminders/test", "chatId=-7"))
        assertEquals(1, admin.started)
        assertEquals(listOf(42L to null, 42L to "weekend_plan", -7L to null), admin.tests)
    }

    @Test
    fun reminderSectionShowsStatsAndControls() = testApplication {
        val snapshot = sampleSnapshot().copy(
            reminders = RemindersSnapshot(
                today = ReminderTotals(sent = 3, blocked = 1),
                week = ReminderTotals(sent = 10, blocked = 2, returned = 4),
                days = listOf(ReminderDay(day = "2026-09-24", sent = 3, returned = 1, blocked = 1)),
                segments = listOf(ReminderSegment("new", sent = 6, returned = 1), ReminderSegment("active", sent = 4, returned = 3)),
                templates = listOf(ReminderTemplateStats("weekend_plan", sent = 3, returned = 1), ReminderTemplateStats("gone_id", sent = 1)),
                replyMedianSeconds = 3900,
                runs = listOf(
                    ReminderRun(
                        mode = "auto",
                        day = "2026-09-24",
                        startedAt = "2026-09-24T19:00:00+03:00",
                        finishedAt = "2026-09-24T19:00:07+03:00",
                        claimed = 4,
                        sent = 3,
                        blocked = 1,
                    ),
                ),
                autoToday = ReminderRun(mode = "auto", startedAt = "2026-09-24T19:00:00+03:00", sent = 3),
                forecast = 12,
            ),
        )
        application {
            module(dashboardConfig(password = PASSWORD), metricsSource = FixedMetricsSource(snapshot), reminderAdmin = FakeReminderAdmin())
        }
        val html = client.get("/admin/metrics/reminders?notice=started") {
            cookie(METRICS_COOKIE, metricsSessionToken(PASSWORD))
        }.bodyAsText()

        assertTrue(html.contains("<a href=\"/admin/metrics/reminders\" aria-current=\"page\">Напоминания</a>"))
        assertTrue(html.contains("Рассылка запущена."))
        assertTrue(html.contains("4 · 40%"))
        assertTrue(html.contains("1 ч 5 мин"))
        assertTrue(html.contains("19:00 · 3"))
        assertTrue(html.contains("примерно 12 людям"))
        assertTrue(html.contains("action=\"/admin/metrics/reminders/test\""))
        assertTrue(html.contains("<option value=\"weekend_plan\">"))
        assertTrue(html.contains("7 с"))
        assertTrue(html.contains("<tr><td>Только /start</td><td>6</td><td>1</td><td>17%</td></tr>"))
        assertTrue(html.contains("удалён из пула"))
        assertFalse(html.contains("<h2>Воронка</h2>"))
    }

    @Test
    fun summaryPageLinksToRemindersTabWithoutTheSection() = testApplication {
        application {
            module(dashboardConfig(password = PASSWORD), metricsSource = FixedMetricsSource(sampleSnapshot()), reminderAdmin = FakeReminderAdmin())
        }
        val html = client.get("/admin/metrics") {
            cookie(METRICS_COOKIE, metricsSessionToken(PASSWORD))
        }.bodyAsText()
        val anonymous = client.get("/admin/metrics/reminders").bodyAsText()

        assertTrue(html.contains("<a href=\"/admin/metrics\" aria-current=\"page\">Сводка</a>"))
        assertTrue(html.contains("<a href=\"/admin/metrics/reminders\">Напоминания</a>"))
        assertFalse(html.contains("Отправить всем сейчас"))
        assertTrue(html.contains("<th>Игнор подряд</th>"))
        assertTrue(anonymous.contains("type=\"password\""))
        assertFalse(anonymous.contains("Отправить всем сейчас"))
    }

    @Test
    fun streaksTabShowsBucketsRepliesAndRetention() = testApplication {
        application {
            module(
                dashboardConfig(password = PASSWORD),
                metricsSource = FixedMetricsSource(sampleSnapshot().copy(streaks = sampleStreaks())),
            )
        }
        val html = client.get("/admin/metrics/streaks") {
            cookie(METRICS_COOKIE, metricsSessionToken(PASSWORD))
        }.bodyAsText()
        val anonymous = client.get("/admin/metrics/streaks").bodyAsText()

        assertTrue(html.contains("<a href=\"/admin/metrics/streaks\" aria-current=\"page\">Стрики</a>"))
        assertTrue(html.contains("<tr><td>2–6</td><td>4</td></tr>"))
        assertTrue(html.contains("<tr><td>14+</td><td>3</td><td>1</td><td>33%</td></tr>"))
        assertTrue(html.contains("<tr><td>2026-09-21</td><td>10</td><td>40%</td><td>—</td><td>—</td></tr>"))
        assertTrue(html.contains("<tr><td>До релиза</td><td>8</td><td>25%</td><td>20%</td><td>—</td></tr>"))
        assertTrue(html.contains("Релиз стриков: 2026-09-26."))
        assertTrue(anonymous.contains("type=\"password\""))
        assertFalse(anonymous.contains("Retention"))
    }

    @Test
    fun streaksTabWithoutData() = testApplication {
        application {
            module(dashboardConfig(password = PASSWORD), metricsSource = FixedMetricsSource(sampleSnapshot()))
        }
        val html = client.get("/admin/metrics/streaks") {
            cookie(METRICS_COOKIE, metricsSessionToken(PASSWORD))
        }.bodyAsText()
        assertTrue(html.contains("Нет данных о стриках."))
    }

    @Test
    fun reminderSectionWithoutDataOrControls() = testApplication {
        application {
            module(dashboardConfig(password = PASSWORD), metricsSource = FixedMetricsSource(sampleSnapshot()))
        }
        val html = client.get("/admin/metrics/reminders?notice=%3Cscript%3E") {
            cookie(METRICS_COOKIE, metricsSessionToken(PASSWORD))
        }.bodyAsText()

        assertTrue(html.contains("Нет данных о напоминаниях."))
        assertFalse(html.contains("/admin/metrics/reminders/send"))
        assertFalse(html.contains("<script>"))
    }

    private class FakeReminderAdmin : ReminderAdmin {
        var canStart = true
        var testResult = true
        var started = 0
        val tests = mutableListOf<Pair<Long, String?>>()

        override fun startAll(): Boolean {
            if (canStart) {
                started += 1
            }
            return canStart
        }

        override suspend fun sendTest(chatId: Long, templateId: String?): Boolean {
            tests += chatId to templateId
            return testResult
        }
    }

    private fun dashboardConfig(password: String?) = AppConfig(
        telegramBotToken = null,
        telegramWebhookUrl = null,
        telegramWebhookSecret = null,
        aiServiceBaseUrl = "http://127.0.0.1:8090",
        aiInternalToken = "test-internal-token",
        serverPort = 8080,
        tlsCertPath = AppConfig.DEFAULT_TLS_CERT_PATH,
        tlsKeyPath = AppConfig.DEFAULT_TLS_KEY_PATH,
        jwtSecret = null,
        databaseUrl = null,
        metricsPassword = password,
    )

    private fun sampleSnapshot() = MetricsSnapshot(
        timezone = "Europe/Moscow",
        day = "2026-09-24",
        promptTokens = 100,
        completionTokens = 40,
        llmRequests = 8,
        llmFailures = 1,
        llmRequestsByPurpose = mapOf("onboarding" to 3, "reply" to 3, "notes" to 1, "session_review" to 1),
        tpm = 12,
        tps = 8.0,
        turns = 2,
        dau = 1,
        sttSeconds = 3.5,
        ttsChars = 20,
        rubPerTurn = 1.5,
        rubPerDau = 3.0,
        ratesConfigured = true,
        chats = listOf(
            MetricsChat(
                sessionId = "tg-9",
                turns = 2,
                lastAt = "2026-09-24T12:00:00+03:00",
                username = "alex_g",
                name = "Alex <Green>",
            ),
            MetricsChat(
                sessionId = "tg-10",
                turns = 1,
                lastAt = "2026-09-24T11:00:00+03:00",
            ),
        ),
        activated7 = 4,
        funnelDays = listOf(FunnelDay(day = "2026-09-24", start = 2, activated = 1)),
        funnelSources = listOf(FunnelSource(source = "clubs", start = 2, activated = 1)),
    )

    private fun sampleStreaks() = StreaksSnapshot(
        buckets = listOf(StreakBucket("0", 2), StreakBucket("2_6", 4), StreakBucket("14_plus", 1)),
        reminderBuckets = listOf(StreakReminderBucket("14_plus", sent = 3, returned = 1)),
        retention = RetentionSnapshot(
            cohorts = listOf(RetentionCohort(week = "2026-09-21", size = 10, d1 = 0.4)),
            before = RetentionSlice(size = 8, d1 = 0.25, d7 = 0.2),
            after = RetentionSlice(size = 2, d1 = 0.5),
            releasedDay = "2026-09-26",
        ),
    )

    private class FixedMetricsSource(private val snapshot: MetricsSnapshot) : MetricsSource {
        override suspend fun load(): MetricsSnapshot = snapshot
        override suspend fun llmRange(range: LlmRange): LlmRequestPeriod = sampleLlmPeriod(range)
    }

    private class FailingMetricsSource : MetricsSource {
        override suspend fun load(): MetricsSnapshot {
            error("ai-service GET /internal/metrics returned 500 body secret-transcript")
        }
    }

    private companion object {
        const val PASSWORD = "secret-pass"

        fun sampleLlmPeriod(range: LlmRange) = LlmRequestPeriod(
            from = range.from.toString(), to = range.to.toString(), timezone = "Europe/Moscow",
            requests = 8, failures = 1,
            byPurpose = mapOf("onboarding" to 3, "reply" to 3, "notes" to 1, "session_review" to 1),
        )
    }
}
