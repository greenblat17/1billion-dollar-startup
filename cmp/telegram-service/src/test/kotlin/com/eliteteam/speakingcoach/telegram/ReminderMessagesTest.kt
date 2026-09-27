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
        val ids = REMINDER_TEMPLATES.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { Regex("^[a-z0-9_]{1,64}$").matches(it) })
        assertEquals("weekend_plan", reminderTemplateById("weekend_plan")?.id)
        assertNull(reminderTemplateById("missing"))
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
