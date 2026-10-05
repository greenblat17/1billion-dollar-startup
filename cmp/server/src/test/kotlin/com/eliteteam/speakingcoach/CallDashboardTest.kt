package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.analytics.CallEvent
import com.eliteteam.speakingcoach.analytics.CallEventsSnapshot
import com.eliteteam.speakingcoach.analytics.MemoryCallEventStore
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
}
