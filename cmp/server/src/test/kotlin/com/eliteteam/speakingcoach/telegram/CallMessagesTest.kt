package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.CallReviewResponse
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.KeyboardButtonStyle
import dev.inmo.tgbotapi.types.message.textsources.TextSourcesList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallMessagesTest {
    @Test
    fun clockShowsTodayAgainstTheDailyGoal() {
        assertEquals("🎯 8:24 / 10:00", callClockLabel(504.0, 600.0))
        assertEquals("🎯 8:24 today", callClockLabel(504.0, 0.0))
        assertEquals("🎯 12:40 / 10:00", callClockLabel(760.0, 600.0))
        assertEquals("That's your 10 minutes today.", callGoalReached(600.0))
    }

    @Test
    fun keyboardKeepsSubtitlesAndEndOnSeparateRows() {
        val rows = callKeyboard(90.0, 600.0, spoken = true).keyboard
        val top = rows[0].map { (it as CallbackDataInlineKeyboardButton).callbackData }
        val end = rows[1].single() as CallbackDataInlineKeyboardButton
        assertEquals(listOf(CALL_CLOCK_CALLBACK, SPOKEN_TEXT_CALLBACK), top)
        assertEquals("End call", end.text)
        assertEquals(KeyboardButtonStyle.Danger, end.style)
        assertEquals(CallCallback("end", ""), parseCallCallback(end.callbackData))
        val linkedEnd = callKeyboard(90.0, 600.0, spoken = true, callId = "a".repeat(32))
            .keyboard.last().single() as CallbackDataInlineKeyboardButton
        assertEquals(CallCallback("end", "a".repeat(32)), parseCallCallback(linkedEnd.callbackData))
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
    fun progressCardRecapsTheConversation() {
        val up = callProgressMessage(
            CallReviewResponse(
                recap = "That was fun. We talked about your startup and the launch.",
                cefr = "B1",
                overallScore = 54,
                previousScore = 52,
                todaySeconds = 51.0,
                goalSeconds = 300.0,
                streak = 2,
            ),
        ).plain()
        assertTrue(up.contains("🎯 0:51 / 5:00 today\n\nThat was fun. We talked about your startup and the launch.\n\n🔥 2 day streak"))
        assertTrue(!up.contains("→"))
        assertTrue(!up.contains("B1"))
        assertTrue(!up.contains("Nice"))
        assertTrue(!up.contains("54"))

        val offer = callProgressMessage(
            CallReviewResponse(overallScore = 52, previousScore = 52, cefr = "B1", streak = 2),
            offerReminder = true,
        ).plain()
        assertTrue(offer.endsWith(REMINDER_OFFER))
        val withoutGoal = callProgressMessage(
            CallReviewResponse(todaySeconds = 51.0, goalSeconds = 0.0, recap = "We discussed your work."),
        ).plain()
        assertTrue(withoutGoal.startsWith("🎯 0:51 today\n\nWe discussed your work."))
    }

    @Test
    fun callOpensOnlyAfterANumericLevelAndAChosenGoal() {
        assertEquals("onboarding", callGate("active", 52, 10))
        assertEquals("legacy", callGate("exempt", null, null))
        assertEquals("legacy", callGate("completed", null, null, legacyUser = true))
        assertEquals("need-onboarding", callGate("completed", null, null))
        assertEquals("need-goal", callGate("completed", 52, null))
        assertEquals("open", callGate("completed", 52, 10))
        assertEquals("open", callGate("completed", 52, 0))
        assertEquals("open", callGate("exempt", 52, 5))
    }

    @Test
    fun startCallButtonUsesPersistentReplyKeyboardAndExactText() {
        val keyboard = startCallKeyboard()
        assertEquals(START_CALL_BUTTON, keyboard.keyboard.single().single().text)
        assertEquals(KeyboardButtonStyle.Success, keyboard.keyboard.single().single().style)
        assertEquals(true, keyboard.persistent)
        assertTrue(isStartCallButton(START_CALL_BUTTON))
        assertTrue(!isStartCallButton("Start call"))
        assertEquals("Speaky is joining the chat… 💙", START_CALL_CONNECTING)
    }

    private fun TextSourcesList.plain(): String = joinToString("") { it.source }
}
