package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.CallReviewResponse
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.message.textsources.TextSourcesList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallMessagesTest {
    @Test
    fun clockShowsTodayAgainstTheDailyGoal() {
        assertEquals("🎯 8:24 / 10:00", callClockLabel(504.0, 600.0))
        assertEquals("🎯 12:40 / 10:00", callClockLabel(760.0, 600.0))
        assertEquals("That's your 10 minutes today.", callGoalReached(600.0))
    }

    @Test
    fun keyboardKeepsSubtitlesAndEndOnSeparateRows() {
        val rows = callKeyboard(90.0, 600.0, spoken = true).keyboard
        val top = rows[0].map { (it as CallbackDataInlineKeyboardButton).callbackData }
        val end = rows[1].single() as CallbackDataInlineKeyboardButton
        assertEquals(listOf(CALL_CLOCK_CALLBACK, SPOKEN_TEXT_CALLBACK), top)
        assertEquals("End conversation 📞", end.text)
        assertEquals(CallCallback("end", ""), parseCallCallback(end.callbackData))
        assertTrue(end.callbackData.encodeToByteArray().size <= 64)
    }

    @Test
    fun slidesAndReturnButtonsStayInsideTelegramCallbackLimit() {
        val callId = "a".repeat(32)
        for (action in listOf("grammar", "vocab", "fluency", "progress")) {
            val button = callSlideKeyboard(action, callId).keyboard.single().single() as CallbackDataInlineKeyboardButton
            assertEquals(CallCallback(action, callId), parseCallCallback(button.callbackData))
            assertTrue(button.callbackData.encodeToByteArray().size <= 64)
        }
        assertEquals(CallCallback("review", callId), parseCallCallback(callYesterdayKeyboard(callId).keyboard.single().single().let { (it as CallbackDataInlineKeyboardButton).callbackData }))
        assertNull(parseCallCallback("call:grammar:short"))
        assertNull(parseCallCallback("ob:level:$callId"))
    }

    @Test
    fun returnKeyboardOffersProfileAndFinish() {
        val row = callReturnKeyboard().keyboard.single().map { it as CallbackDataInlineKeyboardButton }
        assertEquals(listOf("Profile", "Finish for today"), row.map { it.text })
        assertEquals(listOf("call:profile", "call:bye"), row.map { it.callbackData })
    }

    @Test
    fun progressLineShowsTheSmallStepFromTheStoredLevel() {
        val up = callProgressMessage(
            CallReviewResponse(
                overallScore = 53,
                previousScore = 52,
                cefr = "B1",
                todaySeconds = 51.0,
                goalSeconds = 300.0,
                streak = 2,
            ),
        ).plain()
        assertTrue(up.contains("🎯 0:51 / 5:00 today"))
        assertTrue(up.contains("52 → 53 ↑"))
        assertTrue(up.contains("Nice — your speaking score went up."))
        assertTrue(up.contains("B1 · Intermediate"))
        assertTrue(up.contains("🔥 2 day streak"))
        assertTrue(!up.contains("80"))

        val down = callProgressMessage(
            CallReviewResponse(
                overallScore = 50,
                previousScore = 52,
                levelText = "The pauses got longer than usual.",
                cefr = "B1",
                streak = 2,
            ),
        ).plain()
        assertTrue(down.contains("52 → 50 ↓"))
        assertTrue(down.contains("This one came out a little lower."))
        assertTrue(down.contains("The pauses got longer than usual."))

        val same = callProgressMessage(
            CallReviewResponse(overallScore = 52, previousScore = 52, cefr = "B1"),
        ).plain()
        assertTrue(same.contains("Speaking score: 52"))
        assertTrue(same.contains("B1 · Intermediate"))
        assertTrue(!same.contains("→"))
        assertTrue(!same.contains("Nice"))
        assertTrue(!same.contains("/remind"))

        val offer = callProgressMessage(
            CallReviewResponse(overallScore = 52, previousScore = 52, cefr = "B1", streak = 2),
            offerReminder = true,
        ).plain()
        assertTrue(offer.endsWith(REMINDER_OFFER))
    }

    @Test
    fun callOpensOnlyAfterANumericLevelAndAChosenGoal() {
        assertEquals("onboarding", callGate("active", 52, 10))
        assertEquals("need-onboarding", callGate("exempt", null, null))
        assertEquals("need-goal", callGate("completed", 52, null))
        assertEquals("open", callGate("completed", 52, 10))
        assertEquals("open", callGate("exempt", 52, 5))
    }

    private fun TextSourcesList.plain(): String = joinToString("") { it.source }
}
