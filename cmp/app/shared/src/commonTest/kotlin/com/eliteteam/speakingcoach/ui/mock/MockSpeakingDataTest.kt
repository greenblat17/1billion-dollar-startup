package com.eliteteam.speakingcoach.ui.mock

import com.eliteteam.speakingcoach.ui.review.ReviewMetric
import kotlin.test.Test
import kotlin.test.assertEquals

class MockSpeakingDataTest {

    @Test
    fun reviewCarouselHasGrammarAndVocabularyOnly() {
        val metrics = MockSpeakingData.review.steps.map { it.metric }
        assertEquals(listOf(ReviewMetric.Grammar, ReviewMetric.Vocabulary), metrics)
    }
}
