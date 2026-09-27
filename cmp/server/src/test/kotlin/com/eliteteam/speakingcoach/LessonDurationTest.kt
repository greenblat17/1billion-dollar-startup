package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.ai.LessonLengthRow
import com.eliteteam.speakingcoach.ai.LessonsSnapshot
import com.eliteteam.speakingcoach.ai.MetricsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LessonDurationTest {
    @Test
    fun speechDurationUsesMinutesAndSeconds() {
        assertEquals("2 минуты 1 сек", speechDuration(121))
        assertEquals("45 сек", speechDuration(45))
        assertEquals("2 минуты", speechDuration(120))
        assertEquals("1 минута", speechDuration(60))
        assertEquals("5 минут", speechDuration(300))
        assertEquals("21 минута 1 сек", speechDuration(21 * 60 + 1))
        assertEquals("0 сек", speechDuration(0))
    }

    @Test
    fun lessonsPageShowsUsernameAndDuration() {
        val html = lessonsPageHtml(
            MetricsSnapshot(
                timezone = "Europe/Moscow",
                day = "2026-09-27",
                promptTokens = 0,
                completionTokens = 0,
                tpm = 0,
                tps = 0.0,
                turns = 0,
                dau = 0,
                sttSeconds = 0.0,
                ttsChars = 0,
                lessons = LessonsSnapshot(
                    count = 1,
                    averageSeconds = 121,
                    totalSeconds = 121,
                    rows = listOf(
                        LessonLengthRow(
                            sessionId = "tg-1",
                            username = "alex",
                            name = "Alex Green",
                            startedAt = "2026-09-27T12:00:00+00:00",
                            speechSeconds = 121,
                        ),
                    ),
                ),
            ),
        )
        assertTrue(html.contains("Разговоры"))
        assertTrue(html.contains("@alex · Alex Green"))
        assertTrue(html.contains("2 минуты 1 сек"))
        assertTrue(html.contains("2026-09-27 15:00"))
    }
}
