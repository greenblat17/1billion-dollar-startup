package com.eliteteam.speakingcoach.foundation

import kotlin.test.Test
import kotlin.test.assertEquals

class ProblemDetectionTest {
    @Test fun experimentalFormulaExamplesAndBounds() {
        assertEquals(15.0, ProblemDetection.next(0.0, "uncorrected_error"))
        assertEquals(27.75, ProblemDetection.next(15.0, "uncorrected_error"))
        assertEquals(45.0, ProblemDetection.next(50.0, "independent_correct"))
        assertEquals(53.5, ProblemDetection.next(50.0, "self_corrected_error"))
        assertEquals(0.0, ProblemDetection.next(0.0, "independent_correct"))
        assertEquals(100.0, ProblemDetection.next(100.0, "uncorrected_error"))
        assertEquals(50.0, ProblemDetection.next(50.0, "hinted_correct"))
        assertEquals(50.0, ProblemDetection.next(50.0, "invalid"))
    }
}
