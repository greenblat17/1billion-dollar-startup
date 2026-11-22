package com.eliteteam.speakingcoach.foundation

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrialRemindersTest {
    private fun lesson(start: String, agreed: String = "2026-11-13T00:00:00Z", zone: String = "Europe/Moscow") =
        json("starts_at" to start, "updated_at" to agreed, "timezone" to zone)

    @Test fun localMorningUsesLessonDateAndSkipsLateBookingsAndEarlyLessons() {
        assertEquals(Instant.parse("2026-11-14T05:00:00Z"), trialReminderTimes(lesson("2026-11-14T16:00:00Z"))["morning"])
        assertFalse("morning" in trialReminderTimes(lesson("2026-11-14T16:00:00Z", "2026-11-14T05:00:01Z")))
        assertFalse("morning" in trialReminderTimes(lesson("2026-11-14T04:00:00Z")))
        assertEquals(Instant.parse("2026-11-14T13:00:00Z"), trialReminderTimes(lesson("2026-11-14T23:00:00Z", zone="America/New_York"))["morning"])
    }
    @Test fun coincidenceAndImmediateBookingProduceOnlyOneNotice() {
        assertEquals(setOf("reminder", "invitation"), trialReminderTimes(lesson("2026-11-14T06:00:00Z")).keys)
        assertEquals(setOf("invitation"), trialReminderTimes(lesson("2026-11-14T05:00:00Z", "2026-11-14T05:00:00Z")).keys)
    }
    @Test fun staleMorningDoesNotCatchUpAndReminderExpiresAtStart() {
        val due = Instant.parse("2026-11-14T05:00:00Z")
        val start = due.plusSeconds(3600)
        assertTrue(scheduledNoticeApplies("morning", due, start, due.plusSeconds(59)))
        assertFalse(scheduledNoticeApplies("morning", due, start, due.plusSeconds(60)))
        assertFalse(scheduledNoticeApplies("reminder", due, start, start))
        assertTrue(scheduledNoticeApplies("invitation", start, start, start))
    }
    @Test fun reminderIsOneHourBeforeAndLateAgreementDoesNotSendIt() {
        assertEquals(Instant.parse("2026-11-14T15:00:00Z"), trialReminderTimes(lesson("2026-11-14T16:00:00Z"))["reminder"])
        assertFalse("reminder" in trialReminderTimes(lesson("2026-11-14T16:00:00Z", "2026-11-14T15:30:00Z")))
    }
    @Test fun dstMorningUsesOffsetOnLessonDay() {
        assertEquals(Instant.parse("2026-03-29T06:00:00Z"), trialReminderTimes(lesson("2026-03-29T12:00:00Z", "2026-03-28T00:00:00Z", "Europe/Berlin"))["morning"])
    }
}
