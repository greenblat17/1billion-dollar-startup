package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.TrialBooking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class TutorBookingDeliveryTest {
    private val booking = TrialBooking("lesson", "student", "scheduled", "2026-11-14T16:00:00Z", "Europe/Moscow", 2)
    @Test fun timingComesFromStructuredBookingAndCancellationStillGetsConfirmation() {
        val decoded = kotlinx.serialization.json.Json.decodeFromString<TrialBooking>(
            """{"id":"lesson","learnerId":"student","status":"scheduled","startsAtUtc":"2026-11-14T16:00:00Z","timezone":"UTC","version":1,"timing":"now"}""")
        assertTrue(decoded.startsImmediately)
        assertTrue(!booking.startsImmediately)
        assertTrue(!decoded.copy(status = "cancelled").startsImmediately)
    }
    @Test fun sendsOneNativeConfirmationAndCanStillClearOldWidgetButtons() = runTest {
        val calls = mutableListOf<String>()
        assertEquals(72L, deliverBookingCard(1, booking, "confirmation", null, false) {
            calls += it.method()
            val request = it as TutorCardRequest
            assertEquals("confirmation", request.text)
            assertEquals(null, request.messageId)
            assertTrue(request.markup.toString().contains("Начать урок"))
            buildJsonObject { put("message_id", 72) }
        })
        assertEquals(72L, deliverBookingCard(1, booking.copy(status="cancelled", version=3), "cancelled", 72, true) {
            calls += it.method()
            val request = it as BookingWidgetRequest
            assertEquals(72L, request.messageId)
            if (it.method() == "editMessageCaption") {
                assertEquals("", request.caption)
                assertEquals("[]", request.markup!!["inline_keyboard"].toString())
            }
            JsonPrimitive(true)
        })
        assertEquals(listOf("sendMessage", "editMessageWidgetData", "editMessageCaption"), calls)
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
    @Test fun textCardUsesTextEditAndCalendarUsesSeparateLink() = runTest {
        deliverBookingCard(1, booking, "confirmation", 72, false) {
            assertEquals("editMessageText", it.method()); JsonPrimitive(true)
        }
        val markup = tutorBookingMarkup(true, "a b", "https://mini.test/miniapp", calendar=true).toString()
        assertTrue(markup.contains("view=calendar&lesson=a+b"))
        assertTrue(markup.contains("Начать урок"))
        assertTrue(markup.contains("https://mini.test/miniapp?lesson=a+b"))
        assertEquals(2, kotlinx.serialization.json.Json.parseToJsonElement(markup).jsonObject["inline_keyboard"]!!.let {
            (it as kotlinx.serialization.json.JsonArray).size
        })
    }
    @Test fun compactWidgetShowsLocalRangeAndHandlesMidnight() {
        val data = kotlinx.serialization.json.Json.parseToJsonElement(bookingWidgetData(booking)).jsonObject
        assertEquals("14", data["day"]!!.jsonPrimitive.content)
        assertTrue(data["date"]!!.jsonPrimitive.content.contains("14 ноября 2026"))
        assertEquals("19:00–19:15 (МСК)", data["time"]!!.jsonPrimitive.content)
        assertEquals("Суббота · Саша", data["weekday"]!!.jsonPrimitive.content)
        val midnight = bookingWidgetData(booking.copy(startsAtUtc = "2026-11-14T20:55:00Z"))
        assertTrue(midnight.contains("23:55–15 ноября 00:10 (МСК)"))
    }

}
