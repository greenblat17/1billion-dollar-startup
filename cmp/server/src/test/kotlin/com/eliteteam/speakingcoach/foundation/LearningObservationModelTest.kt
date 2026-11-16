package com.eliteteam.speakingcoach.foundation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal fun observationFixture(event: String = "event-1", revision: Long = 1) = json(
    "schemaVersion" to 1, "attemptId" to "attempt-1", "evidenceSlot" to "application-1", "anchorEventId" to event,
    "lessonId" to null, "occurredAt" to "2026-11-16T10:00:00Z", "sources" to JsonArray(listOf(
        json("eventId" to event, "revision" to revision, "role" to "user", "purpose" to "answer", "span" to json("start" to 0, "end" to 4)))),
    "catalogVersion" to "test-v1", "skillId" to "grammar", "topicId" to null, "problemId" to null,
    "practice" to "homework", "method" to "conversation", "kind" to "demonstration", "outcome" to "independent_correct",
    "support" to "independent", "initialOutcome" to "correct", "quality" to json("status" to "usable", "signals" to JsonArray(emptyList()), "confidence" to null),
    "provenance" to json("component" to "test", "analyzerVersion" to "v1"), "taskId" to null, "stageRef" to null,
    "actionId" to null, "repeatOf" to null, "route" to "assessment_only", "rationale" to "A demonstrated application")

class LearningObservationModelTest {
    private fun failure(d: JsonObject) = assertFailsWith<FoundationFailure> { LearningObservationModel.validate(d) }.code

    @Test fun publishedHandoffExamplesConformToTheBackendContract() {
        val text = java.nio.file.Files.readString(java.nio.file.Path.of("../../observation-contract/v1/examples.json"))
        (foundationJson.parseToJsonElement(text) as JsonArray).forEach {
            LearningObservationModel.validate((it as JsonObject).obj("observation"))
        }
    }

    @Test fun unknownClassificationAndUnknownConfidenceAreValid() {
        LearningObservationModel.validate(observationFixture())
        for (method in listOf("conversation", "explanation", "exercises", "exposure")) {
            LearningObservationModel.validate(JsonObject(observationFixture() + json("method" to method)))
        }
    }

    @Test fun independentCannotHideHintOrInitialError() {
        assertEquals("invalid_observation_outcome", failure(JsonObject(observationFixture() + json("support" to "hinted"))))
        assertEquals("invalid_observation_outcome", failure(JsonObject(observationFixture() + json("initialOutcome" to "error"))))
        val error = JsonObject(observationFixture() + json("outcome" to "self_corrected_error", "initialOutcome" to "error"))
        LearningObservationModel.validate(error)
    }

    @Test fun hintedAnswerRetainsInitialErrorAndNeedsHintSource() {
        var d = JsonObject(observationFixture() + json("outcome" to "hinted_correct", "initialOutcome" to "error", "support" to "hinted"))
        assertEquals("hint_source_required", failure(d))
        d = JsonObject(d + json("sources" to JsonArray((d["sources"] as JsonArray) +
            json("eventId" to "hint", "revision" to 2, "role" to "assistant", "purpose" to "hint"))))
        LearningObservationModel.validate(d)
    }

    @Test fun uncertaintyRequiresExplicitReasonAndProvenance() {
        val base = observationFixture()
        assertEquals("invalid_quality_signals", failure(JsonObject(base + json("quality" to json("status" to "uncertain", "signals" to JsonArray(emptyList()))))))
        val q = json("status" to "uncertain", "signals" to JsonArray(listOf(json("reason" to "uncertain_asr", "component" to "fixture", "sourceEventId" to "event-1"))))
        LearningObservationModel.validate(JsonObject(base + json("quality" to q)))
        assertEquals("invalid_quality_status", failure(JsonObject(base + json("quality" to JsonObject(q + json("status" to "usable"))))))
    }

    @Test fun selfReportAndUnknownCannotBecomeScoringSuccess() {
        assertEquals("invalid_observation_outcome", failure(JsonObject(observationFixture() + json("kind" to "self_report"))))
        assertEquals("observation_classification_required", failure(JsonObject(observationFixture() + json("route" to "problem_detection"))))
        assertEquals("stage_reference_required", failure(JsonObject(observationFixture() + json("route" to "stage_progress"))))
    }

    @Test fun rejectsUndocumentedFieldsAndInvalidSpans() {
        assertEquals("unknown_field", failure(JsonObject(observationFixture() + json("score" to 90))))
        val source = json("eventId" to "event-1", "revision" to 1, "role" to "user", "purpose" to "answer", "span" to json("start" to 4, "end" to 3))
        assertEquals("invalid_source_span", failure(JsonObject(observationFixture() + json("sources" to JsonArray(listOf(source))))))
    }
}
