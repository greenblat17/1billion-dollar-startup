package com.eliteteam.speakingcoach.analytics

import com.eliteteam.speakingcoach.onboardingReportHtml
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingJourneyTest {
    private val start = Instant.parse("2026-10-01T07:00:00Z")

    @Test
    fun closedPauseAndRecoveredFailureKeepDistinctUserStates() {
        val stopped = attempt("stopped", "tg-1").copy(
            beginPressedAt = start.plusSeconds(20),
            firstQuestionDeliveredAt = start.plusSeconds(30),
        )
        val completed = attempt("completed", "tg-2").copy(
            beginPressedAt = start.plusSeconds(20), firstQuestionDeliveredAt = start.plusSeconds(30),
            speech30At = start.plusSeconds(50), speech60At = start.plusSeconds(50),
            speech90At = start.plusSeconds(50), speech120At = start.plusSeconds(50),
            resultDeliveredAt = start.plusSeconds(70), resultsOpenedAt = start.plusSeconds(80),
            practiceSetupAt = start.plusSeconds(90), goalSelectedAt = start.plusSeconds(100),
            goalMinutes = 0, reminderOfferedAt = start.plusSeconds(110),
            reminderDecision = "not_now", reminderDecisionAt = start.plusSeconds(120),
        )
        val voices = listOf(
            OnboardingVoiceRow("completed", "tg-2", false, "no_speech", start.plusSeconds(35),
                outcome = "no_speech", receivedAt = start.plusSeconds(34), voiceIndex = 1),
            OnboardingVoiceRow("completed", "tg-2", true, null, start.plusSeconds(55),
                outcome = "recognized", receivedAt = start.plusSeconds(40), voiceIndex = 2),
        )
        val entries = listOf(entry("tg-1", "stopped"), entry("tg-2", "completed"))
        val report = onboardingJourney(listOf(stopped, completed), voices, emptyList(), entries,
            start.plusSeconds(86_401), OnboardingFilter())
        assertEquals(2, report.total)
        assertEquals(0, report.open)
        assertEquals(1, report.steps.first { it.stage.id == "first_question" }.stopped)
        assertEquals(1, report.steps.first { it.stage.id == "reminder_resolved" }.reached)
        assertEquals(1, report.errors.single().events)
        assertEquals("no_speech", report.errors.single().reason)
        assertEquals("completed", report.recent.first { it.chatId == 2L }.state)
        assertEquals("stopped", report.recent.first { it.chatId == 1L }.state)
    }

    @Test
    fun openAndMissingAttemptsAreNotCalledDropOff() {
        val report = onboardingJourney(listOf(attempt("open", "tg-1")), emptyList(), emptyList(),
            listOf(entry("tg-1", "open"), entry("tg-2", "missing")), start.plusSeconds(60), OnboardingFilter())
        assertEquals(2, report.total)
        assertEquals(2, report.open)
        assertTrue(report.steps.all { it.stopped == 0 })
        assertEquals("in_progress", report.recent.first { it.chatId == 1L }.state)
        assertEquals("incomplete", report.recent.first { it.chatId == 2L }.state)
    }

    @Test
    fun latestListHasFifteenDistinctUsersAndHtmlEscapesStoredUsername() {
        val entries = (1..18).map { number -> entry("tg-$number", "run-$number")
            .copy(receivedAt = start.plusSeconds(number.toLong()), username = if (number == 18) "<script>" else "name$number") }
        val attempts = (1..18).map { number -> attempt("run-$number", "tg-$number") }
        val report = onboardingReport(attempts, emptyList(), start.plusSeconds(100), entries = entries)
        assertEquals(15, report.journey.recent.size)
        assertEquals(18L, report.journey.recent.first().chatId)
        assertFalse(report.journey.recent.any { it.chatId == 1L })
        val html = onboardingReportHtml(report)
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun repeatModeCountsAttemptsAndKeepsOnlyLatestPerUserInRecentList() {
        val entries = listOf(entry("tg-1", "first"))
        val attempts = listOf(attempt("first", "tg-1"),
            attempt("repeat-1", "tg-1").copy(isPrimary = false, trigger = "onboarding_command",
                startedAt = start.plusSeconds(10), attemptNumber = 2),
            attempt("repeat-2", "tg-1").copy(isPrimary = false, trigger = "onboarding_command",
                startedAt = start.plusSeconds(20), attemptNumber = 3))
        val report = onboardingJourney(attempts, emptyList(), emptyList(), entries, start.plusSeconds(100),
            OnboardingFilter(journeyMode = JourneyMode.REPEATS))
        assertEquals(2, report.total)
        assertEquals(1, report.recent.size)
        assertEquals(3, report.recent.single().attemptNumber)
    }

    @Test
    fun recentStatusIncludesCompletionAfterCohortWindow() {
        val late = start.plusSeconds(25 * 3_600)
        val attempt = attempt("late", "tg-1").copy(
            beginPressedAt = start.plusSeconds(20), firstQuestionDeliveredAt = start.plusSeconds(30),
            speech30At = start.plusSeconds(50), speech60At = start.plusSeconds(50),
            speech90At = start.plusSeconds(50), speech120At = start.plusSeconds(50),
            resultDeliveredAt = late, resultsOpenedAt = late.plusSeconds(10),
            practiceSetupAt = late.plusSeconds(20), goalSelectedAt = late.plusSeconds(30),
            goalMinutes = 0, reminderOfferedAt = late.plusSeconds(40),
            reminderDecision = "not_now", reminderDecisionAt = late.plusSeconds(50),
        )
        val voice = OnboardingVoiceRow("late", "tg-1", true, null, start.plusSeconds(40),
            outcome = "recognized", receivedAt = start.plusSeconds(35), voiceIndex = 1)
        val report = onboardingJourney(listOf(attempt), listOf(voice), emptyList(), listOf(entry("tg-1", "late")),
            late.plusSeconds(60), OnboardingFilter())
        assertEquals(0, report.steps.first { it.stage.id == "result_delivered" }.reached)
        assertEquals("completed", report.recent.single().state)
    }

    @Test
    fun memoryStoreNumbersRepeatAttempts() = runTest {
        val analytics = MemoryOnboardingAnalytics()
        analytics.startAttempt("tg-1", "first", "start", start, chatId = 1)
        analytics.startAttempt("tg-1", "repeat", "onboarding_command", start.plusSeconds(10), chatId = 1)
        val report = analytics.report(start.plusSeconds(30), OnboardingFilter(journeyMode = JourneyMode.REPEATS))
        assertEquals(2, report.journey.recent.single().attemptNumber)
    }

    @Test
    fun repeatedVoiceFailureCountsOneRecoveredUserAndTwoEvents() {
        val finished = finishedAttempt("recovered", "tg-1")
        val voices = listOf(
            OnboardingVoiceRow("recovered", "tg-1", false, "no_speech", start.plusSeconds(36),
                outcome = "no_speech", receivedAt = start.plusSeconds(35)),
            OnboardingVoiceRow("recovered", "tg-1", false, "no_speech", start.plusSeconds(42),
                outcome = "no_speech", receivedAt = start.plusSeconds(40)),
            OnboardingVoiceRow("recovered", "tg-1", true, null, start.plusSeconds(55),
                outcome = "recognized", receivedAt = start.plusSeconds(50)),
        )
        val report = onboardingJourney(listOf(finished), voices, emptyList(), listOf(entry("tg-1", "recovered")),
            start.plusSeconds(86_401), OnboardingFilter())
        val error = report.errors.single()
        assertEquals(2, error.events)
        assertEquals(1, error.users)
        assertEquals(1, error.retryMeasurable)
        assertEquals(1, error.retried)
        assertEquals(1, error.reachedNext)
        assertEquals(1, error.completed)
    }

    @Test
    fun callbackRetriesRequireAnObservedInitialAction() {
        val attempts = listOf(
            attempt("measured", "tg-1").copy(beginPressedAt = start.plusSeconds(20),
                firstQuestionDeliveredAt = start.plusSeconds(50)),
            attempt("historical", "tg-2").copy(beginPressedAt = start.plusSeconds(20),
                firstQuestionDeliveredAt = start.plusSeconds(50)),
        )
        val events = listOf(
            OnboardingEventRow("measured", "action_attempt", start.plusSeconds(25), "first_question"),
            OnboardingEventRow("measured", "stage_error", start.plusSeconds(30), "first_question", "timeout"),
            OnboardingEventRow("measured", "action_attempt", start.plusSeconds(40), "first_question"),
            OnboardingEventRow("historical", "stage_error", start.plusSeconds(30), "first_question", "timeout"),
        )
        val report = onboardingJourney(attempts, emptyList(), events,
            listOf(entry("tg-1", "measured"), entry("tg-2", "historical")),
            start.plusSeconds(86_401), OnboardingFilter())
        val error = report.errors.single()
        assertEquals(2, error.users)
        assertEquals(1, error.retryMeasurable)
        assertEquals(1, error.retried)
        assertEquals(2, error.reachedNext)
    }

    @Test
    fun postCompletionPracticeUsesMatureTrackedCompletions() {
        val finished = finishedAttempt("tracked", "tg-1").copy(
            postCompletionPracticeObservable = true,
            firstPracticeAt = start.plusSeconds(115),
            firstPostCompletionPracticeAt = start.plusSeconds(150),
        )
        val old = finishedAttempt("old", "tg-2").copy(firstPracticeAt = start.plusSeconds(150))
        val voices = listOf("tracked" to "tg-1", "old" to "tg-2").map { (run, session) ->
            OnboardingVoiceRow(run, session, true, null, start.plusSeconds(55),
                outcome = "recognized", receivedAt = start.plusSeconds(50))
        }
        val report = onboardingJourney(listOf(finished, old), voices, emptyList(),
            listOf(entry("tg-1", "tracked"), entry("tg-2", "old")),
            start.plusSeconds(86_521), OnboardingFilter())
        assertEquals(JourneyPractice(2, 1, 1, 1, 0, 0), report.practice)
    }

    private fun finishedAttempt(run: String, session: String) = attempt(run, session).copy(
        beginPressedAt = start.plusSeconds(20), firstQuestionDeliveredAt = start.plusSeconds(30),
        speech30At = start.plusSeconds(60), speech60At = start.plusSeconds(60),
        speech90At = start.plusSeconds(60), speech120At = start.plusSeconds(60),
        resultDeliveredAt = start.plusSeconds(70), resultsOpenedAt = start.plusSeconds(80),
        practiceSetupAt = start.plusSeconds(90), goalSelectedAt = start.plusSeconds(100),
        goalMinutes = 0, reminderOfferedAt = start.plusSeconds(110),
        reminderDecision = "not_now", reminderDecisionAt = start.plusSeconds(120),
    )

    private fun attempt(run: String, session: String) = OnboardingAttemptRow(
        runId = run, sessionId = session, trigger = "start", isPrimary = true,
        startedAt = start.plusSeconds(5), invitationDeliveredAt = start.plusSeconds(10),
        chatId = session.removePrefix("tg-").toLong(),
    )

    private fun entry(session: String, run: String) = OnboardingEntryRow(
        sessionId = session, entryKey = "message:$run", receivedAt = start,
        eligible = true, trigger = "start", exclusionReason = null, source = null,
        runId = run, invitationDeliveredAt = start.plusSeconds(10),
        chatId = session.removePrefix("tg-").toLong(),
    )
}
