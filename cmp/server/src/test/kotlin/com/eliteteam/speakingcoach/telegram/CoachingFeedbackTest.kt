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
            "You is are",
            grammar.joinToString("") { it.source },
        )
        val word = onboardingCorrection(Correction("made a photo", "took a photo", CorrectionKind.WORD))
        assertEquals(
            "made took a photo",
            word.joinToString("") { it.source },
        )
        val natural = onboardingCorrection(Correction("very interesting for me", "really fun", CorrectionKind.NATURAL))
        assertEquals(
            "very interesting for me really fun",
            natural.joinToString("") { it.source },
        )
    }

    @Test
    fun explanationFollowsOnlyItsOwnCorrection() {
        val explanation = "Agree — глагол, поэтому am здесь не нужен."
        val onboarding = onboardingCorrection(Correction(
            "I am agree with you.", "I agree with you.", CorrectionKind.GRAMMAR, explanation,
        ))
        assertEquals("I am agree agree with you.\n💡 $explanation", onboarding.joinToString("") { it.source })
        assertEquals(listOf("am agree"), onboarding.filterIsInstance<StrikethroughTextSource>().map { it.source })
        assertEquals(listOf("agree"), onboarding.filterIsInstance<BoldTextSource>().map { it.source })

        val dialogue = coachingEntities(
            "I am agree with you. Yesterday I go to work.",
            listOf(
                Correction("I am agree with you.", "I agree with you.", CorrectionKind.GRAMMAR, explanation),
                Correction("Yesterday I go to work.", "Yesterday I went to work.", CorrectionKind.GRAMMAR),
            ),
        )
        assertEquals(1, dialogue.joinToString("") { it.source }.split("💡").size - 1)
        assertEquals(true, dialogue.joinToString("") { it.source }.contains("$explanation\n\n"))
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
    fun showsCorrectionWithoutCategoryHeading() {
        val sources = coachingEntities(
            "I made a photo yesterday",
            listOf(Correction("made a photo", "took a photo", CorrectionKind.WORD)),
        )
        assertEquals(
            "🗣️ You said:\n\nI\n\nmade took a photo\n\nyesterday",
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
            "🗣️ You said:\n\nIt were was\n\nvery interesting for me",
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
            "🗣️ You said:\n\na A\n\nb\n\nc C\n\nd D",
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
            "Yesterday I go went to the office early.",
            sources.joinToString("") { it.source },
        )
        assertEquals(listOf("go"), sources.filterIsInstance<StrikethroughTextSource>().map { it.source })
        assertEquals(listOf("went"), sources.filterIsInstance<BoldTextSource>().map { it.source })
    }

    @Test
    fun insertionsAndDeletionsKeepAVisibleReplacement() {
        val insertion = onboardingCorrection(Correction("I bought car", "I bought a car"))
        assertEquals("I bought car a car", insertion.joinToString("") { it.source })
        val deletion = onboardingCorrection(Correction("I am agree", "I agree"))
        assertEquals("I am agree agree", deletion.joinToString("") { it.source })
    }
}
