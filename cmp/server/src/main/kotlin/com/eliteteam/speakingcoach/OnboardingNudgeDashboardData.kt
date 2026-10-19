package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.analytics.NudgeReport
import com.eliteteam.speakingcoach.analytics.OnboardingAnalytics
import com.eliteteam.speakingcoach.analytics.nudgeReportJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

private val nudgeDashboardLog = LoggerFactory.getLogger("OnboardingNudgeDashboard")

internal suspend fun loadNudgeDashboard(analytics: OnboardingAnalytics?, days: Int): NudgeReport? = try {
    analytics?.nudgeReport(days)
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    nudgeDashboardLog.warn("Onboarding nudge report unavailable", error)
    null
}

/** Call only after the route's admin-cookie or monitoring-port guard. */
internal suspend fun ApplicationCall.respondNudgeExport(analytics: OnboardingAnalytics?) {
    val days = request.queryParameters["days"]?.toIntOrNull()?.takeIf { it == 7 || it == 30 } ?: 7
    val report = loadNudgeDashboard(analytics, days)
    if (report == null) {
        respondText("{\"error\":\"analytics_unavailable\"}", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
    } else {
        respondText(nudgeReportJson(report), ContentType.Application.Json)
    }
}
