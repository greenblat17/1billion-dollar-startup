package com.eliteteam.speakingcoach.telegram

import kotlin.test.Test
import kotlin.test.assertEquals

class CoachingFeedbackTest {

    @Test
    fun parsesWrongAndBetterPairs() {
        assertEquals(
            listOf(Correction("I was in Turkey", "I went to Turkey")),
            parseCorrections(listOf("I was in Turkey|||I went to Turkey", "skip me")),
        )
    }

    @Test
    fun quotesTranscriptWhenThereAreNoNotes() {
        val sources = coachingEntities("Hello there", emptyList())
        assertEquals("🗣️ You said:\n\nHello there", sources.joinToString("") { it.source })
    }

    @Test
    fun splicesOneCorrectionInsideTheQuote() {
        val sources = coachingEntities(
            "I was in Turkey last summer",
            listOf("I was in Turkey|||I went to Turkey"),
        )
        assertEquals(
            "🗣️ You said:\n\nI was in Turkey I went to Turkey last summer",
            sources.joinToString("") { it.source },
        )
    }

    @Test
    fun splicesTwoNonOverlappingCorrections() {
        val sources = coachingEntities(
            "I go to shop and I was tired",
            listOf("I go|||I went", "I was|||I got"),
        )
        assertEquals(
            "🗣️ You said:\n\nI go I went to shop and I was I got tired",
            sources.joinToString("") { it.source },
        )
    }

    @Test
    fun ignoresWrongThatIsNotInTheTranscript() {
        val sources = coachingEntities(
            "Hello there",
            listOf("xyz|||abc"),
        )
        assertEquals("🗣️ You said:\n\nHello there", sources.joinToString("") { it.source })
    }
}
