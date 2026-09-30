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
    fun returnKeyboardLeadsWithTomorrowOnlyAfterTheGoal() {
        val early = callReturnKeyboard(goalMet = false).keyboard.single().map { (it as CallbackDataInlineKeyboardButton).callbackData }
        val done = callReturnKeyboard(goalMet = true).keyboard.single().map { (it as CallbackDataInlineKeyboardButton).callbackData }
        assertEquals(listOf("call:talk", "call:bye"), early)
        assertEquals(listOf("call:bye", "call:talk"), done)
    }

    @Test
    fun progressLineShowsTheSmallStepFromTheStoredLevel() {
        val text = callProgressMessage(
            CallReviewResponse(
                overallScore = 54,
                previousScore = 52,
                cefr = "B1",
                todaySeconds = 504.0,
                goalSeconds = 600.0,
                streak = 4,
            ),
        ).plain()
        assertTrue(text.contains("8:24 / 10:00"))
        assertTrue(text.contains("52 → 54"))
        assertTrue(text.contains("B1 · Intermediate"))
        assertTrue(text.contains("Day 4"))
        assertTrue(!text.contains("80"))
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
