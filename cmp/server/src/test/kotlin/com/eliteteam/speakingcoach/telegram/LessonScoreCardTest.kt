package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.StreakProfileResponse
import com.eliteteam.speakingcoach.speaking.Correction
import com.eliteteam.speakingcoach.speaking.CorrectionKind
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LessonScoreCardTest {
    @Test
    fun cardListsEveryStoredCorrectionOfThatKind() {
        val corrections = listOf(
            Correction("I goes", "I go", CorrectionKind.GRAMMAR),
            Correction("he don't", "he doesn't", CorrectionKind.GRAMMAR),
            Correction("big", "large", CorrectionKind.WORD),
        )

        assertEquals(
            """
            Grammar
            Score: 34

            I goes → I go
            he don't → he doesn't
            """.trimIndent(),
            lessonScoreCard(LessonScoreMetric.GRAMMAR, 34, corrections),
        )
    }

    @Test
    fun emptyKindKeepsTheScoreAndSaysThereWereNoCorrections() {
        assertEquals(
            """
            Vocabulary
            Score: 34

            No vocabulary corrections in this lesson.
            """.trimIndent(),
            lessonScoreCard(LessonScoreMetric.VOCABULARY, 34, emptyList()),
        )
        assertEquals(
            """
            Fluency
            Score: 12

            No fluency corrections in this lesson.
            """.trimIndent(),
            lessonScoreCard(LessonScoreMetric.FLUENCY, 12, listOf(Correction("goes", "go", CorrectionKind.GRAMMAR))),
        )
    }

    @Test
    fun longCardKeepsTheLastExamplesThatFit() {
        val corrections = (1..200).map { index ->
            Correction("wrong $index ${"x".repeat(40)}", "better $index ${"y".repeat(40)}", CorrectionKind.GRAMMAR)
        }
        val card = lessonScoreCard(LessonScoreMetric.GRAMMAR, 34, corrections)
        assertTrue(card.length <= 4096)
        assertTrue(card.contains("And "))
        assertTrue(card.contains("wrong 200"))
        assertTrue(!card.contains("wrong 1 "))
    }

    @Test
    fun callbackButtonsNameTheNextMetric() {
        val lessonId = "6f0c2a1e-1b2c-4d5e-8f90-aabbccddeeff"
        val grammar = grammarScoreKeyboard(lessonId).keyboard.single().single()
        val vocabulary = nextScoreKeyboard(LessonScoreMetric.GRAMMAR, lessonId)!!.keyboard.single().single()
        val fluency = nextScoreKeyboard(LessonScoreMetric.VOCABULARY, lessonId)!!.keyboard.single().single()

        assertEquals("Grammar", (grammar as CallbackDataInlineKeyboardButton).text)
        assertEquals("g:$lessonId", grammar.callbackData)
        assertEquals("Vocabulary", (vocabulary as CallbackDataInlineKeyboardButton).text)
        assertEquals("v:$lessonId", vocabulary.callbackData)
        assertEquals("Fluency", (fluency as CallbackDataInlineKeyboardButton).text)
        assertEquals("f:$lessonId", fluency.callbackData)
        assertNull(nextScoreKeyboard(LessonScoreMetric.FLUENCY, lessonId))
        assertEquals(LessonScoreMetric.FLUENCY to lessonId, parseLessonScoreCallback("f:$lessonId"))
        assertTrue("g:$lessonId".encodeToByteArray().size < 64)
    }

    @Test
    fun lessonEndCaptionShowsCurrentAndBestWithoutACheer() {
        assertEquals(
            "Current streak: 3 days. Best: 5.",
            lessonEndStreakCaption(StreakProfileResponse(current = 3, best = 5)),
        )
        assertEquals(
            "Current streak: 1 day. Best: 1.",
            lessonEndStreakCaption(StreakProfileResponse(current = 1, best = 1)),
        )
        assertEquals(
            "No streak yet.",
            lessonEndStreakCaption(StreakProfileResponse(current = 0, best = 4)),
        )
    }
}
