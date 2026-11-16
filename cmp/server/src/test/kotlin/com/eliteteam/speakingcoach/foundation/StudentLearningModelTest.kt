package com.eliteteam.speakingcoach.foundation

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StudentLearningModelTest {
    @Test
    fun partialUpdatesKeepIndependentLevelsAndClearOnlyExplicitNulls() {
        val profile = StudentLearningModel.patch(StudentLearningModel.empty(), json(
            "currentLevels" to json("selfAssessment" to json("overall" to json("level" to "A2")),
                "speaky" to json("skills" to json("grammar" to json("level" to "B1", "explanation" to "Observed use")))),
            "targetLevel" to "B2", "intensity" to json("lessonsPerWeek" to 3)))
        val updated = StudentLearningModel.patch(profile, json("intensity" to json("lessonsPerWeek" to null)))
        assertEquals(JsonNull, updated.obj("intensity")["lessonsPerWeek"])
        assertEquals("A2", updated.obj("currentLevels").obj("selfAssessment").obj("overall").text("level"))
        assertEquals("Unknown", updated.obj("currentLevels").obj("speaky").obj("overall").text("level"))
        assertEquals("B1", updated.obj("currentLevels").obj("speaky").obj("skills").obj("grammar").text("level"))
        assertEquals("B2", updated.text("targetLevel"))
    }

    @Test
    fun rejectsRangesGuessedNumbersInvalidSkillsAndHomeworkWithoutPeriod() {
        val invalid = listOf(json("targetLevel" to "A2-B1"), json("targetLevel" to json("speaking" to "B2")),
            json("intensity" to json("lessonsPerWeek" to "3")), json("intensity" to json("lessonDurationMinutes" to 0)),
            json("intensity" to json("lessonsPerWeek" to -1)), json("homework" to json("minutes" to 10)),
            json("homework" to json("minutes" to -1)),
            json("homework" to json("consent" to true)), json("deadline" to "tomorrow"),
            json("currentLevels" to json("speaky" to json("skills" to json("invented" to json("level" to "A1"))))))
        invalid.forEach { patch -> assertFailsWith<FoundationFailure> { StudentLearningModel.patch(StudentLearningModel.empty(), patch) } }
        assertEquals(JsonPrimitive(10), StudentLearningModel.patch(StudentLearningModel.empty(),
            json("homework" to json("consent" to "yes", "minutes" to 10, "period" to "day"))).obj("homework")["minutes"])
    }

    @Test
    fun zeroHomeworkMinutesCanKeepPeriodUnknownWithoutChangingOtherFields() {
        val profile = StudentLearningModel.patch(StudentLearningModel.empty(), json(
            "intensity" to json("lessonsPerWeek" to 2),
            "homework" to json("consent" to "no", "minutes" to 0)))
        assertEquals(JsonPrimitive(0), profile.obj("homework")["minutes"])
        assertEquals(JsonNull, profile.obj("homework")["period"])
        assertEquals(JsonPrimitive(2), profile.obj("intensity")["lessonsPerWeek"])
        assertEquals(JsonPrimitive(0), StudentLearningModel.patch(profile,
            json("homework" to json("period" to "week"))).obj("homework")["minutes"])
    }
}
