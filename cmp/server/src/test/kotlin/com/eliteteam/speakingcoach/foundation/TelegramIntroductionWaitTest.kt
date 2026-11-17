package com.eliteteam.speakingcoach.foundation

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramIntroductionWaitTest {
    @Test
    fun positiveSubMillisecondWaitNeverBecomesReadyBeforeDueTime() {
        val now = Instant.parse("2026-11-17T00:00:00.123456100Z")
        val due = Instant.parse("2026-11-17T00:00:00.123456500Z")
        assertEquals(1L, introductionWaitMillis(now, due))
        assertEquals(0L, introductionWaitMillis(due, due))
        assertEquals(0L, introductionWaitMillis(due.plusNanos(1), due))
    }
}
