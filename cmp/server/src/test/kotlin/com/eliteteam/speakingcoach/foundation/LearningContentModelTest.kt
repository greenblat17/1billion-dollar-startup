package com.eliteteam.speakingcoach.foundation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal fun contentFixture(task: JsonObject, planId: String, method: String = "Exercises", stageId: String = "exercise"): JsonObject {
    val action = when (method) {
        "Explanation" -> "direct"
        "Exposure" -> "listening_reconstruction"
        "Conversation" -> "topic_driven"
        else -> "sentence_transformation"
    }
    val payload = when (method) {
        "Explanation" -> json("explanation" to "Use the past form for a finished action.",
            "examples" to JsonArray(listOf(JsonPrimitive("I went to work yesterday."))))
        "Exposure" -> json("segments" to JsonArray(listOf(JsonPrimitive("I went to work yesterday."))),
            "checks" to JsonArray(listOf(JsonPrimitive("reconstruction"), JsonPrimitive("meaning"))))
        "Conversation" -> json("openingPrompt" to "What did you do yesterday?",
            "targetOpportunities" to JsonArray(listOf(JsonPrimitive("Use a past-tense verb in a natural answer."))))
        else -> json("stimulus" to "I go to work yesterday.", "targetOperation" to "Say the sentence in the past tense.")
    }
    return json("schemaVersion" to 1, "taskId" to task.text("id"),
        "stageRef" to json("taskId" to task.text("id"), "planId" to planId, "planRevision" to 1, "stageId" to stageId),
        "catalogVersion" to task.text("catalog_version"), "skillId" to task.text("skill_id"),
        "topicId" to task.text("topic_id"), "problemId" to task.text("problem_id"),
        "method" to method, "actionKind" to action, "cefr" to "A2",
        "objective" to "Use a past form in speech.", "spokenInstruction" to "Say this in the past tense.",
        "payload" to payload, "answerGuide" to json("criteria" to JsonArray(listOf(JsonPrimitive("Past form preserves the intended meaning."))),
            "alternatives" to JsonArray(listOf(JsonPrimitive("Natural equivalent wording is allowed."))),
            "unacceptable" to JsonArray(listOf(JsonPrimitive("Present form for a completed past action.")))),
        "validation" to json("status" to "passed", "component" to "fixture", "reasons" to JsonArray(emptyList())))
}

class LearningContentModelTest {
    private val task = json("id" to "task-1", "catalog_version" to "v1", "skill_id" to "grammar",
        "topic_id" to "past", "problem_id" to "grammar_application")

    @Test
    fun fourMethodsHaveOneContractAndExposureContainsReconstructionWithoutExercisesStage() {
        for ((method, stage) in listOf("Explanation" to "explain", "Exercises" to "exercise",
            "Exposure" to "exposure", "Conversation" to "converse")) {
            val document = contentFixture(task, "plan-1", method, stage)
            assertEquals(document, LearningContentModel.validate(document))
        }
    }

    @Test
    fun malformedLinksActionsAndAnswerGuidesAreRejected() {
        val base = contentFixture(task, "plan-1")
        val bad = listOf(
            JsonObject(base + json("actionKind" to "dialogue")),
            JsonObject(base + json("method" to "Exposure")),
            JsonObject(base + json("stageRef" to json("taskId" to "other", "planId" to "plan-1", "planRevision" to 1, "stageId" to "exercise"))),
            JsonObject(base + json("answerGuide" to json("criteria" to JsonArray(emptyList()),
                "alternatives" to JsonArray(emptyList()), "unacceptable" to JsonArray(emptyList())))),
            JsonObject(base + json("spokenInstruction" to "x".repeat(401))),
            JsonObject(base + json("validation" to json("status" to "rejected", "component" to "fixture", "reasons" to JsonArray(emptyList())))),
            JsonObject(base + json("score" to 100)),
        )
        bad.forEach { assertFailsWith<FoundationFailure> { LearningContentModel.validate(it) } }
    }

    @Test
    fun handoffExamplesValidateWithoutClaimingSemanticGeneration() {
        val source = java.nio.file.Files.readString(java.nio.file.Path.of("../../content-contract/v1/examples.json"))
        val examples = foundationJson.parseToJsonElement(source).jsonArray
        assertEquals(5, examples.size)
        examples.forEach { example ->
            val document = example.jsonObject.getValue("document").jsonObject
            assertEquals(document, LearningContentModel.validate(document))
        }
    }
}
