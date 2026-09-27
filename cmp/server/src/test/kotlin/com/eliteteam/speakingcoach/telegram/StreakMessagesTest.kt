package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.StreakProfileResponse
import java.io.ByteArrayInputStream
import java.time.LocalDate
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreakMessagesTest {
    @Test
    fun saturdayWeekMarksMissedTodayAndFuture() {
        val cells = weekCells(
            listOf(true, true, false, true, true, true, false),
            today = LocalDate.of(2026, 9, 26),
        )
        assertContentEquals(
            listOf(
                WeekCell.Done,
                WeekCell.Missed,
                WeekCell.Done,
                WeekCell.Done,
                WeekCell.Done,
                WeekCell.TodayOpen,
                WeekCell.Future,
            ),
            cells,
        )
    }

    @Test
    fun kickoffCaptionNamesTheDay() {
        assertEquals(
            "Day 3. Glad you're here. Let's talk.",
            streakKickoffCaption(3),
        )
    }

    @Test
    fun profileCaptionNamesTheCount() {
        assertEquals(
            "Current streak: 3 days",
            streakProfileCaption(StreakProfileResponse(current = 3)),
        )
        assertEquals(
            "Current streak: 1 day",
            streakProfileCaption(StreakProfileResponse(current = 1)),
        )
    }

    @Test
    fun emptyStreakCaptionHasNoFlame() {
        val text = streakProfileCaption(StreakProfileResponse(current = 0))
        assertEquals("No streak yet. Send me a voice message to start one!", text)
        assertFalse("🔥" in text)
    }

    @Test
    fun weekImageIsAPngOfTheCard() {
        val bytes = streakWeekPng(
            weekCells(
                listOf(true, true, false, true, true, true, false),
                today = LocalDate.of(2026, 9, 26),
            ),
        )
        assertTrue(bytes.size > 8)
        assertEquals(0x89, bytes[0].toInt() and 0xff)
        assertEquals('P'.code, bytes[1].toInt() and 0xff)
        assertEquals('N'.code, bytes[2].toInt() and 0xff)
        assertEquals('G'.code, bytes[3].toInt() and 0xff)
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(808, image.width)
        assertEquals(200, image.height)
    }

    @Test
    fun streakCommandIsRecognizedWithABotSuffix() {
        assertTrue(isStreakCommand("/streak"))
        assertTrue(isStreakCommand("/streak@speaky_english_buddy_bot"))
        assertFalse(isStreakCommand("/start"))
        assertFalse(isStreakCommand("/streaks"))
    }
}
