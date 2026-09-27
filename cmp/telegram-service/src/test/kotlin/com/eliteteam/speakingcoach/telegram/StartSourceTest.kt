package com.eliteteam.speakingcoach.telegram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartSourceTest {

    @Test
    fun recognizesStartWithAPayloadOrABotMention() {
        assertTrue(isStartCommand("/start"))
        assertTrue(isStartCommand("  /start  "))
        assertTrue(isStartCommand("/start clubs"))
        assertTrue(isStartCommand("/start@speaky_english_buddy_bot"))
        assertTrue(isStartCommand("/start@speaky_english_buddy_bot product_radar"))
        assertFalse(isStartCommand("/starting"))
        assertFalse(isStartCommand("/help"))
        assertFalse(isStartCommand("hello"))
    }

    @Test
    fun readsADeepLinkPayload() {
        assertEquals("clubs", startSource("/start clubs"))
        assertEquals("product_radar", startSource("/start@speaky_english_buddy_bot product_radar"))
    }

    @Test
    fun emptyStartHasNoSource() {
        assertNull(startSource("/start"))
        assertNull(startSource("/start   "))
        assertNull(startSource("/start@speaky_english_buddy_bot"))
    }

    @Test
    fun rejectsAPayloadOutsideTheTelegramCharset() {
        assertNull(startSource("/start clubs friends"))
        assertNull(startSource("/start clubs!"))
        assertNull(startSource("/start " + "a".repeat(65)))
    }
}
