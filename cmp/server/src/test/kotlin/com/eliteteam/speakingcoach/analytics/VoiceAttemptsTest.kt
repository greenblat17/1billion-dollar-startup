package com.eliteteam.speakingcoach.analytics

import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceAttemptsTest {
    @Test
    fun countsOneTerminalResultPerTelegramMessageAndSeparatesPending() = runTest {
        val store = MemoryVoiceAttemptStore()
        val now = Instant.now()
        val first = VoiceAttempt(123, 1, now.minusSeconds(30), eligible = true)
        store.record(first)
        store.record(first.copy(outcome = "delivered", terminalAt = now, totalMs = 100))
        store.record(first.copy(outcome = "ai_failed", stage = "stt", reason = "network"))
        store.record(VoiceAttempt(123, 2, now.minusSeconds(20), eligible = true))
        store.record(VoiceAttempt(456, 1, now.minusSeconds(10), eligible = false, outcome = "not_eligible"))
        val report = store.report(now)
        assertEquals(1, report.days.first().delivered)
        assertEquals(0, report.days.first().failed)
        assertEquals(1, report.days.first().pending)
        assertEquals(1, report.days.first().notEligible)
        assertEquals(100, report.todayLatencies.single { it.stage == "total" }.p95Ms)
        assertEquals(voiceAttemptId(123, 1), first.attemptId)
    }

    @Test
    fun countsAffectedChatsAndThreeConsecutiveFailures() = runTest {
        val store = MemoryVoiceAttemptStore()
        val now = Instant.now()
        repeat(3) { offset ->
            store.record(VoiceAttempt(123, offset.toLong(), now.minusSeconds((3 - offset).toLong()),
                username = "alex", eligible = true, outcome = "ai_failed", stage = "stt", reason = "network",
                totalMs = (offset + 1L) * 100))
        }
        val report = store.report(now)
        assertEquals(3, report.days.first().failed)
        assertEquals(1, report.affectedChats)
        assertEquals(3, report.affected.single().failures)
        assertEquals(3, report.repeated.single().failures)
        assertEquals(300, report.latencies.single { it.stage == "total" }.p95Ms)
        assertTrue(report.breakdown.any { it.stage == "stt" && it.reason == "network" && it.count == 3 })
        store.record(VoiceAttempt(123, 3, now, eligible = true, outcome = "delivered"))
        assertTrue(store.report(now).repeated.isEmpty())
    }

    @Test
    fun staleInFlightAttemptIsVisibleAsUnknown() = runTest {
        val store = MemoryVoiceAttemptStore()
        val now = Instant.now()
        store.record(VoiceAttempt(123, 9, now.minusSeconds(200)))
        val report = store.report(now)
        assertEquals(1, report.days.first().failed)
        assertEquals("unknown", report.recent.single().outcome)
    }

    @Test
    fun receivesAcrossUtcMidnightUseMoscowDate() = runTest {
        val store = MemoryVoiceAttemptStore()
        val at = Instant.parse("2026-10-05T21:30:00Z")
        store.record(VoiceAttempt(123, 10, at, eligible = true, outcome = "delivered"))
        val report = store.report(at.plusSeconds(1))
        assertEquals("2026-10-06", report.days.first().day)
        assertEquals(1, report.days.first().delivered)
    }

    @Test
    fun recorderDrainsQueuedWritesOnClose() = runTest {
        val store = MemoryVoiceAttemptStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = VoiceAttemptRecorder(store, scope)
        val now = Instant.now()
        recorder.record(VoiceAttempt(123, 11, now, eligible = true))
        recorder.record(VoiceAttempt(123, 11, now, eligible = true, outcome = "delivered"))
        recorder.close()
        scope.cancel()
        assertEquals(1, store.report(now).days.first().delivered)
    }
}
