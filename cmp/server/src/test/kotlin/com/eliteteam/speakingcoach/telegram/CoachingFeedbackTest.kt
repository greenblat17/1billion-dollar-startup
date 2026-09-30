package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.speaking.Correction
import com.eliteteam.speakingcoach.speaking.CorrectionKind
import com.eliteteam.speakingcoach.speaking.parseCorrections
import dev.inmo.tgbotapi.types.message.textsources.BoldTextSource
import dev.inmo.tgbotapi.types.message.textsources.StrikethroughTextSource
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
    fun quotesWhatSpeakySaid() {
        val sources = spokenQuote("  Hey, I'm Speaky.  ")
        assertEquals("💬 Speaky said:\n\nHey, I'm Speaky.", sources.joinToString("") { it.source })
    }

    @Test
    fun quotesOneOnboardingMistakeWithoutTheRestOfTheSpeech() {
        val grammar = onboardingCorrection(Correction("You is", "You are", CorrectionKind.GRAMMAR))
        assertEquals(
            "Правильно:\nYou is are",
            grammar.joinToString("") { it.source },
        )
        val word = onboardingCorrection(Correction("made a photo", "took a photo", CorrectionKind.WORD))
        assertEquals(
            "Лучше здесь сказать:\nmade took a photo",
            word.joinToString("") { it.source },
        )
        val natural = onboardingCorrection(Correction("very interesting for me", "really fun", CorrectionKind.NATURAL))
        assertEquals(
            "Естественнее:\nvery interesting for me really fun",
            natural.joinToString("") { it.source },
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
            parseCorrections(listOf("I was in Turkey|||I went to Turkey")),
        )
        assertEquals(
            "🗣️ You said:\n\nI was in went to Turkey\n\nlast summer",
            sources.joinToString("") { it.source },
        )
    }

    @Test
    fun splicesTwoNonOverlappingCorrections() {
        val sources = coachingEntities(
            "I go to shop and I was tired",
            parseCorrections(listOf("I go|||I went", "I was|||I got")),
        )
        assertEquals(
            "🗣️ You said:\n\nI go went\n\nto shop and\n\nI was got\n\ntired",
            sources.joinToString("") { it.source },
        )
    }

    @Test
    fun ignoresWrongThatIsNotInTheTranscript() {
        val sources = coachingEntities(
            "Hello there",
            parseCorrections(listOf("xyz|||abc")),
        )
        assertEquals("🗣️ You said:\n\nHello there", sources.joinToString("") { it.source })
    }

    @Test
    fun doesNotLeaveOrphanPeriodOnTheNextLine() {
        val sources = coachingEntities(
            "I will think about it when I will have users. Right now my goal is an MVP",
            parseCorrections(
                listOf("I will think about it when I will have users|||I will think about it when I have users"),
            ),
        )
        assertEquals(
            "🗣️ You said:\n\nI will think about it when I will have have users\n\nRight now my goal is an MVP",
            sources.joinToString("") { it.source },
        )
    }

    @Test
    fun doesNotSpliceMeInsideRemember() {
        val sources = coachingEntities(
            "I just try to remember how I celebrated",
            parseCorrections(listOf("me|||me is")),
        )
        assertEquals(
            "🗣️ You said:\n\nI just try to remember how I celebrated",
            sources.joinToString("") { it.source },
        )
    }

    @Test
    fun labelsCorrectionWithItsKind() {
        val sources = coachingEntities(
            "I made a photo yesterday",
            listOf(Correction("made a photo", "took a photo", CorrectionKind.WORD)),
        )
        assertEquals(
            "🗣️ You said:\n\nI\n\n$WORD_LABEL\nmade took a photo\n\nyesterday",
            sources.joinToString("") { it.source },
        )
    }

    @Test
    fun grammarWinsOverOverlappingNatural() {
        val sources = coachingEntities(
            "It were very interesting for me",
            listOf(
                Correction("It were very interesting for me", "I really enjoyed it", CorrectionKind.NATURAL),
                Correction("It were", "It was", CorrectionKind.GRAMMAR),
            ),
        )
        assertEquals(
            "🗣️ You said:\n\n$GRAMMAR_LABEL\nIt were was\n\nvery interesting for me",
            sources.joinToString("") { it.source },
        )
    }

    @Test
    fun keepsThreeCorrectionsByPriority() {
        val sources = coachingEntities(
            "a b c d",
            listOf(
                Correction("a", "A", CorrectionKind.NATURAL),
                Correction("b", "B", CorrectionKind.NATURAL),
                Correction("c", "C", CorrectionKind.WORD),
                Correction("d", "D", CorrectionKind.GRAMMAR),
            ),
        )
        assertEquals(
            "🗣️ You said:\n\n$NATURAL_LABEL\na A\n\nb\n\n$WORD_LABEL\nc C\n\n$GRAMMAR_LABEL\nd D",
            sources.joinToString("") { it.source },
        )
    }

    @Test
    fun keepsContextWhileChangingOnlyTheMistake() {
        val sources = onboardingCorrection(Correction(
            "Yesterday I go to the office early.",
            "Yesterday I went to the office early.",
            CorrectionKind.GRAMMAR,
        ))
        assertEquals(
            "Правильно:\nYesterday I go went to the office early.",
            sources.joinToString("") { it.source },
        )
        assertEquals(listOf("go"), sources.filterIsInstance<StrikethroughTextSource>().map { it.source })
        assertEquals(listOf("went"), sources.filterIsInstance<BoldTextSource>().map { it.source })
    }

    @Test
    fun insertionsAndDeletionsKeepAVisibleReplacement() {
        val insertion = onboardingCorrection(Correction("I bought car", "I bought a car"))
        assertEquals("Правильно:\nI bought car a car", insertion.joinToString("") { it.source })
        val deletion = onboardingCorrection(Correction("I am agree", "I agree"))
        assertEquals("Правильно:\nI am agree agree", deletion.joinToString("") { it.source })
    }
}
