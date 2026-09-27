package com.eliteteam.speakingcoach.telegram

import dev.inmo.tgbotapi.types.buttons.SimpleKeyboardButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LessonKeyboardTest {
    @Test
    fun endConversationMatchesTheButtonLabelOnly() {
        assertTrue(isEndConversation("📞 End conversation"))
        assertTrue(isEndConversation("  📞 End conversation  "))
        assertFalse(isEndConversation("End conversation"))
        assertFalse(isEndConversation("end conversation"))
        assertFalse(isEndConversation("📞 End conversation."))
        assertFalse(isEndConversation(SEND_VOICE_HINT))
    }

    @Test
    fun readyReplyMarkupExistsOnlyWhileTheLessonIsOpen() {
        assertNull(readyReplyMarkup(lessonOpen = false))
        val markup = readyReplyMarkup(lessonOpen = true)
        assertEquals(true, markup?.resizeKeyboard)
        assertEquals(false, markup?.oneTimeKeyboard)
        val button = markup?.keyboard?.single()?.single()
        assertIs<SimpleKeyboardButton>(button)
        assertEquals(END_CONVERSATION_TEXT, button.text)
    }
}
