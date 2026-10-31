package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.VocabularyCard
import com.eliteteam.speakingcoach.ai.VocabularyLesson
import com.eliteteam.speakingcoach.ai.VocabularyResponse
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VocabularyMessagesTest {
    @Test
    fun selectionSurvivesPagesAndCallbacksAreIdempotentActions() {
        val cards = (1..8).map { VocabularyCard(id = "id$it", word = "word$it") }
        val state = VocabularyResponse(items = cards, selected = listOf("id2", "id7"))
        assertTrue(vocabularyListText(state, 0).contains("Selected 2/5"))
        assertFalse(vocabularyListText(state, 0).contains("word7"))
        assertTrue(vocabularyListText(state, 1).contains("✅ word7"))
        val firstPage = vocabularyListKeyboard(state, 0).keyboard.flatten()
            .filterIsInstance<CallbackDataInlineKeyboardButton>()
        assertTrue(firstPage.any { it.callbackData == "vocab:unchoose:id2:0" })
        assertTrue(firstPage.any { it.callbackData == "vocab:choose:id1:0" })
        assertTrue(firstPage.any { it.callbackData == "vocab:list:1" })
        assertTrue(firstPage.any { it.callbackData == "vocab:start" })
    }

    @Test
    fun recallExerciseButtonsDoNotOfferHint() {
        val lesson = VocabularyLesson(id = "lesson1", status = "active", round = "Recall",
            phase = "Recall", index = 6, total = 9, word = null, hints = 0)
        val buttons = vocabularyLessonKeyboard(lesson).keyboard.flatten()
            .filterIsInstance<CallbackDataInlineKeyboardButton>()
        assertEquals(listOf("vocab:progress", "vocab:subtitle", "vocab:end:lesson1"),
            buttons.map { it.callbackData })
        assertFalse(vocabularyLessonKeyboard(lesson, spoken = false).keyboard.flatten()
            .filterIsInstance<CallbackDataInlineKeyboardButton>().any { it.callbackData == "vocab:subtitle" })
    }

    @Test
    fun wordProgressCountsCompletedWordsAndResetsForRecall() {
        fun label(index: Int, round: String, phase: String = "Understand", status: String = "active") =
            vocabularyRoundProgress(VocabularyLesson(index = index, total = 9, round = round,
                phase = phase, status = status))

        assertEquals("Learn · ⚪️⚪️⚪️ 0/3", label(0, "Learn"))
        assertEquals("Make a phrase · ⚪️⚪️⚪️ 0/3", label(1, "Learn", "Use"))
        assertEquals("Learn · 🟢⚪️⚪️ 1/3", label(2, "Learn"))
        assertEquals("3/3 → Recall · ⚪️⚪️⚪️ 0/3", label(6, "Recall"))
        assertEquals("Recall · 🟢🟢⚪️ 2/3", label(8, "Recall"))
        assertEquals("Recall · 🟢🟢🟢 3/3", label(9, "Recall", status = "offer"))
    }

    @Test
    fun optionalWordGameOffersPlayAndSkip() {
        val lesson = VocabularyLesson(id = "lesson1", status = "offer", round = "Recall", index = 3, total = 3)
        val buttons = vocabularyLessonKeyboard(lesson).keyboard.flatten()
            .filterIsInstance<CallbackDataInlineKeyboardButton>()
        assertTrue(buttons.any { it.callbackData == "vocab:play:lesson1" })
        assertTrue(buttons.any { it.callbackData == "vocab:skip:lesson1" })
    }

    @Test
    fun newLessonTracksEachWordThroughMeaningAndPhrase() {
        fun progress(index: Int, words: Int = 2) = vocabularyRoundProgress(
            VocabularyLesson(format = 5, status = "active", index = index, total = 3 * words))

        assertEquals(listOf("Progress: ⚪️⚪️", "Progress: 🟡⚪️", "Progress: 🟢⚪️",
            "Progress: 🟢🟡", "Progress: 🟢🟢", "Progress: 🟢🟢", "Progress: 🟢🟢"),
            (0..6).map { progress(it) })
        assertEquals(listOf("Progress: ⚪️", "Progress: 🟡", "Progress: 🟢", "Progress: 🟢"),
            (0..3).map { progress(it, words = 1) })
        assertEquals("Progress: 🟢🟢🟢🟢🟢", progress(10, words = 5))
        assertEquals("Progress: 🟢🟢🟢🟢🟢", progress(15, words = 5))
    }

    @Test
    fun existingGroupedLessonKeepsItsOrderAndHidesCardControls() {
        val card = VocabularyLesson(id = "new", format = 4, status = "cards",
            round = "Word game", phase = "Word game", index = 4, total = 6, hints = 3)
        assertEquals(listOf("Progress: ⚪️⚪️", "Progress: 🟡⚪️", "Progress: 🟡🟡",
            "Progress: 🟢🟡", "Progress: 🟢🟢", "Progress: 🟢🟢", "Progress: 🟢🟢"),
            (0..6).map { vocabularyRoundProgress(card.copy(index = it)) })
        val buttons = vocabularyLessonKeyboard(card).keyboard.flatten()
            .filterIsInstance<CallbackDataInlineKeyboardButton>()
        assertEquals(listOf("vocab:subtitle", "vocab:end:new"),
            buttons.map { it.callbackData })
        assertFalse(buttons.any { it.callbackData.startsWith("vocab:play") || it.callbackData.startsWith("vocab:skip") })
        assertEquals("Progress: 🟢🟢", vocabularyRoundProgress(card.copy(status = "finished")))
        assertEquals("Progress: 🟢🟢", vocabularyRoundProgress(card.copy(status = "finished", index = 6)))
    }

    @Test
    fun hintIsAvailableOnlyOnceDuringUnderstandAndUse() {
        val understand = VocabularyLesson(id = "lesson", format = 5, status = "active",
            phase = "Understand", index = 0, total = 3)
        val use = understand.copy(phase = "Use", index = 1)
        fun callbacks(lesson: VocabularyLesson) = vocabularyLessonKeyboard(lesson).keyboard.flatten()
            .filterIsInstance<CallbackDataInlineKeyboardButton>().map { it.callbackData }
        assertTrue("vocab:hint:lesson:0" in callbacks(understand))
        assertTrue("vocab:hint:lesson:1" in callbacks(use))
        assertFalse(callbacks(use.copy(hints = 1)).any { it.startsWith("vocab:hint:") })
        assertFalse(callbacks(use.copy(status = "cards", phase = "Word game")).any {
            it.startsWith("vocab:hint:") || it == "vocab:progress"
        })
    }

}
