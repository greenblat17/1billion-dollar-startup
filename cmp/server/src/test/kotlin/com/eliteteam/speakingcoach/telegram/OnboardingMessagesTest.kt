package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.ReviewExample
import com.eliteteam.speakingcoach.ai.ReviewFluency
import com.eliteteam.speakingcoach.ai.ConversationReview
import com.eliteteam.speakingcoach.ai.ReviewSkill
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
    fun vocabularyUpgradeAppearsOnTheScoreSlideWithFullPhrase() {
        val suggestion = VocabularySuggestion(
            original = "big", alternative = "substantial", category = "word_upgrade",
            explanation = "Используй substantial для заметного роста затрат.",
            context = "a big increase in costs", editedContext = "a substantial increase in costs",
        )
        val vocabulary = ReviewSkill(score = 70, text = "You use familiar words accurately.", suggestions = listOf(suggestion))
        val review = ConversationReview(vocabulary = vocabulary)
        val slide = vocabularySlide(review)
        assertTrue(slide.plain().contains("70 / 100"))
        assertTrue(slide.plain().contains("Words and expressions to try\n\nYou said: a big increase in costs\nTry: a substantial increase in costs"))
        assertTrue(slide.plain().contains("💡 Используй substantial для заметного роста затрат."))
        assertTrue(slide.none { it is StrikethroughTextSource })

        val corrected = vocabularySlide(ConversationReview(vocabulary = vocabulary.copy(
            examples = listOf(ReviewExample("I did a decision", "I made a decision", "Use make a decision.")),
        )))
        assertTrue(!corrected.plain().contains("What I noticed"))
        assertTrue(corrected.plain().contains("Words and expressions to try"))
        assertEquals(0, corrected.count { it is StrikethroughTextSource })
    }

    @Test
    fun wordAndExpressionUpgradesShareOneBoundedVocabularySlide() {
        val words = (1..5).map { index -> VocabularySuggestion(
            original = "big$index", alternative = "substantial$index", category = "word_upgrade",
        ) }
        val expressions = (1..5).map { index -> VocabularySuggestion(
            original = "said no $index", alternative = "turned down $index", category = "expression_upgrade",
        ) }
        val review = ConversationReview(vocabulary = ReviewSkill(suggestions = words + expressions))
        val slide = vocabularySlide(review).plain()
        assertTrue(slide.contains("substantial5"))
        assertTrue(slide.contains("turned down 5"))
        assertTrue(slide.length <= 3900)
    }

    @Test
    fun longVocabularySuggestionsKeepTheScoreAndFitOneTelegramMessage() {
        val suggestions = (1..10).map { index -> VocabularySuggestion(
            original = "big", alternative = "substantial",
            context = "This was a big increase in costs $index. " + "Context ".repeat(30),
            editedContext = "This was a substantial increase in costs $index. " + "Context ".repeat(30),
            explanation = "Используй substantial для заметного роста затрат.",
        ) }
        val review = ConversationReview(vocabulary = ReviewSkill(
            score = 70, text = "You use familiar words accurately.", suggestions = suggestions,
        ))
        val slide = vocabularySlide(review).plain()
        assertTrue(slide.contains("70 / 100"))
        assertTrue(slide.contains("You use familiar words accurately."))
        assertTrue(slide.contains("This was a substantial increase in costs 1."))
        assertTrue(!slide.contains("This was a substantial increase in costs 10."))
        assertTrue(slide.length <= 3900)
    }







    @Test
    fun examplesAreSeparatedAndCappedAtFiveAndZeroFillersAreHidden() {
        val review = ConversationReview(grammar = ReviewSkill(
            score = 52,
            text = "You connect ideas, with some agreement errors.",
            examples = listOf(
                ReviewExample("I builds", "I build", "Use the base verb with I."),
                ReviewExample("he work", "he works", "Use works with he."),
                ReviewExample("she go", "she goes", "Use goes with she."),
                ReviewExample("they goes", "they go", "Use go with they."),
                ReviewExample("we is", "we are", "Use are with we."),
                ReviewExample("you was", "you were", "Use were with you."),
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
        assertEquals(emptyList(), fluencyLines(ReviewFluency(fillers = 0)))
        assertEquals(emptyList(), fluencyLines(ReviewFluency(fillers = null)))
        assertEquals(listOf("Detected filler words · 2"), fluencyLines(ReviewFluency(fillers = 2)))
        assertTrue(practiceAsk("A2", 38, "B1", 9).plain().contains("A2 · 38/100 → B1 · 47/100"))
        assertTrue(!practiceAsk("C1", 90, null).plain().contains("→"))
    }

    private fun TextSourcesList.plain(): String = joinToString("") { it.source }




}
