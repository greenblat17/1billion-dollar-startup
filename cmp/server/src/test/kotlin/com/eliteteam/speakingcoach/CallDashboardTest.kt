package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.analytics.CallEvent
import com.eliteteam.speakingcoach.analytics.CallEventsSnapshot
import com.eliteteam.speakingcoach.analytics.MemoryCallEventStore
import com.eliteteam.speakingcoach.ai.CallFeedbackEntry
import com.eliteteam.speakingcoach.ai.CallFeedbackList
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallDashboardTest {
    private val callId = "a".repeat(32)
    private val start = Instant.now().minusSeconds(120)

    @Test
    fun showsFeedbackUsernameChoiceAndEscapedMessage() {
        val feedback = CallFeedbackList(1, listOf(CallFeedbackEntry(
            "tg-42", "alex<script>", "neutral", "Improve <audio> & pacing",
        )))
        val page = callDashboardPage(CallEventsSnapshot(emptyList(), false, 0), CallDashboardFilter(),
            feedback = feedback)
        assertTrue(page.contains("Отзывы после первого разговора (1)"))
        assertTrue(page.contains("@alex&lt;script&gt;"))
        assertTrue(page.contains("😐 Так себе"))
        assertTrue(page.contains("Improve &lt;audio&gt; &amp; pacing"))
        assertFalse(page.contains("<audio>"))
    }

    @Test
    fun correlatesVoiceReplyCorrectionsAndSubtitleClicksWithoutDoubleCounting() = runTest {
        val store = MemoryCallEventStore()
        store.record(CallEvent("start:1", "start_pressed", 42, start, callId = callId))
        store.record(CallEvent("call:open", "call_open", 42, start, callId = callId,
            username = "<unsafe>", seconds = 300.0, state = "button"))
        store.record(CallEvent("voice:1", "voice_received", 42, start.plusSeconds(5),
            callId = callId, messageId = 1, seconds = 9.0))
        store.record(CallEvent("voice:1", "voice_received", 42, start.plusSeconds(5),
            callId = callId, messageId = 1, seconds = 9.0))
        store.record(CallEvent("processed:1", "voice_processed", 42, start.plusSeconds(6),
            callId = callId, messageId = 1, seconds = 5.5, amount = 2))
        store.record(CallEvent("card:1", "correction_card", 42, start.plusSeconds(7),
            callId = callId, messageId = 1, state = "delivered"))
        store.record(CallEvent("reply:1", "reply_delivered", 42, start.plusSeconds(8),
            callId = callId, messageId = 1, botMessageId = 99, milliseconds = 3000, state = "audio"))
        store.record(CallEvent("subtitle:a", "subtitle_click", 42, start.plusSeconds(9),
            callId = callId, botMessageId = 99, state = "shown"))
        store.record(CallEvent("subtitle:b", "subtitle_click", 42, start.plusSeconds(10),
            callId = callId, botMessageId = 99, state = "shown"))
        val snapshot = store.snapshot(start.minusSeconds(1))
        val row = callDashboardRows(snapshot).single()
        assertEquals(1, row.voices.size)
        assertEquals(9.0, row.audioSeconds)
        assertEquals(5.5, row.recognizedSeconds)
        assertEquals(1, row.audioReplies)
        assertEquals(2, row.subtitles.size)
        assertEquals(callId, store.callIdForBot(42, 99))
        val page = callDashboardPage(snapshot, CallDashboardFilter())
        assertTrue(page.contains("3000 / 3000 мс (n=1)"))
        assertTrue(page.contains("2 / 1 / 1"))
        assertFalse(page.contains("<unsafe>"))
        assertTrue(callDetailPage(row).contains("2 / 1 / 1"))
    }

    @Test
    fun keepsOpenAndUnansweredDistinctFromCompletedCall() {
        val events = listOf(
            CallEvent("open", "call_open", 42, start, callId = callId, state = "voice"),
            CallEvent("voice", "voice_received", 42, start.plusSeconds(1), callId = callId, messageId = 1),
            CallEvent("failed", "voice_outcome", 42, start.plusSeconds(2), callId = callId,
                messageId = 1, state = "ai_failed"),
        )
        val row = callDashboardRows(CallEventsSnapshot(events, false, 0)).single()
        assertEquals(null, row.closed)
        assertTrue(row.lastVoiceUnanswered)
        assertEquals(start.plusSeconds(1), row.dialogueEnd)
        assertTrue(row.failed)
    }

    @Test
    fun comparesModesWithoutTreatingOldCallsAsFree() {
        val job = "b".repeat(32)
        val jobAgain = "e".repeat(32)
        val free = "c".repeat(32)
        val old = "d".repeat(32)
        val events = listOf(
            CallEvent("job-open", "call_open", 42, start, callId = job, state = "button", scenarioKind = "job"),
            CallEvent("job-voice", "voice_received", 42, start.plusSeconds(2), callId = job, messageId = 1),
            CallEvent("job-speech", "voice_processed", 42, start.plusSeconds(3), callId = job, messageId = 1, seconds = 34.0),
            CallEvent("job-again-open", "call_open", 42, start.plusSeconds(10), callId = jobAgain,
                state = "button", scenarioKind = "job"),
            CallEvent("job-again-voice", "voice_received", 42, start.plusSeconds(12), callId = jobAgain, messageId = 3),
            CallEvent("job-again-speech", "voice_processed", 42, start.plusSeconds(13), callId = jobAgain,
                messageId = 3, seconds = 60.0),
            CallEvent("free-open", "call_open", 42, start.plusSeconds(20), callId = free, state = "voice", scenarioKind = "free"),
            CallEvent("free-voice", "voice_received", 42, start.plusSeconds(21), callId = free, messageId = 2),
            CallEvent("free-speech", "voice_processed", 42, start.plusSeconds(22), callId = free, messageId = 2, seconds = 11.0),
            CallEvent("old-open", "call_open", 77, start.plusSeconds(30), callId = old, state = "button"),
        )
        val snapshot = CallEventsSnapshot(events, false, 0)
        val stats = callModeStats(callDashboardRows(snapshot)).associateBy { it.mode }
        assertEquals(1, stats.getValue("job").users)
        assertEquals(2, stats.getValue("job").calls)
        assertEquals(1, stats.getValue("job").repeatUsers)
        assertEquals(1, stats.getValue("free").users)
        assertEquals(1, stats.getValue("unknown").calls)
        assertEquals(0, stats.getValue("unknown").dialogues)
        assertEquals(94.0, stats.getValue("job").speechSeconds)
        assertEquals("34.0 / 60.0 с (n=2)", speechPercentiles(stats.getValue("job").speechSample))
        val page = callDashboardPage(snapshot, CallDashboardFilter(mode = "job"))
        assertTrue(page.contains("Звонки (2)"))
        assertTrue(page.contains("Людей, попробовавших и обычный разговор, и ситуации: 1"))
        assertTrue(page.contains("Неизвестно"))
    }

    @Test
    fun attributesSituationChoicesToTheirOwnCalls() {
        val job = "b".repeat(32)
        val custom = "c".repeat(32)
        val events = listOf(
            CallEvent("menu-1", "scenario_menu_opened", 42, start, messageId = 10, botMessageId = 100),
            CallEvent("job-choice", "scenario_selected", 42, start.plusSeconds(1), botMessageId = 100, state = "job"),
            CallEvent("job-start", "start_pressed", 42, start.plusSeconds(1), callId = job, messageId = 100,
                state = "delivered", scenarioKind = "job"),
            CallEvent("job-open", "call_open", 42, start.plusSeconds(1), callId = job, messageId = 100,
                state = "button", scenarioKind = "job"),
            CallEvent("job-starter", "starter_delivered", 42, start.plusSeconds(2), callId = job),
            CallEvent("job-voice", "voice_received", 42, start.plusSeconds(3), callId = job, messageId = 11),
            CallEvent("menu-2", "scenario_menu_opened", 42, start.plusSeconds(10), messageId = 20, botMessageId = 200),
            CallEvent("custom-choice", "scenario_selected", 42, start.plusSeconds(11), botMessageId = 200, state = "custom"),
            CallEvent("invalid", "custom_description_invalid", 42, start.plusSeconds(12), messageId = 21, botMessageId = 200),
            CallEvent("valid", "custom_description_submitted", 42, start.plusSeconds(13), messageId = 22, botMessageId = 200),
            CallEvent("custom-start", "start_pressed", 42, start.plusSeconds(13), callId = custom, messageId = 22,
                state = "ready", scenarioKind = "custom"),
            CallEvent("custom-open", "call_open", 42, start.plusSeconds(13), callId = custom, messageId = 22,
                state = "button", scenarioKind = "custom"),
            CallEvent("menu-3", "scenario_menu_opened", 77, start.plusSeconds(20), messageId = 30, botMessageId = 300),
            CallEvent("back", "scenario_back", 77, start.plusSeconds(21), botMessageId = 300, state = "back"),
            CallEvent("stale", "scenario_selected", 88, start.plusSeconds(22), botMessageId = 400, state = "manager"),
        )
        val path = scenarioPathStats(events, callDashboardRows(CallEventsSnapshot(events, false, 0)))
        assertEquals(3, path.menus)
        assertEquals(2, path.menusWithSelection)
        assertEquals(1, path.backs)
        assertEquals(1, path.invalidDescriptions)
        assertEquals(listOf(1, 0, 1), path.choices.map { it.callsOpened })
        assertEquals(listOf(1, 0, 0), path.choices.map { it.dialogues })
        assertEquals(1, path.choices.last().validDescriptions)
    }

    @Test
    fun laterVoiceDoesNotOverwriteSituationMode() = runTest {
        val store = MemoryCallEventStore()
        store.record(CallEvent("open", "call_open", 42, start, callId = callId, state = "button", scenarioKind = "job"))
        store.record(CallEvent("open", "call_open", 42, start, callId = callId, state = "voice", scenarioKind = "free"))
        assertEquals("job", callDashboardRows(store.snapshot(start.minusSeconds(1))).single().mode)
    }
}
