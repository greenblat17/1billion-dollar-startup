package com.eliteteam.speakingcoach.foundation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LearningTaskModelTest {
    @Test
    fun omittedFieldsPreserveLinksAndExplicitNullClearsOnlyTheChosenField() {
        val initial = LearningTaskModel.details(LearningTaskModel.emptyDetails(), json("priority" to 1,
            "plan" to json("id" to "plan", "revision" to 2), "actions" to JsonArray(listOf(json("id" to "action")))))
        val changed = LearningTaskModel.details(initial, json("priority" to null))
        assertEquals(JsonNull, changed["priority"])
        assertEquals(initial["plan"], changed["plan"])
        assertEquals(initial["actions"], changed["actions"])
    }

    @Test
    fun linksRejectEmbeddedDomainStateDuplicateIdsAndInvalidVersions() {
        for (patch in listOf(json("plan" to json("id" to "plan", "score" to 85)),
            json("plan" to json("id" to "plan", "revision" to 0)), json("actions" to null),
            json("priority" to "high"), json("priority" to 0.1),
            json("homework" to JsonArray(listOf(json("id" to "x"), json("id" to "x")))))) {
            assertFailsWith<FoundationFailure> { LearningTaskModel.details(LearningTaskModel.emptyDetails(), patch) }
        }
    }

    @Test
    fun invalidProgressAndRevisionCannotCoerceToAValidNumber() {
        for (value in listOf(JsonNull, JsonPrimitive("50"), JsonPrimitive(-1), JsonPrimitive(100.01))) {
            assertFailsWith<FoundationFailure> { LearningTaskModel.progress(value) }
        }
        for (value in listOf(JsonNull, JsonPrimitive("1"), JsonPrimitive(0), JsonPrimitive(1.5))) {
            assertFailsWith<FoundationFailure> { LearningTaskModel.positive(value) }
        }
        assertEquals(17.14, LearningTaskModel.progress(JsonPrimitive(17.14)))
        assertFailsWith<FoundationFailure> { LearningTaskModel.transition("Identified", "Mastered") }
        assertFailsWith<FoundationFailure> { LearningTaskModel.transition("In Progress", "Identified") }
    }
}
