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
            "🗣️ You said:\n\nI was in Turkey\nI went to Turkey\n\nlast summer",
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
            "🗣️ You said:\n\nI go\nI went\n\nto shop and\n\nI was\nI got\n\ntired",
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

    @Test
    fun doesNotLeaveOrphanPeriodOnTheNextLine() {
        val sources = coachingEntities(
            "I will think about it when I will have users. Right now my goal is an MVP",
            listOf("I will think about it when I will have users|||I will think about it when I have users"),
        )
        assertEquals(
            "🗣️ You said:\n\nI will think about it when I will have users\nI will think about it when I have users\n\nRight now my goal is an MVP",
            sources.joinToString("") { it.source },
        )
    }

    @Test
    fun doesNotSpliceMeInsideRemember() {
        val sources = coachingEntities(
            "I just try to remember how I celebrated",
            listOf("me|||me is"),
        )
        assertEquals(
            "🗣️ You said:\n\nI just try to remember how I celebrated",
            sources.joinToString("") { it.source },
        )
    }
}
