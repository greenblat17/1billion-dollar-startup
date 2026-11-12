package com.eliteteam.speakingcoach.telegram

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TutorTextFormattingTest {
    @Test fun roadmapHeadingBecomesTelegramBoldInWirePayload() {
        val part = tutorTextParts("Маршрут готов!\n\n**1. Аудирование — приоритет №1**\nСлушаем A&B <video>.").single()
        assertEquals("Маршрут готов!\n\n1. Аудирование — приоритет №1\nСлушаем A&B <video>.", part.text)
        val entity = part.entities.single()
        assertEquals("bold", entity.type)
        assertEquals("1. Аудирование — приоритет №1", part.text.substring(entity.offset, entity.offset + entity.length))
        val request = TutorTextRequest(123, part.text, part.entities,
            buildJsonObject { put("remove_keyboard", true) }, TutorReplyParameters(45, true))
        val wire = Json.parseToJsonElement(Json.encodeToString(request)).jsonObject
        assertEquals("bold", wire.getValue("entities").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("45", wire.getValue("reply_parameters").jsonObject.getValue("message_id").jsonPrimitive.content)
        assertFalse("parse_mode" in wire)
    }

    @Test fun longBoldTextKeepsEntitiesAndEmojiAcrossMessageBoundary() {
        val body = "a".repeat(3499) + "😀" + "б".repeat(20)
        val parts = tutorTextParts("**$body**")
        assertEquals(body, parts.joinToString("") { it.text })
        assertEquals(2, parts.size)
        parts.forEach {
            assertTrue(it.text.length <= 3500)
            assertFalse(it.text.last().isHighSurrogate())
            assertFalse(it.text.first().isLowSurrogate())
            assertEquals(listOf(TutorTextEntity("bold", 0, it.text.length)), it.entities)
        }
    }

    @Test fun emojiBeforeHeadingUsesUtf16Offsets() {
        val part = tutorTextParts("😀 **Цель**").single()
        assertEquals(listOf(TutorTextEntity("bold", 3, 4)), part.entities)
    }

    @Test fun incompleteMarkupAndOrdinarySpecialCharactersStayLiteral() {
        val source = "**Не закрыто\nA_B <tag> & 2 * 3 [слово](пример)"
        val part = tutorTextParts(source).single()
        assertEquals(source, part.text)
        assertTrue(part.entities.isEmpty())
    }

    @Test fun codeDoesNotInterpretBoldMarkers() {
        val part = tutorTextParts("`**literal**` и ```**block**``` затем **жирный**").single()
        assertEquals("**literal** и **block** затем жирный", part.text)
        assertEquals(listOf("code", "pre", "bold"), part.entities.map { it.type })
    }
}
