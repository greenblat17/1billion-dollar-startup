package com.eliteteam.speakingcoach.foundation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LearningScoreModelTest {
    private val plan = planFixture()

    @Test
    fun coefficientsAndUnsupportedOutcomesKeepExpectedMeaning() {
        assertEquals(56.0, LearningScoreModel.next(50.0, "independent_correct"))
        assertEquals(45.0, LearningScoreModel.next(50.0, "uncorrected_error"))
        assertEquals(48.0, LearningScoreModel.next(50.0, "self_corrected_error"))
        for (outcome in listOf("hinted_correct", "unresolved", "invalid")) {
            assertEquals(50.0, LearningScoreModel.next(50.0, outcome))
        }
        assertEquals(100.0, LearningScoreModel.next(100.0, "independent_correct"))
        assertEquals(0.0, LearningScoreModel.next(0.0, "uncorrected_error"))
    }

    @Test
    fun normalizationAndProductExamplesMatchV55() {
        assertEquals(0.0, LearningScoreModel.normalized(15.0))
        assertEquals(0.0, LearningScoreModel.normalized(50.0))
        assertEquals(0.5, LearningScoreModel.normalized(67.5))
        assertEquals(1.0, LearningScoreModel.normalized(85.0))
        assertEquals(0.0, LearningScoreModel.progress(plan, emptyMap()))
        assertEquals(17.142857142857142, LearningScoreModel.progress(plan, mapOf("exercise" to 70.0)), 0.000001)
        assertEquals(30.0, LearningScoreModel.progress(plan, mapOf("exercise" to 85.0)))
        assertEquals(70.0, LearningScoreModel.progress(plan, mapOf("exercise" to 85.0, "converse" to 70.0)))
        assertEquals(30.0, LearningScoreModel.progress(plan, mapOf("exercise" to 85.0, "converse" to 50.0)))
        assertEquals(0.0, LearningScoreModel.progress(plan, mapOf("exercise" to 50.0, "converse" to 15.0)))
    }

    @Test
    fun binaryGateHasNoWeightButBlocksMastery() {
        val passed = mapOf("exercise" to 85.0, "converse" to 85.0)
        assertEquals(100.0, LearningScoreModel.progress(plan, passed))
        assertFalse(LearningScoreModel.masteryReady(plan, passed, emptySet()))
        assertTrue(LearningScoreModel.masteryReady(plan, passed, setOf("explain")))
        assertFalse(LearningScoreModel.masteryReady(plan, mapOf("exercise" to 85.0, "converse" to 70.0), setOf("explain")))
    }

    @Test
    fun thresholdsAreSignalsNotResets() {
        assertEquals("review_approach", LearningScoreModel.signal(15.0))
        assertEquals("continue", LearningScoreModel.signal(50.0))
        assertEquals("passed", LearningScoreModel.signal(85.0))
    }
}
