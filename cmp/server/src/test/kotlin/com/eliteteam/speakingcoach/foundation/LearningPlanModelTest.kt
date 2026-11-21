package com.eliteteam.speakingcoach.foundation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal fun planFixture(): JsonObject = json("schemaVersion" to 1,
    "stages" to JsonArray(listOf(
        json("id" to "explain", "method" to "Explanation", "kind" to "binary", "required" to true, "condition" to "understanding_demonstrated"),
        json("id" to "exercise", "method" to "Exercises", "kind" to "numeric", "required" to true, "weight" to 0.3),
        json("id" to "converse", "method" to "Conversation", "kind" to "numeric", "required" to true, "weight" to 0.7))),
    "order" to JsonArray(listOf("explain", "exercise", "converse").map(::JsonPrimitive)),
    "transitions" to JsonArray(listOf(json("from" to "explain", "to" to "exercise"),
        json("from" to "exercise", "to" to "converse"), json("from" to "converse", "to" to "exercise"),
        json("from" to "exercise", "to" to "explain"))))

class LearningPlanModelTest {
    private fun patchStage(index: Int, patch: JsonObject): JsonObject = JsonObject(planFixture() + json(
        "stages" to JsonArray(LearningPlanModel.stages(planFixture()).mapIndexed { i, s -> if (i == index) JsonObject(s + patch) else s })))

    @Test
    fun fourMethodsAndBinaryOnlyShapeDoNotInventScores() {
        assertEquals(planFixture(), LearningPlanModel.definition(planFixture()))
        val exposure = json("id" to "listen", "method" to "Exposure", "kind" to "numeric", "required" to true, "weight" to 1)
        val d = json("schemaVersion" to 1, "stages" to JsonArray(listOf(exposure)),
            "order" to JsonArray(listOf(JsonPrimitive("listen"))), "transitions" to JsonArray(emptyList()))
        assertEquals(d, LearningPlanModel.definition(d))
        val binary = json("schemaVersion" to 1, "stages" to JsonArray(listOf(LearningPlanModel.stages(planFixture()).first())),
            "order" to JsonArray(listOf(JsonPrimitive("explain"))), "transitions" to JsonArray(emptyList()))
        // Shape validity is not a pedagogical recommendation or an inferred completion result.
        assertEquals(binary, LearningPlanModel.definition(binary))
    }

    @Test
    fun weightsAreExactAndNeverSilentlyNormalized() {
        for (value in listOf(JsonPrimitive(-0.1), JsonPrimitive(1.01), JsonPrimitive("0.3"), JsonPrimitive("NaN"), JsonNull)) {
            assertFailsWith<FoundationFailure> { LearningPlanModel.definition(patchStage(1, json("weight" to value))) }
        }
        assertEquals("invalid_plan_weight_sum", assertFailsWith<FoundationFailure> {
            LearningPlanModel.definition(patchStage(1, json("weight" to 0.30000000000001)))
        }.code)
        assertEquals("binary_stage_weight", assertFailsWith<FoundationFailure> {
            LearningPlanModel.definition(patchStage(0, json("weight" to 0)))
        }.code)
    }

    @Test
    fun malformedStagesOrderAndTransitionsAreRejected() {
        val invalid = listOf(patchStage(1, json("method" to "Explanation")), patchStage(1, json("id" to "explain")),
            patchStage(1, json("required" to false)), patchStage(1, json("score" to 50)),
            patchStage(1, json("condition" to "understanding_demonstrated")), patchStage(0, json("condition" to "homework_done")),
            JsonObject(planFixture() + json("order" to JsonArray(listOf(JsonPrimitive("exercise"))))),
            JsonObject(planFixture() + json("transitions" to JsonArray(listOf(json("from" to "exercise", "to" to "missing"))))))
        invalid.forEach { assertFailsWith<FoundationFailure> { LearningPlanModel.definition(it) } }
    }

    @Test
    fun identitySurvivesReweightButNotChangingMeaning() {
        LearningPlanModel.preserveIdentity(planFixture(), patchStage(1, json("weight" to 0.5)))
        for (patch in listOf(json("method" to "Exposure"), json("kind" to "binary"))) {
            assertEquals("stage_identity_changed", assertFailsWith<FoundationFailure> {
                LearningPlanModel.preserveIdentity(planFixture(), patchStage(1, patch))
            }.code)
        }
    }

    @Test
    fun repeatReturnAndStudentChoiceDoNotRequireCompletion() {
        val d = planFixture()
        LearningPlanModel.transition(d, "converse", "exercise", "plan_transition")
        LearningPlanModel.transition(d, "exercise", "exercise", "plan_transition")
        LearningPlanModel.transition(d, "explain", "converse", "student_request")
        LearningPlanModel.transition(d, "converse", null, "student_request")
        assertEquals("stage_transition_not_allowed", assertFailsWith<FoundationFailure> {
            LearningPlanModel.transition(d, "explain", "converse", "plan_transition")
        }.code)
        assertFailsWith<FoundationFailure> { LearningPlanModel.cursor(JsonPrimitive("missing"), d) }
    }
}
