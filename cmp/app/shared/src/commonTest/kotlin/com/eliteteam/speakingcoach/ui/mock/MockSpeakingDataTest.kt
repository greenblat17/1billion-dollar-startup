package com.eliteteam.speakingcoach.ui.mock

import kotlin.test.Test
import kotlin.test.assertEquals

class MockSpeakingDataTest {

    @Test
    fun reviewCarouselHasFiveSteps() {
        assertEquals(5, MockSpeakingData.review.steps.size)
    }
}
