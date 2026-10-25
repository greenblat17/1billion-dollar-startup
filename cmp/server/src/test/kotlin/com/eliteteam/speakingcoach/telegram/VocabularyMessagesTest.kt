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
    fun recallExerciseButtonsNeverExposeTarget() {
        val lesson = VocabularyLesson(id = "lesson1", status = "active", round = "Recall",
            index = 3, total = 6, word = null, hints = 0)
        val buttons = vocabularyLessonKeyboard(lesson).keyboard.flatten()
            .filterIsInstance<CallbackDataInlineKeyboardButton>()
        assertEquals(listOf("vocab:progress", "vocab:subtitle", "vocab:hint:lesson1:3", "vocab:end:lesson1"),
            buttons.map { it.callbackData })
        assertEquals("Hint", buttons[2].text)
        assertFalse(vocabularyLessonKeyboard(lesson, spoken = false).keyboard.flatten()
            .filterIsInstance<CallbackDataInlineKeyboardButton>().any { it.callbackData == "vocab:subtitle" })
    }

    @Test
    fun wordProgressResetsAtEachRound() {
        fun label(index: Int, round: String, status: String = "active") =
            vocabularyRoundProgress(VocabularyLesson(index = index, total = 6, round = round, status = status))

        assertEquals("Guided · ⚪️⚪️⚪️ 0/3", label(0, "Guided"))
        assertEquals("Guided · 🟢⚪️⚪️ 1/3", label(1, "Guided"))
        assertEquals("3/3 → Recall · ⚪️⚪️⚪️ 0/3", label(3, "Recall"))
        assertEquals("Recall · 🟢🟢⚪️ 2/3", label(5, "Recall"))
        assertEquals("Recall · 🟢🟢🟢 3/3", label(6, "Recall", "offer"))
        assertEquals("Guided · ⚪️ 0/1", vocabularyRoundProgress(
            VocabularyLesson(index = 0, total = 2, round = "Guided")))
        assertEquals("1/1 → Recall · ⚪️ 0/1", vocabularyRoundProgress(
            VocabularyLesson(index = 1, total = 2, round = "Recall")))
    }

    @Test
    fun optionalWordGameOffersPlayAndSkip() {
        val lesson = VocabularyLesson(id = "lesson1", status = "offer", round = "Recall", index = 2, total = 2)
        val buttons = vocabularyLessonKeyboard(lesson).keyboard.flatten()
            .filterIsInstance<CallbackDataInlineKeyboardButton>()
        assertTrue(buttons.any { it.callbackData == "vocab:play:lesson1" })
        assertTrue(buttons.any { it.callbackData == "vocab:skip:lesson1" })
    }

}
