package com.eliteteam.speakingcoach.telegram

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReminderMessagesTest {

    private val day = LocalDate.of(2026, 9, 26)

    @Test
    fun templateIdsAreUniqueAndStable() {
        val ids = (REMINDER_TEMPLATES + STREAK_REMINDER_TEMPLATES).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { Regex("^[a-z0-9_]{1,64}$").matches(it) })
        assertEquals("weekend_plan", reminderTemplateById("weekend_plan")?.id)
        assertEquals("smile_today", reminderTemplateById("smile_today")?.id)
        assertNull(reminderTemplateById("missing"))
        assertFalse(REMINDER_TEMPLATES.any { it.id == "smile_today" })
        assertTrue(STREAK_REMINDER_TEMPLATES.any { it.id == "smile_today" })
    }

    @Test
    fun sameUserGetsNoRepeatWithinThePoolSize() {
        val ids = REMINDER_TEMPLATES.indices.map { offset -> reminderTemplate(42, day.plusDays(offset.toLong())).id }
        assertEquals(REMINDER_TEMPLATES.size, ids.toSet().size)
    }

    @Test
    fun neighbouringUsersGetDifferentTextsOnTheSameDay() {
        assertNotEquals(reminderTemplate(1, day), reminderTemplate(2, day))
    }

    @Test
    fun negativeChatIdsStillPickATemplate() {
        assertTrue(REMINDER_TEMPLATES.contains(reminderTemplate(-100123, day)))
    }

    @Test
    fun streakOfTwoUsesTheStreakPool() {
        assertTrue(STREAK_REMINDER_TEMPLATES.contains(reminderTemplate(42, day, streak = 2)))
        assertTrue(REMINDER_TEMPLATES.contains(reminderTemplate(42, day, streak = 1)))
        val ids = STREAK_REMINDER_TEMPLATES.indices.map { offset ->
            reminderTemplate(42, day.plusDays(offset.toLong()), streak = 3).id
        }
        assertEquals(STREAK_REMINDER_TEMPLATES.size, ids.toSet().size)
    }

    @Test
    fun streakSlotsAreFilled() {
        val template = reminderTemplateById("streak_next")!!
        val text = renderReminder(template, "Alex", streak = 4)
        assertFalse("{streak}" in text)
        assertFalse("{next}" in text)
        assertFalse("{name}" in text)
        assertTrue("Day 5" in text)
        assertTrue(", Alex" in text)
        assertEquals(
            "5 days in a row!",
            renderReminder(reminderTemplateById("streak_tonight")!!, null, streak = 5).substringBefore(" Don't"),
        )
    }

    @Test
    fun nameSlotIsFilledOrDroppedCleanly() {
        REMINDER_TEMPLATES.forEach { template ->
            val named = renderReminder(template, "Alex")
            val anonymous = renderReminder(template, " ")
            assertFalse("{name}" in named)
            assertFalse("{name}" in anonymous)
            assertFalse(" ," in anonymous)
            assertFalse(",!" in anonymous)
            if (named != anonymous) {
                assertTrue(", Alex" in named)
            }
        }
    }

    @Test
    fun firstNameIsTheFirstWord() {
        assertEquals("Alex", reminderFirstName("  Alex   Green "))
        assertNull(reminderFirstName("   "))
        assertNull(reminderFirstName(null))
    }
}
