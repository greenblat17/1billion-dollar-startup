package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.TrialBooking
import java.util.zip.Deflater
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class TutorBookingDeliveryTest {
    private val booking = TrialBooking("lesson", "student", "scheduled", "2026-11-14T16:00:00Z", "Europe/Moscow", 2)
    @Test fun sendsOneWidgetWithCompatibleCaptionAndUpdatesSameMessage() = runTest {
        val calls = mutableListOf<String>()
        assertEquals(72L, deliverBookingCard(1, booking, "confirmation", null, true) {
            calls += it.method()
            val request = it as BookingWidgetRequest
            assertEquals("confirmation", request.caption)
            assertTrue(request.html!!.contains("textContent"))
            assertTrue(request.data!!.contains("19:00"))
            buildJsonObject { put("message_id", 72) }
        })
        assertEquals(72L, deliverBookingCard(1, booking.copy(status="cancelled", version=3), "cancelled", 72, true) {
            calls += it.method()
            val request = it as BookingWidgetRequest
            assertEquals(72L, request.messageId)
            if (it.method() == "editMessageCaption") assertEquals("[]", request.markup!!["inline_keyboard"].toString())
            JsonPrimitive(true)
        })
        assertEquals(listOf("sendWidget", "editMessageWidgetData", "editMessageCaption"), calls)
    }
    @Test fun uncertainSendOrEditNeverFallsBackToAnotherSend() = runTest {
        for (previous in listOf(null, 72L)) {
            var calls = 0
            assertFailsWith<IllegalStateException> {
                deliverBookingCard(1, booking, "confirmation", previous, true) { calls++; error("connection lost") }
            }
            assertEquals(1, calls)
        }
    }
    @Test fun legacyCardStillUsesTextEditAndWidgetFitsTelegramLimits() = runTest {
        deliverBookingCard(1, booking, "confirmation", 72, false) {
            assertEquals("editMessageText", it.method()); JsonPrimitive(true)
        }
        val bytes = bookingWidgetHtml.toByteArray()
        val compressor = Deflater(9)
        try { compressor.setInput(bytes); compressor.finish(); assertTrue(compressor.deflate(ByteArray(32768)) <= 4096) }
        finally { compressor.end() }
        assertTrue(bytes.size <= 32768)
        assertTrue(bookingWidgetData(booking).toByteArray().size <= 1024)
        val markup = tutorBookingMarkup(true, "a b", "https://mini.test/miniapp", calendar=true).toString()
        assertTrue(markup.contains("view=calendar&lesson=a+b"))
    }
}
