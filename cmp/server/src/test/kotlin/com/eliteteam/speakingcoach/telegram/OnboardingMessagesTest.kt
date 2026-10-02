package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.OnboardingExample
import com.eliteteam.speakingcoach.ai.OnboardingFluency
import com.eliteteam.speakingcoach.ai.OnboardingReview
import com.eliteteam.speakingcoach.ai.OnboardingSkill
import com.eliteteam.speakingcoach.ai.VocabularySuggestion
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.message.textsources.TextSourcesList
import dev.inmo.tgbotapi.types.message.textsources.BoldTextSource
import dev.inmo.tgbotapi.types.message.textsources.ItalicTextSource
import dev.inmo.tgbotapi.types.message.textsources.StrikethroughTextSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingMessagesTest {
    @Test
    fun vocabularyAlternativeIsClearlyOptionalAndNeverStruckThrough() {
        val suggestion = VocabularySuggestion(
            original = "I use the same words every single time",
            alternative = "I tend to fall back on the same words",
            explanation = "Fall back on describes relying on familiar words out of habit.",
        )
        val vocabulary = OnboardingSkill(score = 70, suggestions = listOf(suggestion))
        val slide = vocabularySlide(OnboardingReview(vocabulary = vocabulary))
        assertTrue(slide.plain().contains("Another way to say it\n\nI use the same words every single time\n→ I tend to fall back on the same words"))
        assertTrue(slide.plain().contains("💡 Fall back on describes relying on familiar words out of habit."))
        assertTrue(slide.none { it is StrikethroughTextSource })
        assertTrue(!slide.plain().contains("What I noticed"))

        val corrected = vocabularySlide(OnboardingReview(vocabulary = vocabulary.copy(
            examples = listOf(OnboardingExample("I did a decision", "I made a decision", "Use make a decision.")),
        )))
        assertTrue(corrected.plain().contains("What I noticed"))
        assertTrue(!corrected.plain().contains("Another way to say it"))
    }

    @Test
    fun buttonsCarryTheAttemptAndFitTelegramLimit() {
        val run = "a".repeat(32)
        for (action in listOf("begin", "retry", "continue", "level", "results", "vocab", "fluency", "finish", "talk", "profile", "bye", "m5", "m10", "m15", "skip")) {
            val button = onboardingKeyboard(action, run).keyboard.single().single() as CallbackDataInlineKeyboardButton
            assertEquals(OnboardingCallback(action, run), parseOnboardingCallback(button.callbackData))
            assertTrue(button.callbackData.encodeToByteArray().size <= 64)
        }
        assertNull(parseOnboardingCallback("ob:reset:$run"))
        assertNull(parseOnboardingCallback("ob:begin:another:chat"))
        assertNull(parseOnboardingCallback("ob:begin:"))
        assertNull(parseOnboardingCallback(SPOKEN_TEXT_CALLBACK))
        for (action in listOf("level", "results", "vocab", "fluency", "finish", "profile")) {
            assertEquals("callback:q1", onboardingCallbackRequestId(action, run, "q1"))
            assertTrue(onboardingCallbackRequestId(action, run, "q1") != onboardingCallbackRequestId(action, run, "q2"))
        }
        assertEquals("callback:begin:$run", onboardingCallbackRequestId("begin", run, "q1"))
        assertEquals("callback:begin:$run", onboardingCallbackRequestId("begin", run, "q2"))
        assertEquals(onboardingCallbackRequestId("m5", run, "q1"), onboardingCallbackRequestId("skip", run, "q2"))
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
            "🎙 This tracks how much English you've spoken. Around 2 minutes gives me enough to get to know you and estimate your English level.",
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
                examples = listOf(OnboardingExample("I work in startup", "I work at a startup", "Use at a with startup here.")),
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
            "🎯 Your English level\n\nB1\nIntermediate\n\n57 / 100\n${scoreBar(57, 63)}\n\n✨ 6 points to B2\n\n" +
                "You can keep a conversation going about your own work.\n\n" +
                LEVEL_ESTIMATE,
            level.plain(),
        )
        assertTrue(level.any { it is BoldTextSource && it.source == "57 / 100" })
        assertEquals(20, scoreBar(57).length)
        assertEquals("█".repeat(10) + "░".repeat(2) + "┃" + "░".repeat(8), scoreBar(52, 61))
        assertEquals(21, scoreBar(57, 63).length)
        assertEquals(
            "🎯 Your English level\n\nI don't have a clear level from this chat yet.\n\n" +
                "You can keep a conversation going about your own work.\n\n" +
                LEVEL_ESTIMATE,
            levelSlide(null, review.levelText).plain(),
        )
        assertTrue(!levelSlide("C2", review.levelText).plain().contains("/ 100"))
        val grammar = grammarSlide(review)
        assertTrue(grammar.plain().contains("✍️ Grammar"))
        assertTrue(grammar.plain().contains("62 / 100"))
        assertTrue(grammar.plain().contains("I work in at a startup\n\n💡 Use at a with startup here."))
        assertTrue(grammar.any { it is StrikethroughTextSource && it.source == "in" })
        assertTrue(grammar.any { it is BoldTextSource && it.source == "at a" })
        assertTrue(grammar.any { it is ItalicTextSource && it.source == "Use at a with startup here." })
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
        assertEquals(listOf(PRACTICE_5_LABEL, PRACTICE_10_LABEL, PRACTICE_15_LABEL, PRACTICE_SKIP_LABEL), minutes)
        assertEquals(
            "No daily goal for now. You can still practice whenever you like.\n\n$NEXT_CHAT_HINT\n\n$REMINDER_QUESTION",
            PRACTICE_SKIPPED,
        )
        val quietDeal = practiceDeal(10)
        assertTrue(quietDeal.plain().startsWith("10 minutes a day. Deal 🤝"))
        assertTrue(quietDeal.plain().endsWith("$NEXT_CHAT_HINT\n\n$REMINDER_QUESTION"))
        assertTrue(!quietDeal.plain().contains("/profile"))
        assertTrue(quietDeal.any { it is BoldTextSource && it.source == "10 minutes a day. Deal 🤝" })
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
        assertEquals("Perfect. I'll remind you every day at 13:00 🔔\n\n$NEXT_CHAT_HINT", onboardingReminderSaved("13:00"))
        assertEquals(REMINDER_TIME_PROMPT, "When should I remind you?\n\nSend a time like 13:00.")
        assertEquals("That time doesn't look right. Send a time like 13:00.", REMINDER_INVALID_TIME_PROMPT)
        assertEquals(SEE_YOU_TOMORROW, "See you tomorrow. I'll be here when you're ready.")
        assertTrue(FOUNDER_NOTE.contains("@alexgusev93"))
        assertTrue(FOUNDER_NOTE.startsWith("Кстати, я Саша, один из создателей Speaky 👋"))
        assertEquals(3, FOUNDER_NOTE.windowed(2).count { it == "\n\n" })
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
    fun examplesAreSeparatedAndCappedAtFiveAndZeroFillersAreHidden() {
        val review = OnboardingReview(grammar = OnboardingSkill(
            score = 52,
            text = "You connect ideas, with some agreement errors.",
            examples = listOf(
                OnboardingExample("I builds", "I build", "Use the base verb with I."),
                OnboardingExample("he work", "he works", "Use works with he."),
                OnboardingExample("she go", "she goes", "Use goes with she."),
                OnboardingExample("they goes", "they go", "Use go with they."),
                OnboardingExample("we is", "we are", "Use are with we."),
                OnboardingExample("you was", "you were", "Use were with you."),
            ),
        ))
        assertEquals(
            "✍️ Grammar\n\n52 / 100\n\nYou connect ideas, with some agreement errors.\n\n" +
                "What I noticed\n\nI builds build\n\n💡 Use the base verb with I.\n\n" +
                "he work works\n\n💡 Use works with he.\n\n" +
                "she go goes\n\n💡 Use goes with she.\n\n" +
                "they goes go\n\n💡 Use go with they.\n\n" +
                "we is are\n\n💡 Use are with we.",
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
        assertTrue(onboardingInvitation("Alex").contains("Let’s talk in English for about 2 minutes."))
        assertTrue(onboardingInvitation("Alex").contains("see what your English level is."))
        assertEquals(
            "🎙 Reply with a voice message in English\n\n" +
                "No need to talk for 2 minutes at once. Just answer naturally — I’ll keep the conversation going.",
            ONBOARDING_VOICE_HINT,
        )
        val begin = onboardingKeyboard("begin", "a".repeat(32)).keyboard.single().single() as CallbackDataInlineKeyboardButton
        assertEquals("Let’s chat 👋", begin.text)
        assertTrue(isOnboardingCommand("/onboarding@speaky"))
        assertTrue(!isOnboardingCommand("/onboarding_extra"))
    }
}
