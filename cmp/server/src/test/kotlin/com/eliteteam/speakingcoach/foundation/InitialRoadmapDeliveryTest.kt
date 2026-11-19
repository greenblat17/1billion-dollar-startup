package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.telegram.TutorTextRequest
import com.eliteteam.speakingcoach.telegram.telegramRequestJson
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InitialRoadmapDeliveryTest {
    @Test fun longRoadmapKeepsTasksTogetherAndWithinTelegramLimit() {
        val header = "**Твой предварительный маршрут**\nЦель: разговор"
        val tasks = (1..80).map { number ->
            "$number. Разговор · B1 · Тема $number\nСтатус: ещё не начата (0%).\nЗачем: проверим гипотезу."
        }
        val body = (listOf(header) + tasks + "Обсудим следующие занятия здесь.").joinToString("\n\n")
        val parts = roadmapParts(body)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.text.length <= 3000 })
        assertEquals(body.replace("**", ""), parts.joinToString("\n\n") { it.text })
        assertTrue(parts.last().text.contains("Обсудим следующие занятия"))
    }

    @Test fun intermediatePartOmitsTelegramMarkup() {
        val request = telegramRequestJson.encodeToString(TutorTextRequest(123, "Первая часть", emptyList(), null))
        assertTrue("reply_markup" !in request)
    }
}
