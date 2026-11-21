package com.eliteteam.speakingcoach.foundation

import java.sql.DriverManager
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class LearningScoresIntegrationTest {
    private suspend fun op(s: FoundationService, scope: String, action: String, args: kotlinx.serialization.json.JsonObject) =
        s.command(scope, json("version" to 1, "operationId" to newId(), "action" to action, "args" to args))

    @Test
    fun fixtureEventsPersistWithoutDoubleCountingOrImplicitReopen() = runTest {
        val url = requireNotNull(System.getenv("TEST_POSTGRES_URL"))
        val schema = "score_${newId().replace("-", "")}"
        DriverManager.getConnection(url).use { it.createStatement().execute("CREATE SCHEMA $schema") }
        val scoped = url + (if ('?' in url) "&" else "?") + "currentSchema=$schema"
        try {
            FoundationService(FoundationDatabase(scoped)).use { s ->
                val manifest = catalogFixture()
                LearningCatalog(s.database).import(LearningCatalogModel.document(manifest).toString())
                LearningCatalog(s.database).publish(manifest.version, "synthetic-test", "fixture only")
                val student = s.identity.resolve("telegram", System.nanoTime().toString())
                val scope = s.identity.issue(student, "operator")
                val event = s.history.accept(student, newId(), "I want B2 for work", Instant.now(), false)
                val sources = s.database.transaction(readOnly = true) { c, _ ->
                    val e = c.document("SELECT to_jsonb(e) FROM conversation_event e WHERE id=?", event.text("event_id"))!!
                    JsonArray(listOf(json("type" to "event", "id" to e.text("id"), "revision" to e.number("sequence"))))
                }
                op(s, scope, "learning.update", json("expectedRevision" to 0, "patch" to json(), "goal" to "B2 for work",
                    "component" to "test", "sources" to sources))
                val topic = manifest.topics.first { it.skillId == "grammar" }
                val task = op(s, scope, "learning.task.create", json("goalRevision" to 1, "catalogVersion" to manifest.version,
                    "skillId" to "grammar", "topicId" to topic.id, "problemId" to "grammar_application", "component" to "test")).obj("task")
                val plan = op(s, scope, "learning.plan.create", json("taskId" to task.text("id"),
                    "expectedTaskRevision" to task.number("revision"), "definition" to planFixture(), "component" to "test")).obj("plan")
                val base = json("taskId" to task.text("id"), "planId" to plan.text("id"), "planRevision" to 1,
                    "stageId" to "exercise", "fixtureEventId" to "e1", "outcome" to "independent_correct")
                assertEquals(0.0, s.learningScore(scope, task.text("id"))["progress"]!!.jsonPrimitive.doubleOrNull)
                val first = op(s, scope, "learning.score.fixture", base)
                assertTrue(first["progress"]!!.jsonPrimitive.doubleOrNull!! > 0)
                assertEquals(first, op(s, scope, "learning.score.fixture", base))
                assertEquals(1, s.learningScore(scope, task.text("id"), history = true)["items"]!!.jsonArray.size)
                val hinted = op(s, scope, "learning.score.fixture", json("taskId" to task.text("id"), "planId" to plan.text("id"),
                    "planRevision" to 1, "stageId" to "exercise", "fixtureEventId" to "hint", "outcome" to "hinted_correct"))
                assertEquals(first["progress"], hinted["progress"])
                for (n in 2..16) op(s, scope, "learning.score.fixture", json("taskId" to task.text("id"), "planId" to plan.text("id"),
                    "planRevision" to 1, "stageId" to "exercise", "fixtureEventId" to "e$n", "outcome" to "independent_correct"))
                val passed = s.learningScore(scope, task.text("id"))
                assertEquals(30.0, passed["progress"]!!.jsonPrimitive.doubleOrNull)
                assertFalse(passed["masteryReady"]!!.jsonPrimitive.content.toBoolean())
                val binary = op(s, scope, "learning.score.fixture", json("taskId" to task.text("id"), "planId" to plan.text("id"),
                    "planRevision" to 1, "stageId" to "explain", "fixtureEventId" to "understood", "binaryMet" to true))
                assertEquals(30.0, binary["progress"]!!.jsonPrimitive.doubleOrNull)
                assertFalse(binary["masteryReady"]!!.jsonPrimitive.content.toBoolean())
                assertTrue((binary["stages"]!!.jsonArray[0] as kotlinx.serialization.json.JsonObject)["completed"]!!.jsonPrimitive.content.toBoolean())
                val passedAttemptId = (passed["stages"]!!.jsonArray[1] as kotlinx.serialization.json.JsonObject).text("stageAttemptId")
                val error = op(s, scope, "learning.score.fixture", json("taskId" to task.text("id"), "planId" to plan.text("id"),
                    "planRevision" to 1, "stageId" to "exercise", "fixtureEventId" to "late-error", "outcome" to "uncorrected_error"))
                assertEquals(30.0, error["progress"]!!.jsonPrimitive.doubleOrNull)
                assertEquals("passed", error["stages"]!!.jsonArray[1].let { (it as kotlinx.serialization.json.JsonObject).text("signal") })
                assertEquals(passedAttemptId, (error["stages"]!!.jsonArray[1] as kotlinx.serialization.json.JsonObject).text("stageAttemptId"))
                assertFailsWith<FoundationFailure> { op(s, scope, "learning.score.fixture", json("taskId" to task.text("id"),
                    "planId" to plan.text("id"), "planRevision" to 1, "stageId" to "exercise", "fixtureEventId" to "reopen-without-reason",
                    "reopen" to true)) }
                assertFailsWith<FoundationFailure> { op(s, scope, "learning.score.fixture", json("taskId" to task.text("id"),
                    "planId" to plan.text("id"), "planRevision" to 1, "stageId" to "exercise", "fixtureEventId" to "e1",
                    "outcome" to "uncorrected_error")) }
                FoundationService(FoundationDatabase(scoped)).use { restarted ->
                    val readScope = restarted.identity.issue(student, "operator")
                    assertEquals(error["progress"], restarted.learningScore(readScope, task.text("id"))["progress"])
                }
                val reopened = op(s, scope, "learning.score.fixture", json("taskId" to task.text("id"), "planId" to plan.text("id"),
                    "planRevision" to 1, "stageId" to "exercise", "fixtureEventId" to "explicit-reopen", "reopen" to true,
                    "reason" to "pedagogical_review"))
                assertEquals(0.0, reopened["progress"]!!.jsonPrimitive.doubleOrNull)
                val current = reopened["stages"]!!.jsonArray[1] as kotlinx.serialization.json.JsonObject
                assertEquals(2L, current.number("attemptNumber"))
                assertTrue(current.text("stageAttemptId") != passedAttemptId)
                assertTrue(s.learningScore(scope, task.text("id"), history = true)["items"]!!.jsonArray.size > 17)
            }
        } finally {
            DriverManager.getConnection(url).use { it.createStatement().execute("DROP SCHEMA $schema CASCADE") }
        }
    }
}
