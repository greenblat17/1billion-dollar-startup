package com.eliteteam.speakingcoach.foundation

import java.sql.DriverManager
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PersonalizedRoadmapIntegrationTest {
    @Test
    fun roadmapIsScopedDurableIdempotentAndClearedByLearningForget() = runTest {
        val url = requireNotNull(System.getenv("TEST_POSTGRES_URL"))
        val schema = "roadmap_${newId().replace("-", "")}"
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
                service.command(scope, json("version" to 1, "operationId" to newId(), "action" to "learning.update", "args" to
                    json("expectedRevision" to 0, "patch" to json("targetLevel" to "B2"), "goal" to "B2 for work",
                        "component" to "test", "sources" to sources)))
                val topic = manifest.topics.first { it.skillId == "grammar" }
                val created = service.command(scope, json("version" to 1, "operationId" to newId(), "action" to "learning.task.create", "args" to
                    json("goalRevision" to 1, "catalogVersion" to manifest.version, "skillId" to "grammar",
                        "topicId" to topic.id, "problemId" to "grammar_unknown", "component" to "test"))).obj("task")
                val entry = json("topicId" to topic.id, "skillId" to "grammar", "cefr" to topic.cefr,
                    "problemId" to "grammar_unknown", "knowledge" to "Unknown", "provisional" to true,
                    "reason" to "This is a hypothesis.", "orderReason" to "Earlier level first.",
                    "goalFocus" to false, "taskId" to created.text("id"), "order" to 1)
                val roadmap = json("schemaVersion" to 1, "catalogVersion" to manifest.version,
                    "goalRevision" to 1, "targetLevel" to "B2", "goal" to "B2 for work",
                    "provisional" to true, "sequence" to JsonArray(listOf(entry)), "taskCount" to 1,
                    "assumptions" to JsonArray(emptyList()),
                    "history" to JsonArray(emptyList()), "scope" to "initial_roadmap", "timeline" to null)
                suspend fun save(document: JsonObject, revision: Int = 1) = service.command(scope,
                    json("version" to 1, "operationId" to newId(), "action" to "learning.roadmap.save",
                        "args" to json("learningRevision" to revision, "roadmap" to document)))
                val saved = save(roadmap)
                assertEquals(roadmap, service.learningRoadmap(scope).obj("roadmap").obj("document"))
                assertEquals("false", save(roadmap).text("created"))
                assertEquals("version_conflict", assertFailsWith<FoundationFailure> { save(roadmap, 0) }.code)
                assertEquals("invalid_roadmap_task", assertFailsWith<FoundationFailure> {
                    save(JsonObject(roadmap + json("sequence" to JsonArray(listOf(JsonObject(entry + json("problemId" to "grammar_application")))))))
                }.code)
                val other = service.identity.resolve("telegram", System.nanoTime().toString())
                val foreign = service.identity.issue(other, "operator")
                assertEquals("roadmap_not_found", assertFailsWith<FoundationFailure> { service.learningRoadmap(foreign) }.code)
                service.command(scope, json("version" to 1, "operationId" to newId(), "action" to "learning.forget",
                    "args" to json("expectedRevision" to 1)))
                val fresh = service.identity.issue(student, "operator")
                assertEquals("roadmap_not_found", assertFailsWith<FoundationFailure> { service.learningRoadmap(fresh) }.code)
                assertEquals("true", saved.text("created"))
            }
        } finally {
            DriverManager.getConnection(url).use { it.createStatement().execute("DROP SCHEMA $schema CASCADE") }
        }
    }
}
