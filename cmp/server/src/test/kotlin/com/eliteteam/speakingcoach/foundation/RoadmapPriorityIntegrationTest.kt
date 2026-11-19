package com.eliteteam.speakingcoach.foundation

import java.sql.DriverManager
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RoadmapPriorityIntegrationTest {
    @Test
    fun learnerDecisionAndPriorityAreDurableWithoutChangingTaskProgress() = runTest {
        val url = requireNotNull(System.getenv("TEST_POSTGRES_URL"))
        val schema = "priority_${newId().replace("-", "")}"
        DriverManager.getConnection(url).use { it.createStatement().execute("CREATE SCHEMA $schema") }
        val scoped = url + (if ('?' in url) "&" else "?") + "currentSchema=$schema"
        try {
            FoundationService(FoundationDatabase(scoped)).use { service ->
                val manifest = catalogFixture()
                LearningCatalog(service.database).import(LearningCatalogModel.document(manifest).toString())
                LearningCatalog(service.database).publish(manifest.version, "synthetic-test", "fixture only")
                val student = service.identity.resolve("telegram", System.nanoTime().toString())
                val scope = service.identity.issue(student, "operator")
                val event = service.history.accept(student, newId(), "B2 for work", Instant.now(), false)
                val sources = service.database.transaction(readOnly = true) { c, _ ->
                    val row = c.document("SELECT to_jsonb(e) FROM conversation_event e WHERE id=?", event.text("event_id"))!!
                    JsonArray(listOf(json("type" to "event", "id" to row.text("id"), "revision" to row.number("sequence"))))
                }
                suspend fun command(action: String, args: kotlinx.serialization.json.JsonObject) = service.command(scope,
                    json("version" to 1, "operationId" to newId(), "action" to action, "args" to args))
                command("learning.update", json("expectedRevision" to 0, "patch" to json("targetLevel" to "B2"),
                    "goal" to "B2 for work", "component" to "test", "sources" to sources))
                val topics = manifest.topics.filter { it.skillId == "grammar" }.take(2)
                val first = command("learning.task.create", json("goalRevision" to 1, "catalogVersion" to manifest.version,
                    "skillId" to "grammar", "topicId" to topics[0].id, "problemId" to "grammar_unknown",
                    "component" to "test")).obj("task")
                val initial = json("schemaVersion" to 1, "catalogVersion" to manifest.version, "goalRevision" to 1,
                    "targetLevel" to "B2", "goal" to "B2 for work", "provisional" to true,
                    "sequence" to JsonArray(listOf(json("topicId" to topics[0].id, "skillId" to "grammar",
                        "cefr" to topics[0].cefr, "problemId" to "grammar_unknown", "knowledge" to "Unknown",
                        "provisional" to true, "reason" to "Hypothesis", "orderReason" to "Current level",
                        "goalFocus" to false, "taskId" to first.text("id"), "order" to 1))),
                    "taskCount" to 1, "assumptions" to JsonArray(emptyList()), "history" to JsonArray(emptyList()),
                    "scope" to "initial_roadmap", "timeline" to null)
                command("learning.roadmap.save", json("learningRevision" to 1, "roadmap" to initial))
                command("learning.roadmap.problem.decide", json("topicId" to topics[1].id,
                    "problemId" to "grammar_unknown", "skillId" to "grammar", "decision" to "declined",
                    "source" to "learner_response", "reason" to "Learner wants another topic"))
                assertEquals(1, (service.learningRoadmapPriority(scope)["problemDecisions"] as JsonArray).size)
                val accepted = command("learning.roadmap.problem.decide", json("topicId" to topics[1].id,
                    "problemId" to "grammar_unknown", "skillId" to "grammar", "decision" to "accepted",
                    "source" to "learner_request", "reason" to "Learner asked directly"))
                val addedId = (accepted["problemDecisions"] as JsonArray).single().let { (it as kotlinx.serialization.json.JsonObject).text("taskId") }
                val repeated = command("learning.roadmap.problem.decide", json("topicId" to topics[1].id,
                    "problemId" to "grammar_unknown", "skillId" to "grammar", "decision" to "accepted",
                    "source" to "learner_request", "reason" to "Same request"))
                assertEquals(addedId, (repeated["problemDecisions"] as JsonArray).single().let {
                    (it as kotlinx.serialization.json.JsonObject).text("taskId") })
                val decision = json("schemaVersion" to 1, "roadmapRevision" to 1, "trigger" to "learner_request",
                    "orderedTasks" to JsonArray(listOf(
                        json("taskId" to addedId, "priority" to 1, "reason" to "Learner request",
                            "evidence" to "hypothesis", "prerequisiteTaskIds" to JsonArray(emptyList())),
                        json("taskId" to first.text("id"), "priority" to 2, "reason" to "Initial work",
                            "evidence" to "hypothesis", "prerequisiteTaskIds" to JsonArray(emptyList())))),
                    "nextTaskId" to addedId, "focusTaskIds" to JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(addedId))),
                    "deferredTaskIds" to JsonArray(emptyList()), "declinedProblems" to JsonArray(emptyList()),
                    "needsRoadmapReview" to false, "explanation" to "Learner request", "scope" to "operational_priority")
                command("learning.roadmap.priority.save", json("expectedRevision" to 0, "decision" to decision))
                assertEquals(addedId, service.learningRoadmapPriority(scope).obj("priority").obj("document").text("nextTaskId"))
                val rankedTask = service.learningTask(scope, addedId).obj("task")
                assertEquals(0.0, LearningTaskModel.progress(rankedTask["progress"]))
                assertEquals(1L, rankedTask.obj("details").number("priority"))
                assertEquals("version_conflict", assertFailsWith<FoundationFailure> {
                    command("learning.roadmap.priority.save", json("expectedRevision" to 0, "decision" to decision))
                }.code)
            }
        } finally {
            DriverManager.getConnection(url).use { it.createStatement().execute("DROP SCHEMA $schema CASCADE") }
        }
    }
}
