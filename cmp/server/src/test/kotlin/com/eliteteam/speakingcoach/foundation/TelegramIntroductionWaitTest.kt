package com.eliteteam.speakingcoach.foundation

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramIntroductionWaitTest {
    @Test
    fun positiveSubMillisecondWaitNeverBecomesReadyBeforeDueTime() {
        val now = Instant.parse("2026-11-17T00:00:00.123456100Z")
        val due = Instant.parse("2026-11-17T00:00:00.123456500Z")
        assertEquals(1L, introductionWaitMillis(now, due))
        assertEquals(0L, introductionWaitMillis(due, due))
        assertEquals(0L, introductionWaitMillis(due.plusNanos(1), due))
    }
    @Test
    fun pauseReflectsPrecedingMessageAndIsBounded() {
        val (short, medium, long) = TELEGRAM_INTRO_TEXTS.map(::introductionPauseMillis)
        assertTrue(short in 1000L..2000L)
        assertTrue(short < medium && medium < long)
        assertEquals(6500L, introductionPauseMillis("слово ".repeat(200)))
    }
}
