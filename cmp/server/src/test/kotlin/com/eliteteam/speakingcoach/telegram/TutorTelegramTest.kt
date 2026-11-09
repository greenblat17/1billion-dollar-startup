package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.TutorResponse
import com.eliteteam.speakingcoach.ai.TutorRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TutorTelegramTest {
    @Test fun disabledButtonHasNoActionOrUrl() {
        val button = tutorBookingMarkup(true)["inline_keyboard"]!!.jsonArray[0].jsonArray[0].jsonObject
        assertEquals(setOf("text", "disabled"), button.keys)
        assertEquals("{}", button["disabled"].toString())
        assertEquals(0, tutorBookingMarkup(false)["inline_keyboard"]!!.jsonArray.size)
    }

    @Test fun nativeCardSerializationKeepsDisabledObject() {
        val request = TutorCardRequest(123, "Пробная встреча", tutorBookingMarkup(true), 45)
        val json = Json.encodeToString(request)
        assertTrue(json.contains("\"disabled\":{}"))
        assertFalse(json.contains("callback_data"))
        assertEquals("editMessageText", request.method())
        assertEquals("sendMessage", request.copy(messageId = null).method())
    }

    @Test fun tutorContractSupportsPlainConversationAfterBooking() {
        val json = Json { ignoreUnknownKeys = true }
        val reply = json.decodeFromString<TutorResponse>("""{"conversationId":"same","reply":"Давай обсудим","booking":null,"degraded":false}""")
        assertEquals(null, reply.booking)
        assertEquals("same", reply.conversationId)
        val request = TutorRequest("tg-1", "message:2", "А если я растеряюсь?", 100.0)
        assertEquals(request, json.decodeFromString<TutorRequest>(json.encodeToString(request)))
    }
}
