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
        assertTrue(vocabularyListText(state, 0).contains("Selected 2/3"))
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
    fun independentExerciseButtonsNeverExposeTarget() {
        val lesson = VocabularyLesson(id = "lesson1", status = "active", round = "Independent",
            index = 6, total = 9, word = null, hints = 1)
        val buttons = vocabularyLessonKeyboard(lesson).keyboard.flatten()
            .filterIsInstance<CallbackDataInlineKeyboardButton>()
        assertEquals(listOf("vocab:progress", "vocab:subtitle", "vocab:hint:lesson1:6", "vocab:end:lesson1"),
            buttons.map { it.callbackData })
        assertEquals("More hint", buttons[2].text)
        assertFalse(vocabularyLessonKeyboard(lesson, spoken = false).keyboard.flatten()
            .filterIsInstance<CallbackDataInlineKeyboardButton>().any { it.callbackData == "vocab:subtitle" })
    }

    @Test
    fun wordProgressResetsAtEachRound() {
        fun label(index: Int, round: String, status: String = "active") =
            vocabularyRoundProgress(VocabularyLesson(index = index, total = 9, round = round, status = status))

        assertEquals("Guided · ⚪️⚪️⚪️ 0/3", label(0, "Guided"))
        assertEquals("Guided · 🟢⚪️⚪️ 1/3", label(1, "Guided"))
        assertEquals("3/3 → Contextual · ⚪️⚪️⚪️ 0/3", label(3, "Contextual"))
        assertEquals("Contextual · 🟢🟢⚪️ 2/3", label(5, "Contextual"))
        assertEquals("3/3 → Independent · ⚪️⚪️⚪️ 0/3", label(6, "Independent"))
        assertEquals("Independent · 🟢🟢🟢 3/3", label(9, "Independent", "finished"))
        assertEquals("Guided · ⚪️ 0/1", vocabularyRoundProgress(
            VocabularyLesson(index = 0, total = 3, round = "Guided")))
        assertEquals("1/1 → Contextual · ⚪️ 0/1", vocabularyRoundProgress(
            VocabularyLesson(index = 1, total = 3, round = "Contextual")))
    }
}
