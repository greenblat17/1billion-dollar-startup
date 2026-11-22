package com.eliteteam.speakingcoach.telegram

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressNavigationTest {
    @Test fun progressNavigationDoesNotRequireABookedLesson() {
        val markup = tutorProgressMarkup("https://example.test/miniapp?theme=warm")
        val button = markup["keyboard"]!!.jsonArray[0].jsonArray[0].jsonObject
        assertEquals("My Progress", button["text"]!!.jsonPrimitive.content)
        assertEquals("https://example.test/miniapp?theme=warm&view=progress", button["web_app"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("true", markup["is_persistent"]!!.jsonPrimitive.content)
        assertEquals("true", tutorProgressMarkup("")["remove_keyboard"]!!.jsonPrimitive.content)
    }
}
