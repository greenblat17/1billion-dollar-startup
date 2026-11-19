package com.eliteteam.speakingcoach.foundation

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrialSp24Test {
    @Test
    fun wrapupRequiresAllFifteenMinutesAfterActualStart() {
        val lateStart = 126_517L
        assertNull(sp24PhaseError("warmup", "conversation", null, lateStart))
        assertEquals("invalid_trial_phase", sp24PhaseError("warmup", "wrapup", null, lateStart))
        assertEquals("trial_conversation_in_progress",
            sp24PhaseError("conversation", "wrapup", lateStart, lateStart + 899_999))
        assertNull(sp24PhaseError("conversation", "wrapup", lateStart, lateStart + 900_000))
    }

    @Test
    fun oldSlotsKeepTheirDurationAndOnlySp24HasAnExtendedReconnectDeadline() {
        val start = Instant.parse("2026-11-19T10:00:00Z")
        val legacy = json("starts_at" to start.toString(), "duration_minutes" to 15)
        val v55 = json("starts_at" to start.toString(), "duration_minutes" to 30, "trial_version" to "v5.5")
        val sp24 = json("starts_at" to start.toString(), "duration_minutes" to 20, "trial_version" to "v5.5",
            "trial_reconnect_until" to start.plusSeconds(1260).toString())
        assertFalse(trialSp24(legacy))
        assertFalse(trialSp24(v55))
        assertTrue(trialSp24(sp24))
        assertEquals(start.plusSeconds(900), trialReconnectEnd(legacy))
        assertEquals(start.plusSeconds(1800), trialReconnectEnd(v55))
        assertEquals(start.plusSeconds(1200), slotEnd(sp24))
        assertEquals(start.plusSeconds(1260), trialReconnectEnd(sp24))
    }
}
