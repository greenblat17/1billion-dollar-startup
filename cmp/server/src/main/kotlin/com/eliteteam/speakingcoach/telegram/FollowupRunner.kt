package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.withRequestLog
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val followupLog = LoggerFactory.getLogger("OnboardingFollowup")

internal fun followupAction(stage: String, aiStatus: String): String? = when (aiStatus) {
    "pending" -> "retry"
    "waiting" -> if (stage in setOf("start", "invitation", "begin")) "begin" else null
    "active" -> "resume"
    "initial", "extended" -> "resume"
    "checkpoint" -> "checkpoint"
    "completed" -> when (stage) {
        "result_delivered" -> "level"
        "result_opened" -> "results"
        "goal_shown" -> "practice"
        "goal_decided", "reminder_offered", "reminder_resolved", "practice" -> "practice"
        else -> null
    }
    else -> null
}

internal data class FollowupDelivery(val copyId: String, val status: String)
internal data class FollowupSend(val messageId: Long, val copyKind: String = "general")

internal class FollowupRunner(
    private val store: FollowupStore,
    private val state: suspend (FollowupCandidate) -> Pair<String, String>,
    private val reminderTime: suspend (String) -> String?,
    private val send: suspend (ClaimedFollowup) -> FollowupSend,
    private val clock: () -> Instant = Instant::now,
) {
    suspend fun runRound(): Int {
        val now = clock()
        store.refreshDirty(now)
        var accepted = 0
        for (candidate in store.due(now)) {
            try {
                if (candidate.scheduledFor < now.minusSeconds(7_200)) {
                    store.deferExpired(candidate, now)
                    continue
                }
                if (reminderTime(candidate.sessionId) != null) {
                    store.deferExpired(candidate, now)
                    continue
                }
                val result = deliver(candidate, false)
                if (result?.status == "sent") accepted++
                delay(50)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                followupLog.warn("Follow-up failed for {}", candidate.runId, error)
            }
        }
        return accepted
    }

    /** Called after the opted-in reminder was claimed by AI-service; replaces its generic copy. */
    suspend fun forDailyReminder(sessionId: String): FollowupDelivery? {
        val now = clock()
        store.alreadyClaimedToday(sessionId, now)?.let { return it }
        store.refreshDirty(now)
        val candidate = store.reminderCandidate(sessionId, now) ?: return null
        return deliver(candidate, true)
    }

    private suspend fun deliver(candidate: FollowupCandidate, dailyReminderSlot: Boolean): FollowupDelivery? {
        val (runId, status) = state(candidate)
        if (runId != candidate.runId) return null
        val action = followupAction(candidate.stage, status) ?: return null
        val at = clock()
        val claim = store.claim(candidate, action, at, dailyReminderSlot) ?: return null
        val (delivery, sent) = try {
            val result = withRequestLog(candidate.sessionId, "onboarding:followup") { send(claim) }
            "sent" to result
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            followupLog.warn("Follow-up send failed for {}", candidate.runId, error)
            (when {
                error is RequestException && error.response.errorCode == 403 -> "blocked"
                error is RequestException -> "failed"
                else -> "unknown"
            }) to null
        }
        try {
            store.report(claim, delivery, clock(), sent?.messageId, sent?.copyKind)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            followupLog.error("Could not report follow-up outcome for {}", candidate.runId, error)
        }
        return FollowupDelivery(claim.copy.id, delivery)
    }
}

internal fun CoroutineScope.launchFollowups(runner: FollowupRunner, tick: Duration = 1.minutes): Job = launch {
    while (isActive) {
        try {
            runner.runRound()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            followupLog.error("Follow-up round failed", error)
        }
        delay(tick)
    }
}
