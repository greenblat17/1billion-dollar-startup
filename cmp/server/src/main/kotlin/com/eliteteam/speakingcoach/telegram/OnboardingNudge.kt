package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.analytics.OnboardingNudgeCandidate
import com.eliteteam.speakingcoach.withRequestLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val log = LoggerFactory.getLogger("OnboardingNudge")

internal fun nudgeAction(status: String, began: Boolean): String? = when (status) {
    "waiting" -> "begin"
    "active" -> if (began) "voice" else "begin"
    "pending" -> "retry"
    else -> null
}

internal fun nudgeText(action: String): String = when (action) {
    "begin" -> "👋 I’m still here when you’re ready. Let’s have a quick chat in English and find your starting level."
    "retry" -> "Your English result is nearly ready. Tap Retry to continue."
    else -> "🎙 Ready to continue our chat? Send me a voice message in English."
}

internal class OnboardingNudgeRunner(
    private val candidates: suspend (LocalDate, Instant) -> List<OnboardingNudgeCandidate>,
    private val state: suspend (OnboardingNudgeCandidate) -> Pair<String, String>,
    private val claim: suspend (String, LocalDate, Instant, Instant) -> Boolean,
    private val send: suspend (OnboardingNudgeCandidate, String) -> Unit,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(REMINDER_ZONE) },
) {
    suspend fun runRound(): Int {
        val now = clock().withZoneSameInstant(REMINDER_ZONE)
        if (now.hour !in 20..21) return 0
        val day = now.toLocalDate()
        val inactiveSince = now.minusHours(24).toInstant()
        var sent = 0
        for (candidate in candidates(day, inactiveSince)) {
            try {
                val (runId, status) = state(candidate)
                if (runId != candidate.runId) continue
                val action = nudgeAction(status, candidate.began) ?: continue
                if (!claim(candidate.runId, day, inactiveSince, clock().toInstant())) continue
                withRequestLog(candidate.sessionId, "onboarding:nudge") { send(candidate, action) }
                sent++
                delay(50)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.warn("Onboarding nudge failed for run {}", candidate.runId, error)
            }
        }
        if (sent > 0) log.info("Sent {} onboarding nudges for {}", sent, day)
        return sent
    }
}

internal fun CoroutineScope.launchOnboardingNudges(
    runner: OnboardingNudgeRunner,
    tick: Duration = 1.minutes,
): Job = launch {
    while (isActive) {
        try {
            runner.runRound()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.error("Onboarding nudge round failed", error)
        }
        delay(tick)
    }
}
