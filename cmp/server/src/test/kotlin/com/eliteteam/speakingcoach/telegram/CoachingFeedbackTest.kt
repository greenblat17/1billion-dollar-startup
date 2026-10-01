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
            "Ранее ты сказал:\nYou is are",
            grammar.joinToString("") { it.source },
        )
        val word = onboardingCorrection(Correction("made a photo", "took a photo", CorrectionKind.WORD))
        assertEquals(
            "Ранее ты сказал:\nmade took a photo",
            word.joinToString("") { it.source },
        )
        val natural = onboardingCorrection(Correction("very interesting for me", "really fun", CorrectionKind.NATURAL))
        assertEquals(
            "Ранее ты сказал:\nvery interesting for me really fun",
            natural.joinToString("") { it.source },
        )
    }

    @Test
    fun explanationFollowsOnlyItsOwnCorrection() {
        val explanation = "Agree — глагол, поэтому am здесь не нужен."
        val onboarding = onboardingCorrection(Correction(
            "I am agree with you.", "I agree with you.", CorrectionKind.GRAMMAR, explanation,
        ))
        assertEquals("Ранее ты сказал:\nI am agree agree with you.\n\n💡 $explanation", onboarding.joinToString("") { it.source })
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
        assertEquals(true, dialogue.joinToString("") { it.source }.contains("$explanation\n\nРанее ты сказал:"))
    }

    @Test
    fun matchesTheRequestedCorrectionCard() {
        val correction = Correction(
            "that's important we is in a very competitive market",
            "that's important we are in a very competitive market",
            CorrectionKind.GRAMMAR,
            "С подлежащим we нужен глагол are, а не is.",
        )
        val sources = onboardingCorrection(correction)
        assertEquals(
            "Ранее ты сказал:\n" +
                "that's important we is are in a very competitive market\n\n" +
                "💡 С подлежащим we нужен глагол are, а не is.",
            sources.joinToString("") { it.source },
        )
        assertEquals(listOf("is"), sources.filterIsInstance<StrikethroughTextSource>().map { it.source })
        assertEquals(listOf("are"), sources.filterIsInstance<BoldTextSource>().map { it.source })
        assertEquals(sources, coachingEntities(correction.wrong, listOf(correction)))
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
            "Ранее ты сказал:\nI was in went to Turkey",
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
            "Ранее ты сказал:\nI go went\n\nРанее ты сказал:\nI was got",
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
            "Ранее ты сказал:\nI will think about it when I will have have users",
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
            "Ранее ты сказал:\nmade took a photo",
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
            "Ранее ты сказал:\nIt were was",
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
            "Ранее ты сказал:\na A\n\nРанее ты сказал:\nc C\n\nРанее ты сказал:\nd D",
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
            "Ранее ты сказал:\nYesterday I go went to the office early.",
            sources.joinToString("") { it.source },
        )
        assertEquals(listOf("go"), sources.filterIsInstance<StrikethroughTextSource>().map { it.source })
        assertEquals(listOf("went"), sources.filterIsInstance<BoldTextSource>().map { it.source })
    }

    @Test
    fun insertionsAndDeletionsKeepAVisibleReplacement() {
        val insertion = onboardingCorrection(Correction("I bought car", "I bought a car"))
        assertEquals("Ранее ты сказал:\nI bought car a car", insertion.joinToString("") { it.source })
        val deletion = onboardingCorrection(Correction("I am agree", "I agree"))
        assertEquals("Ранее ты сказал:\nI am agree agree", deletion.joinToString("") { it.source })
    }
}
