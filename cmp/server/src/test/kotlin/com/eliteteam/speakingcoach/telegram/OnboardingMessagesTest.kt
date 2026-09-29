package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.OnboardingExample
import com.eliteteam.speakingcoach.ai.OnboardingFluency
import com.eliteteam.speakingcoach.ai.OnboardingReview
import com.eliteteam.speakingcoach.ai.OnboardingSkill
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.message.textsources.TextSourcesList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingMessagesTest {
    @Test
    fun buttonsCarryTheAttemptAndFitTelegramLimit() {
        val run = "a".repeat(32)
        for (action in listOf("begin", "retry", "continue", "results", "vocab", "fluency", "finish", "talk", "bye", "m5", "m10", "m15")) {
            val button = onboardingKeyboard(action, run).keyboard.single().single() as CallbackDataInlineKeyboardButton
            assertEquals(OnboardingCallback(action, run), parseOnboardingCallback(button.callbackData))
            assertTrue(button.callbackData.encodeToByteArray().size <= 64)
        }
        assertNull(parseOnboardingCallback("ob:reset:$run"))
        assertNull(parseOnboardingCallback("ob:begin:another:chat"))
        assertNull(parseOnboardingCallback("ob:begin:"))
        assertNull(parseOnboardingCallback(SPOKEN_TEXT_CALLBACK))
        val spoken = spokenTextKeyboard().keyboard.single().single() as CallbackDataInlineKeyboardButton
        assertEquals(SPOKEN_TEXT_BUTTON, spoken.text)
        assertEquals(SPOKEN_TEXT_CALLBACK, spoken.callbackData)
    }

    @Test
    fun progressButtonsCountSpeechUpToTwoMinutes() {
        assertEquals("0:00 / 2:00", progressLabels(0.0).single())
        assertEquals("0:38 / 2:00", progressLabels(38.0).single())
        assertEquals("1:17 / 2:00", progressLabels(77.0).single())
        assertEquals("2:00+", progressLabels(120.0).single())
        assertEquals("2:00+", progressLabels(150.0).single())
        assertEquals(
            "🎙 This tracks how much English you've spoken. Around 2 minutes is usually enough for me to get to know you a little.",
            ONBOARDING_PROGRESS_HINT,
        )
        val run = "a".repeat(32)
        val withRetry = onboardingProgressKeyboard(38.0) + onboardingKeyboard("retry", run)
        val labels = withRetry.keyboard.map { row ->
            (row.single() as CallbackDataInlineKeyboardButton).text
        }
        assertEquals("Повторить", labels.last())
        val sideBySide = withSpokenText(onboardingProgressKeyboard(0.0), spoken = true)!!.keyboard.single()
        val texts = sideBySide.map { (it as CallbackDataInlineKeyboardButton).text }
        assertEquals(listOf("0:00 / 2:00", SPOKEN_TEXT_BUTTON), texts)
    }

    @Test
    fun resultsSlidesEditForwardAndKeepTheCommitmentCopy() {
        val run = "b".repeat(32)
        val review = OnboardingReview(
            levelText = "You can keep a conversation going about your own work.",
            grammar = OnboardingSkill(
                score = 62,
                text = "You handle basic sentence structures well.",
                examples = listOf(OnboardingExample("I work in startup", "I work at a startup")),
            ),
            vocabulary = OnboardingSkill(score = 71, text = "You have enough vocabulary.", examples = emptyList()),
            fluency = OnboardingFluency(
                score = 68,
                text = "You can keep your thoughts moving.",
                paceWpm = 104,
                longPauses = 6,
                fillers = 9,
                longestStretchSec = 18,
            ),
        )
        assertEquals(
            "Your English level\nB1\nIntermediate\nYou can keep a conversation going about your own work.",
            levelSlide("B1", review.levelText).plain(),
        )
        assertEquals(
            "Your English level\nI don't have a clear level from this chat yet.\nYou can keep a conversation going about your own work.",
            levelSlide(null, review.levelText).plain(),
        )
        assertTrue(grammarSlide(review).plain().contains("62 / 100"))
        assertTrue(grammarSlide(review).plain().contains("I work in startup → I work at a startup"))
        assertTrue(vocabularySlide(review).plain().contains(NO_VOCABULARY_PATTERNS))
        val fluency = fluencySlide(review).plain()
        assertTrue(fluency.contains("Speaking pace · 104 words/min"))
        assertTrue(fluency.contains("Long pauses · 6"))
        assertTrue(fluency.contains("Filler words · 9"))
        assertTrue(fluency.contains("Longest stretch without a long pause · 18 sec"))
        assertEquals(
            listOf("Long pauses · 6"),
            fluencyLines(OnboardingFluency(longPauses = 6)),
        )
        val minutes = practiceMinutesKeyboard(run).keyboard.single().map { (it as CallbackDataInlineKeyboardButton).text }
        assertEquals(listOf("5 min", "10 min", "15 min"), minutes)
        assertTrue(practiceDeal(10).startsWith("10 minutes a day. Deal 🤝"))
        assertEquals(SEE_YOU_TOMORROW, "See you tomorrow. I'll be here when you're ready.")
        val deal = practiceDealKeyboard(run).keyboard.single().map { it as CallbackDataInlineKeyboardButton }
        assertEquals(listOf("Keep talking 🎙", "See you tomorrow"), deal.map { it.text })
        assertEquals("talk", parseOnboardingCallback(deal[0].callbackData)?.action)
    }

    private fun TextSourcesList.plain(): String = joinToString("") { it.source }

    private fun progressLabels(seconds: Double): List<String> =
        onboardingProgressKeyboard(seconds).keyboard.map { row ->
            val button = row.single() as CallbackDataInlineKeyboardButton
            assertEquals(ONBOARDING_PROGRESS_CALLBACK, button.callbackData)
            assertNull(parseOnboardingCallback(button.callbackData))
            assertTrue(button.text.length <= 64)
            button.text
        }

    @Test
    fun invitationSupportsMissingNameAndCommandMentions() {
        assertTrue(onboardingInvitation("Alex").startsWith("👋 Hey, Alex!"))
        assertTrue(onboardingInvitation(null).startsWith("👋 Hey!"))
        assertTrue(onboardingInvitation("Alex").contains("Let’s get to know each other a little."))
        assertTrue(onboardingInvitation("Alex").contains("a couple of minutes"))
        val begin = onboardingKeyboard("begin", "a".repeat(32)).keyboard.single().single() as CallbackDataInlineKeyboardButton
        assertEquals("Let’s chat 👋", begin.text)
        assertTrue(isOnboardingCommand("/onboarding@speaky"))
        assertTrue(!isOnboardingCommand("/onboarding_extra"))
    }
}
