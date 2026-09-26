package com.eliteteam.speakingcoach.telegram

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DailyReminderTest {

    private val day = LocalDate.of(2026, 9, 26)

    private fun moscow(hour: Int, minute: Int = 0) = ZonedDateTime.of(day.atTime(hour, minute), REMINDER_ZONE)

    @Test
    fun runsOnlyInsideTheEveningWindow() {
        assertFalse(shouldRunReminder(moscow(18, 59), null))
        assertTrue(shouldRunReminder(moscow(19, 0), null))
        assertTrue(shouldRunReminder(moscow(20, 59), null))
        assertFalse(shouldRunReminder(moscow(21, 0), null))
    }

    @Test
    fun runsOncePerMoscowDay() {
        assertFalse(shouldRunReminder(moscow(19, 30), day))
        assertTrue(shouldRunReminder(moscow(19, 30), day.minusDays(1)))
    }

    @Test
    fun convertsUtcClockToMoscow() {
        val utc = ZonedDateTime.of(day.atTime(16, 0), ZoneOffset.UTC)
        assertTrue(shouldRunReminder(utc, null))
    }

    @Test
    fun parsesOnlyTelegramSessionIds() {
        assertEquals(42L, reminderChatId("tg-42"))
        assertEquals(-100L, reminderChatId("tg--100"))
        assertNull(reminderChatId("app-42"))
        assertNull(reminderChatId("tg-abc"))
    }
}
