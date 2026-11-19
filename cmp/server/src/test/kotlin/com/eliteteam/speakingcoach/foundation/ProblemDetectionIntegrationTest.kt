package com.eliteteam.speakingcoach.foundation

import java.sql.DriverManager
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProblemDetectionIntegrationTest {
    @Test fun observationRevisionsProposalDeclineAndActiveTaskExclusion() = runTest {
        val url = requireNotNull(System.getenv("TEST_POSTGRES_URL"))
        val schema = "problems_${newId().replace("-", "")}"
        DriverManager.getConnection(url).use { it.createStatement().execute("CREATE SCHEMA $schema") }
        val scoped = url + (if ('?' in url) "&" else "?") + "currentSchema=$schema"
        try {
            FoundationService(FoundationDatabase(scoped)).use { service ->
                val manifest = catalogFixture()
                LearningCatalog(service.database).import(LearningCatalogModel.document(manifest).toString())
                LearningCatalog(service.database).publish(manifest.version, "synthetic", "fixture only")
                val student = service.identity.resolve("telegram", System.nanoTime().toString())
                val scope = service.identity.issue(student, "operator")
                val topic = manifest.topics.first { it.skillId == "grammar" }.id
                val problem = "grammar_unknown"
                suspend fun command(action: String, args: JsonObject) = service.command(scope,
                    json("version" to 1, "operationId" to newId(), "action" to action, "args" to args))
                suspend fun observe(index: Int, outcome: String): JsonObject {
                    val event = service.history.accept(student, newId(), "Evidence attempt $index", Instant.now(), false)
                    val source = service.database.transaction(readOnly = true) { c, _ ->
                        c.document("SELECT to_jsonb(e) FROM conversation_event e WHERE id=?", event.text("event_id"))!!
                    }
                    val base = observationFixture(source.text("id"), source.number("sequence"))
                    val document = JsonObject(base + json("attemptId" to "attempt-$index", "evidenceSlot" to "slot-$index",
                        "occurredAt" to "2026-11-16T10:00:${index.toString().padStart(2, '0')}Z",
                        "catalogVersion" to manifest.version, "topicId" to topic, "problemId" to problem,
                        "route" to "problem_detection", "outcome" to outcome,
                        "initialOutcome" to if (outcome == "independent_correct") "correct" else "error"))
                    return command("learning.observation.record", json("observation" to document)).obj("observation")
                }
                assertEquals(0, service.learningProblems(scope, topic, problem, 0)["items"]!!.jsonArray.size)
                val first = observe(1, "uncorrected_error")
                assertEquals(15.0, service.learningProblems(scope, topic, problem, 0)["items"]!!.jsonArray.single().jsonObject.double("score"))
                observe(2, "uncorrected_error")
                assertEquals(27.75, service.learningProblems(scope, topic, problem, 0)["items"]!!.jsonArray.single().jsonObject.double("score"))
                val revised = JsonObject(first.obj("document") + json("outcome" to "self_corrected_error"))
                command("learning.observation.revise", json("observationId" to first.text("id"), "expectedRevision" to 1,
                    "observation" to revised, "reason" to "Self correction was heard"))
                assertEquals(20.95, service.learningProblems(scope, topic, problem, 0)["items"]!!.jsonArray.single().jsonObject.double("score"), 0.00001)
                observe(3, "independent_correct")
                assertEquals(18.855, service.learningProblems(scope, topic, problem, 0)["items"]!!.jsonArray.single().jsonObject.double("score"), 0.00001)
                for (index in 4..16) observe(index, "uncorrected_error")
                val candidate = service.learningProblems(scope, topic, problem, 0)["items"]!!.jsonArray.single().jsonObject
                assertTrue(candidate.double("score") >= 80)
                assertEquals("consider_task", candidate.obj("proposal").text("action"))
                assertEquals(0, service.learningTasks(scope, topic, problem, null, 0)["items"]!!.jsonArray.size)
                val event = service.history.accept(student, newId(), "Goal for task", Instant.now(), false)
                val source = service.database.transaction(readOnly = true) { c, _ ->
                    c.document("SELECT to_jsonb(e) FROM conversation_event e WHERE id=?", event.text("event_id"))!!
                }
                command("learning.update", json("expectedRevision" to 0, "patch" to json("targetLevel" to "B2"),
                    "goal" to "B2 for work", "component" to "test", "sources" to JsonArray(listOf(
                        json("type" to "event", "id" to source.text("id"), "revision" to source.number("sequence"))))))
                val baseTopic = manifest.topics.first { it.skillId == "grammar" && it.id != topic }
                val baseTask = command("learning.task.create", json("goalRevision" to 1, "catalogVersion" to manifest.version,
                    "skillId" to "grammar", "topicId" to baseTopic.id, "problemId" to problem, "component" to "test")).obj("task")
                command("learning.roadmap.save", json("learningRevision" to 1, "roadmap" to json(
                    "schemaVersion" to 1, "catalogVersion" to manifest.version, "goalRevision" to 1,
                    "targetLevel" to "B2", "goal" to "B2 for work", "provisional" to true,
                    "sequence" to JsonArray(listOf(json("topicId" to baseTopic.id, "skillId" to "grammar",
                        "cefr" to baseTopic.cefr, "problemId" to problem, "knowledge" to "Unknown",
                        "provisional" to true, "reason" to "Hypothesis", "orderReason" to "Current level",
                        "goalFocus" to false, "taskId" to baseTask.text("id"), "order" to 1))),
                    "taskCount" to 1, "assumptions" to JsonArray(emptyList()), "history" to JsonArray(emptyList()),
                    "scope" to "initial_roadmap", "timeline" to null)))
                command("learning.roadmap.problem.decide", json("topicId" to topic, "problemId" to problem,
                    "skillId" to "grammar", "decision" to "declined", "source" to "learner_response",
                    "reason" to "Learner declined"))
                val declined = service.learningProblems(scope, topic, problem, 0)["items"]!!.jsonArray.single().jsonObject
                assertEquals("declined", declined.text("learnerDecision"))
                assertNull(declined["proposal"]?.takeUnless { it is kotlinx.serialization.json.JsonNull })
                command("learning.roadmap.problem.decide", json("topicId" to topic, "problemId" to problem,
                    "skillId" to "grammar", "decision" to "accepted", "source" to "learner_request",
                    "reason" to "Learner asked directly"))
                val task = service.learningTasks(scope, topic, problem, null, 0)["items"]!!.jsonArray.single().jsonObject
                assertEquals(0.0, task.double("progress"))
                assertEquals(1, service.learningTasks(scope, topic, problem, null, 0)["items"]!!.jsonArray.size)
                assertEquals(task.text("id"), command("learning.task.create", json("goalRevision" to 1, "catalogVersion" to manifest.version,
                    "skillId" to "grammar", "topicId" to topic, "problemId" to problem, "component" to "test")).obj("task")
                    .text("id"))
                assertEquals(task.text("id"), service.learningProblems(scope, topic, problem, 0)["items"]!!.jsonArray.single().jsonObject.text("activeTaskId"))
                val frozen = service.learningProblems(scope, topic, problem, 0)["items"]!!.jsonArray.single().jsonObject.double("score")
                assertEquals("observation_active_task", assertFailsWith<FoundationFailure> {
                    observe(17, "uncorrected_error")
                }.code)
                assertEquals(frozen, service.learningProblems(scope, topic, problem, 0)["items"]!!.jsonArray.single().jsonObject.double("score"))
            }
        } finally {
            DriverManager.getConnection(url).use { it.createStatement().execute("DROP SCHEMA $schema CASCADE") }
        }
    }
}
