package com.eliteteam.speakingcoach.foundation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LearningGoalTest {
    @Test
    fun preservesQuotesAndSourcesWhenLearnerAddsToTheGoal() {
        val quote = "Хочу переехать в США, устроиться там Go-разработчиком и свободно общаться с людьми."
        val update = "А ещё хочу понимать сериалы без субтитров!"
        val firstSource = json("type" to "event", "id" to "first", "revision" to 1)
        val secondSource = json("type" to "event", "id" to "second", "revision" to 2)
        val firstIds = listOf("G05", "G03", "G01").map(::JsonPrimitive)
        val first = learningGoalContent(
            json("goals" to JsonArray(firstIds), "mode" to "replace", "personal_goal" to quote),
            null, JsonArray(listOf(firstSource)),
        )
        assertEquals(quote, first.text("personal_goal"))

        val extended = learningGoalContent(
            json("goals" to JsonArray(listOf(JsonPrimitive("G07"))), "mode" to "extend", "personal_goal" to update),
            first, JsonArray(listOf(secondSource)),
        )
        assertEquals("$quote\n$update", extended.text("personal_goal"))
        assertEquals(extended.text("personal_goal"), extended.text("value"))
        assertEquals(JsonArray(firstIds + JsonPrimitive("G07")), extended["goals"])
        assertEquals(JsonArray(listOf(firstSource, secondSource)), extended["sources"])
    }

    @Test
    fun rejectsUnknownGoalIdsAndKeepsLegacyStringWrites() {
        val sources = JsonArray(emptyList())
        assertFailsWith<FoundationFailure> {
            learningGoalContent(
                json("goals" to JsonArray(listOf(JsonPrimitive("G09"))), "mode" to "replace", "personal_goal" to "Goal"),
                null, sources,
            )
        }
        assertEquals(json("value" to "Existing goal", "sources" to sources),
            learningGoalContent(JsonPrimitive("Existing goal"), null, sources))
    }
}
