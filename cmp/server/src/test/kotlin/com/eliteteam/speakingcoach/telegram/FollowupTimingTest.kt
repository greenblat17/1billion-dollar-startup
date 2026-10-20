package com.eliteteam.speakingcoach.telegram

import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class FollowupTimingTest {
    @Test
    fun oneStartKeepsItsTimeAndFirstDueIsAfterExactlyOneDay() {
        val start = Instant.parse("2026-10-20T11:35:37Z")
        assertEquals(PreferredVisitTime(11 * 60 + 35, 1), preferredVisitTime(listOf(start)))
        assertEquals(start.plusSeconds(86_400), nextFollowupDue(11 * 60 + 35, start.plusSeconds(86_400)))
    }

    @Test
    fun conversationIsOneVisitAndMedianIgnoresDistantOutlier() {
        val visits = listOf(
            Instant.parse("2026-10-01T09:00:00Z"), Instant.parse("2026-10-01T09:10:00Z"),
            Instant.parse("2026-10-02T09:05:00Z"), Instant.parse("2026-10-03T09:10:00Z"),
            Instant.parse("2026-10-04T23:00:00Z"),
        )
        assertEquals(PreferredVisitTime(9 * 60 + 5, 4), preferredVisitTime(visits))
    }

    @Test
    fun midnightTimesStayInOneCluster() {
        val visits = listOf(
            Instant.parse("2026-10-01T23:50:00Z"),
            Instant.parse("2026-10-03T00:10:00Z"),
            Instant.parse("2026-10-04T00:15:00Z"),
        )
        assertEquals(PreferredVisitTime(10, 3), preferredVisitTime(visits))
    }

    @Test
    fun dispersedVisitsChooseMostRecentAndNightIsAllowed() {
        val visits = listOf(
            Instant.parse("2026-10-01T09:00:00Z"),
            Instant.parse("2026-10-02T15:00:00Z"),
            Instant.parse("2026-10-03T03:00:00Z"),
        )
        assertEquals(PreferredVisitTime(180, 3), preferredVisitTime(visits))
    }

    @Test
    fun catalogCoversJourneyAndDoesNotRepeatAcceptedVariants() {
        val stages = listOf("start", "invitation", "begin", "first_question", "voice_received",
            "voice_recognized", "speech_30", "speech_60", "speech_90", "speech_120",
            "result_delivered", "result_opened", "goal_shown", "goal_decided", "reminder_offered",
            "reminder_resolved", "pending")
        stages.forEach { stage ->
            assertEquals(10, FollowupCatalog.variants(stage).size)
            val consumed = mutableSetOf<String>()
            repeat(10) { consumed += FollowupCatalog.select(stage, "tg-123", consumed)!!.id }
            assertEquals(null, FollowupCatalog.select(stage, "tg-123", consumed))
        }
        assertEquals(14, FollowupCatalog.variants("practice").size)
    }

    @Test
    fun actionFollowsVerifiedStageAndLiveState() {
        assertEquals("resume", followupAction("first_question", "active"))
        assertEquals("resume", followupAction("speech_60", "active"))
        assertEquals("retry", followupAction("voice_received", "pending"))
        assertEquals("level", followupAction("result_delivered", "completed"))
        assertEquals("practice", followupAction("reminder_offered", "completed"))
        assertEquals("begin", followupAction("start", "waiting"))
    }

    @Test
    fun everyFollowupActionHasRelevantButtonWithoutStop() {
        val actions = mapOf(
            "begin" to "ob:begin:run", "resume" to "fu:resume:run",
            "retry" to "ob:retry:run", "level" to "ob:level:run",
            "results" to "ob:results:run", "practice" to "fu:practice",
        )
        actions.forEach { (action, expected) ->
            val buttons = followupKeyboard(action, "run").keyboard.flatten()
                .filterIsInstance<CallbackDataInlineKeyboardButton>()
            assertEquals(listOf(expected), buttons.map { it.callbackData })
        }
        assertEquals("Let’s chat 👋", followupKeyboard("resume", "run", "invitation")
            .keyboard.flatten().single().text)
        assertEquals("Continue our chat 🎙", followupKeyboard("resume", "run", "speech_60")
            .keyboard.flatten().single().text)
        assertEquals(4, followupKeyboard("goal", "run").keyboard.flatten().size)
    }
}
