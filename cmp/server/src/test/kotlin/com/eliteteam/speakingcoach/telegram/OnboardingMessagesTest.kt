package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.OnboardingExample
import com.eliteteam.speakingcoach.ai.OnboardingFluency
import com.eliteteam.speakingcoach.ai.OnboardingReview
import com.eliteteam.speakingcoach.ai.OnboardingSkill
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.message.textsources.TextSourcesList
import dev.inmo.tgbotapi.types.message.textsources.BoldTextSource
import dev.inmo.tgbotapi.types.message.textsources.StrikethroughTextSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingMessagesTest {
    @Test
    fun buttonsCarryTheAttemptAndFitTelegramLimit() {
        val run = "a".repeat(32)
        for (action in listOf("begin", "retry", "continue", "level", "results", "vocab", "fluency", "finish", "talk", "bye", "m5", "m10", "m15")) {
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
        assertEquals("🎯 0:00 / 2:00", progressLabels(0.0).single())
        assertEquals("🎯 0:38 / 2:00", progressLabels(38.0).single())
        assertEquals("🎯 1:17 / 2:00", progressLabels(77.0).single())
        assertEquals("🎯 2:00+", progressLabels(120.0).single())
        assertEquals("🎯 2:00+", progressLabels(150.0).single())
        assertEquals(
            "🎙 This tracks how much English you've spoken. Around 2 minutes is usually enough for me to get to know you a little.",
            ONBOARDING_PROGRESS_HINT,
        )
        val run = "a".repeat(32)
        val withRetry = onboardingProgressKeyboard(38.0) + onboardingKeyboard("retry", run)
        val labels = withRetry.keyboard.map { row ->
            (row.single() as CallbackDataInlineKeyboardButton).text
        }
        assertEquals("Retry", labels.last())
        val sideBySide = withSpokenText(onboardingProgressKeyboard(0.0), spoken = true)!!.keyboard.single()
        val texts = sideBySide.map { (it as CallbackDataInlineKeyboardButton).text }
        assertEquals(listOf("🎯 0:00 / 2:00", SPOKEN_TEXT_BUTTON), texts)
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
        val level = levelSlide("B1", review.levelText, overallScore = 57, nextBand = "B2", pointsToNext = 6)
        assertEquals(
            "🎯 Your English level\n\nB1\nIntermediate\n\n57 / 100\n${scoreBar(57)}\n\n✨ 6 points to B2\n\n" +
                "You can keep a conversation going about your own work.",
            level.plain(),
        )
        assertTrue(level.any { it is BoldTextSource && it.source == "57 / 100" })
        assertEquals(20, scoreBar(57).length)
        assertEquals(
            "🎯 Your English level\n\nI don't have a clear level from this chat yet.\n\n" +
                "You can keep a conversation going about your own work.",
            levelSlide(null, review.levelText).plain(),
        )
        assertTrue(!levelSlide("C2", review.levelText).plain().contains("/ 100"))
        val grammar = grammarSlide(review)
        assertTrue(grammar.plain().contains("✍️ Grammar"))
        assertTrue(grammar.plain().contains("62 / 100"))
        assertTrue(grammar.plain().contains("I work in startup\n→ I work at a startup"))
        assertTrue(grammar.any { it is StrikethroughTextSource && it.source == "I work in startup" })
        assertTrue(grammar.any { it is BoldTextSource && it.source == "I work at a startup" })
        val unknown = grammarSlide(
            review.copy(grammar = OnboardingSkill(text = "Thin sample.")),
        ).plain()
        assertTrue(unknown.contains(SKILL_UNKNOWN))
        assertTrue(!unknown.contains("/ 100"))
        val quiet = fluencySlide(review.copy(fluency = review.fluency.copy(score = null))).plain()
        assertTrue(quiet.contains(SKILL_UNKNOWN))
        assertTrue(quiet.contains("Speaking pace · 104 words/min"))
        assertTrue(!quiet.contains("/ 100"))
        assertTrue(!vocabularySlide(review).plain().contains("What I noticed"))
        val fluency = fluencySlide(review).plain()
        assertTrue(fluency.contains("🎙 Fluency"))
        assertTrue(fluency.contains("Speaking pace · 104 words/min"))
        assertTrue(fluency.contains("Long pauses · 6"))
        assertTrue(fluency.contains("Detected filler words · 9"))
        assertTrue(fluency.contains("Longest stretch without a long pause · 18 sec"))
        assertEquals(
            listOf("Long pauses · 6"),
            fluencyLines(OnboardingFluency(longPauses = 6)),
        )
        val minutes = practiceMinutesKeyboard(run).keyboard.map { row ->
            (row.single() as CallbackDataInlineKeyboardButton).text
        }
        assertEquals(listOf(PRACTICE_5_LABEL, PRACTICE_10_LABEL, PRACTICE_15_LABEL), minutes)
        assertTrue(practiceDeal(10).startsWith("10 minutes a day. Deal 🤝"))
        assertTrue(practiceDeal(10).endsWith("Want me to remind you?"))
        val ask = reminderAskKeyboard(run).keyboard.single().map { it as CallbackDataInlineKeyboardButton }
        assertEquals(listOf("🔔 Set reminder", "Not now"), ask.map { it.text })
        assertEquals("remind", parseOnboardingCallback(ask[0].callbackData)?.action)
        assertEquals("later", parseOnboardingCallback(ask[1].callbackData)?.action)
        assertEquals("08:05", parseReminderClock("8:05"))
        assertEquals("13:00", parseReminderClock(" 13:00 "))
        assertEquals(null, parseReminderClock("evening"))
        assertEquals(null, parseReminderClock("13"))
        assertEquals(null, parseReminderClock("24:00"))
        assertEquals(
            "Perfect. I'll remind you every day at 13:00 🔔\n\nYou can check your progress anytime with /profile.",
            reminderSaved("13:00"),
        )
        assertEquals("You can check your progress anytime with /profile.", reminderSkipped())
        assertEquals(REMINDER_TIME_PROMPT, "When should I remind you?\nSend a time like 13:00")
        assertEquals(SEE_YOU_TOMORROW, "See you tomorrow. I'll be here when you're ready.")
        val deal = practiceDealKeyboard(run).keyboard.single().map { it as CallbackDataInlineKeyboardButton }
        assertEquals(listOf("Keep talking 🎙", "See you tomorrow"), deal.map { it.text })
        assertEquals("talk", parseOnboardingCallback(deal[0].callbackData)?.action)
        val closing = withSpokenText(onboardingKeyboard("level", run), spoken = true)!!.keyboard.single()
        val closingLabels = closing.map { (it as CallbackDataInlineKeyboardButton).text }
        assertEquals(listOf("🔥 See my results", SPOKEN_TEXT_BUTTON), closingLabels)
        val report = onboardingKeyboard("results", run).keyboard.single().single() as CallbackDataInlineKeyboardButton
        assertEquals("See what I noticed →", report.text)
        assertEquals(
            "🚀 This is your starting point.\n\nB1 · 57/100 → B2 · 63/100\n\n" +
                "Now let's make progress one conversation at a time.\n\n" +
                "How much time do you want to practice each day?",
            practiceAsk("B1", 57, "B2", 6).plain(),
        )
        assertTrue(practiceAsk(null, null, null).plain().contains(PRACTICE_ASK_BODY))
        assertTrue(!practiceAsk("C2", null, null).plain().contains("→"))
        val carryOn = onboardingKeyboard("finish", run).keyboard.single().single() as CallbackDataInlineKeyboardButton
        assertEquals("Continue →", carryOn.text)
    }

    @Test
    fun examplesAreSeparatedAndCappedAndZeroFillersAreHidden() {
        val review = OnboardingReview(grammar = OnboardingSkill(
            score = 52,
            text = "You connect ideas, with some agreement errors.",
            examples = listOf(
                OnboardingExample("I builds", "I build"),
                OnboardingExample("he work", "he works"),
                OnboardingExample("she go", "she goes"),
            ),
        ))
        assertEquals(
            "✍️ Grammar\n\n52 / 100\n\nYou connect ideas, with some agreement errors.\n\n" +
                "What I noticed\n\nI builds\n→ I build\n\nhe work\n→ he works",
            grammarSlide(review).plain(),
        )
        assertEquals(emptyList(), fluencyLines(OnboardingFluency(fillers = 0)))
        assertEquals(emptyList(), fluencyLines(OnboardingFluency(fillers = null)))
        assertEquals(listOf("Detected filler words · 2"), fluencyLines(OnboardingFluency(fillers = 2)))
        assertTrue(practiceAsk("A2", 38, "B1", 9).plain().contains("A2 · 38/100 → B1 · 47/100"))
        assertTrue(!practiceAsk("C1", 90, null).plain().contains("→"))
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
