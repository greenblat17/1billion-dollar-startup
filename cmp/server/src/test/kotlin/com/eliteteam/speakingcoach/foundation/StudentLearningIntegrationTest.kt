package com.eliteteam.speakingcoach.foundation

import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StudentLearningIntegrationTest {
    private class TestClock(var time: Instant = Instant.now()) : Clock() {
        override fun instant() = time
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private suspend fun store(clock: Clock = Clock.systemUTC(), block: suspend (FoundationService, String) -> Unit) {
        val url = requireNotNull(System.getenv("TEST_POSTGRES_URL"))
        val schema = "learning_${newId().replace("-", "") }"
        DriverManager.getConnection(url).use { it.createStatement().execute("CREATE SCHEMA $schema") }
        val scoped = url + (if ('?' in url) "&" else "?") + "currentSchema=$schema"
        try { FoundationService(FoundationDatabase(scoped, clock)).use { block(it, scoped) } }
        finally { DriverManager.getConnection(url).use { it.createStatement().execute("DROP SCHEMA $schema CASCADE") } }
    }
    private suspend fun FoundationService.op(token: String, action: String, args: JsonObject, id: String = newId()) =
        command(token, json("version" to 1, "operationId" to id, "action" to action, "args" to args))
    private suspend fun FoundationService.source(student: String, at: Instant = Instant.now()): JsonObject {
        val event = history.accept(student, newId(), "I want B2 for work. I can study three times a week.", at, false)
        return database.transaction(readOnly = true) { c, _ ->
            val row = c.document("SELECT to_jsonb(e) FROM conversation_event e WHERE id=?", event.text("event_id"))!!
            json("type" to "event", "id" to row.text("id"), "revision" to row.number("sequence"))
        }
    }
    private fun update(source: JsonObject, revision: Long = 0, patch: JsonObject = json("targetLevel" to "B2")) =
        json("expectedRevision" to revision, "patch" to patch, "component" to "test", "sources" to JsonArray(listOf(source)))
    private suspend fun FoundationService.catalog(): CatalogManifest {
        val manifest = catalogFixture()
        LearningCatalog(database).import(LearningCatalogModel.document(manifest).toString())
        LearningCatalog(database).publish(manifest.version, "synthetic-test", "test only")
        return manifest
    }
    private fun topic(manifest: CatalogManifest, source: JsonObject, status: String, revision: Long) = json(
        "catalogVersion" to manifest.version, "topicId" to manifest.topics.first().id, "expectedRevision" to revision,
        "status" to status, "explanation" to "Evidence for this recorded assessment", "component" to "test-assessment",
        "sources" to JsonArray(listOf(source)))

    @Test
    fun profileSurvivesRestartAndMemoryIsPersonalOnly() = runTest {
        store { service, url ->
            val student = service.identity.resolve("telegram", "93001")
            val scope = service.identity.issue(student, "operator")
            val source = service.source(student)
            val args = JsonObject(update(source) + json("goal" to "B2 for work"))
            val id = newId()
            val saved = service.op(scope, "learning.update", args, id)
            assertEquals(saved, service.op(scope, "learning.update", args, id))
            assertEquals("operation_conflict", assertFailsWith<FoundationFailure> {
                service.op(scope, "learning.update", update(source, patch = json("targetLevel" to "C1")), id)
            }.code)
            assertEquals("version_conflict", assertFailsWith<FoundationFailure> { service.op(scope, "learning.update", args) }.code)
            val next = service.op(scope, "learning.update", update(source, 1, json("intensity" to json("lessonsPerWeek" to 3))))
            assertEquals("B2", next.obj("profile").text("targetLevel"))
            assertEquals("B2 for work", next.obj("goal").obj("content").text("value"))
            assertEquals("learning_state_required", assertFailsWith<FoundationFailure> {
                service.op(scope, "memory.record", json("sourceId" to source.text("id"), "evidence" to "B2",
                    "category" to "learning", "key" to "level", "kind" to "self_assessment", "value" to "B2", "expectedVersion" to 0))
            }.code)
            service.op(scope, "memory.record", json("sourceId" to source.text("id"), "evidence" to "work",
                "category" to "personal", "key" to "interest", "kind" to "user_report", "value" to "work", "expectedVersion" to 0))
            FoundationService(FoundationDatabase(url)).use { restarted ->
                val token = restarted.identity.issue(student, "operator")
                assertEquals(next, restarted.learningState(token))
                val snapshot = restarted.snapshot(token, "text")
                assertEquals("B2", snapshot.obj("learningState").obj("profile").text("targetLevel"))
                assertEquals("interest", snapshot["facts"]!!.jsonArray.single().jsonObject.text("fact_key"))
            }
        }
    }

    @Test
    fun topicHistoryRetentionCatalogIsolationAndExplicitDeletion() = runTest {
        val clock = TestClock()
        store(clock) { service, _ ->
            val student = service.identity.resolve("telegram", "93002")
            var scope = service.identity.issue(student, "operator")
            val source = service.source(student, clock.time)
            val manifest = service.catalog()
            assertEquals("Unknown", service.topicKnowledge(scope, manifest.version, manifest.topics.first().id, 0, false)["items"]!!.jsonArray.single().jsonObject.text("status"))
            for ((index, status) in listOf("Presumed Not Known", "Not Known", "Known").withIndex()) {
                service.op(scope, "learning.topic.record", topic(manifest, source, status, index.toLong()))
            }
            val history = service.topicKnowledge(scope, manifest.version, manifest.topics.first().id, 0, true)["items"]!!.jsonArray
            assertEquals(listOf("Known", "Not Known", "Presumed Not Known"), history.map { it.jsonObject.text("status") })
            val next = manifest.copy(version = "sp03-next")
            LearningCatalog(service.database).import(LearningCatalogModel.document(next).toString())
            LearningCatalog(service.database).publish(next.version, "synthetic-test", "test only")
            assertEquals("Unknown", service.topicKnowledge(scope, next.version, next.topics.first().id, 0, false)["items"]!!.jsonArray.single().jsonObject.text("status"))
            clock.time = clock.time.plusSeconds(HISTORY_SECONDS + 1)
            scope = service.identity.issue(student, "operator")
            service.op(scope, "retention.prune", json())
            val retained = service.topicKnowledge(scope, manifest.version, manifest.topics.first().id, 0, false)["items"]!!.jsonArray.single().jsonObject
            assertEquals("Known", retained.text("status"))
            assertEquals("true", retained["sources"]!!.jsonArray.single().jsonObject.text("sourceContentExpired"))
            assertEquals("source_expired", assertFailsWith<FoundationFailure> {
                service.op(scope, "learning.topic.record", topic(manifest, source, "Known", 3))
            }.code)
            service.op(scope, "learning.forget", json("expectedRevision" to 0))
            val fresh = service.identity.issue(student, "operator")
            assertTrue(service.topicKnowledge(fresh, manifest.version, manifest.topics.first().id, 0, true)["items"]!!.jsonArray.isEmpty())
            assertEquals("scope_expired", assertFailsWith<FoundationFailure> { service.learningState(scope) }.code)
        }
    }

    @Test
    fun observationReferencesAreOwnedVersionedAndNeverSynthesized() = runTest {
        store { service, _ ->
            val student = service.identity.resolve("telegram", "93005")
            val scope = service.identity.issue(student, "operator")
            val event = service.source(student)
            val manifest = service.catalog()
            val lesson = service.op(scope, "lesson.create", json("kind" to "trial", "launchMode" to "executable",
                "startsAt" to Instant.now().plusSeconds(600).toString(), "durationMinutes" to 30, "timezone" to "UTC"))
            val observationId = newId()
            service.database.transaction { c, now ->
                c.update("UPDATE lesson SET transcript_revision=1 WHERE id=?", lesson.text("id"))
                c.update("""INSERT INTO memory_observation(id,student_id,lesson_id,source_revision,analyzer_version,
                    observation_key,dimension,content,uncertainty,source_event_id,created_at)
                    VALUES (?,?,?,1,'test','grammar-test','grammar','Independent use','limited',?,?)""",
                    observationId, student, lesson.text("id"), event.text("id"), now)
            }
            val source = json("type" to "observation", "id" to observationId, "revision" to 1)
            val args = topic(manifest, source, "Known", 0)
            service.op(scope, "learning.topic.record", args, "observation-assessment")
            assertEquals(1L, service.op(scope, "learning.topic.record", args, "observation-assessment").number("revision"))
            service.database.transaction { c, _ -> c.update("UPDATE lesson SET transcript_revision=2 WHERE id=?", lesson.text("id")) }
            assertEquals("stale_learning_source", assertFailsWith<FoundationFailure> {
                service.op(scope, "learning.topic.record", topic(manifest, source, "Known", 1))
            }.code)
            val retained = service.topicKnowledge(scope, manifest.version, manifest.topics.first().id, 0, false)["items"]!!.jsonArray.single().jsonObject
            assertEquals("false", retained["sources"]!!.jsonArray.single().jsonObject.text("sourceRevisionCurrent"))
            assertEquals("learning_source_not_owned", assertFailsWith<FoundationFailure> {
                service.op(scope, "learning.topic.record", topic(manifest,
                    json("type" to "observation", "id" to newId(), "revision" to 1), "Known", 1))
            }.code)
            assertEquals(1, service.topicKnowledge(scope, manifest.version, manifest.topics.first().id, 0, true)["items"]!!.jsonArray.size)
        }
    }

    @Test
    fun personalForgetPreservesIndependentLearningAndRedactsOnlyLinkedHistory() = runTest {
        store { service, _ ->
            val student = service.identity.resolve("telegram", "93006")
            var scope = service.identity.issue(student, "operator")
            val independent = service.source(student)
            val personal = service.source(student)
            val manifest = service.catalog()
            service.op(scope, "memory.record", json("sourceId" to personal.text("id"), "evidence" to "work",
                "category" to "personal", "key" to "interest", "kind" to "user_report", "value" to "work", "expectedVersion" to 0))
            service.op(scope, "learning.update", JsonObject(update(independent) + json("goal" to "Independent goal")))
            service.op(scope, "learning.update", update(personal, 1, json("intensity" to json("lessonsPerWeek" to 3))))
            service.op(scope, "learning.topic.record", topic(manifest, personal, "Not Known", 0))
            service.op(scope, "learning.topic.record", topic(manifest, independent, "Known", 1))
            service.op(scope, "memory.forget", json("category" to "personal", "key" to "interest", "expectedVersion" to 1))
            scope = service.identity.issue(student, "operator")
            val state = service.learningState(scope)
            assertEquals("B2", state.obj("profile").text("targetLevel"))
            assertEquals("Independent goal", state.obj("goal").obj("content").text("value"))
            assertEquals(kotlinx.serialization.json.JsonNull, state.obj("profile").obj("intensity")["lessonsPerWeek"])
            val history = service.topicKnowledge(scope, manifest.version, manifest.topics.first().id, 0, true)["items"]!!.jsonArray
            assertEquals(listOf("Known", "Unknown"), history.map { it.jsonObject.text("status") })
            assertEquals("Evidence for this recorded assessment", history.first().jsonObject.text("explanation"))
            assertEquals("", history.last().jsonObject.text("explanation"))
            assertTrue(history.last().jsonObject["sources"]!!.jsonArray.isEmpty())
        }
    }

    @Test
    fun unrelatedForgetKeepsProfileAndObservationLineageForLaterRelatedForget() = runTest {
        store { service, _ ->
            val student = service.identity.resolve("telegram", "93007")
            var scope = service.identity.issue(student, "operator")
            val independent = service.source(student)
            val personal = service.source(student)
            val manifest = service.catalog()
            for ((key, source) in listOf("interest" to personal, "job" to independent)) {
                service.op(scope, "memory.record", json("sourceId" to source.text("id"), "evidence" to "work",
                    "category" to "personal", "key" to key, "kind" to "user_report", "value" to "work", "expectedVersion" to 0))
            }
            val lesson = service.op(scope, "lesson.create", json("kind" to "trial", "launchMode" to "executable",
                "startsAt" to Instant.now().plusSeconds(600).toString(), "durationMinutes" to 30, "timezone" to "UTC"))
            val observationId = newId()
            service.database.transaction { c, now ->
                c.update("UPDATE lesson SET transcript_revision=1 WHERE id=?", lesson.text("id"))
                c.update("""INSERT INTO memory_observation(id,student_id,lesson_id,source_revision,analyzer_version,
                    observation_key,dimension,content,uncertainty,source_event_id,created_at)
                    VALUES (?,?,?,1,'test','grammar-test','grammar','Independent use','limited',?,?)""",
                    observationId, student, lesson.text("id"), independent.text("id"), now)
            }
            val observation = json("type" to "observation", "id" to observationId, "revision" to 1)
            service.op(scope, "learning.update", JsonObject(update(observation) + json("goal" to "Independent goal")))
            service.op(scope, "learning.topic.record", topic(manifest, observation, "Known", 0))
            val before = service.learningState(scope)
            service.op(scope, "memory.forget", json("category" to "personal", "key" to "interest", "expectedVersion" to 1))
            scope = service.identity.issue(student, "operator")
            val after = service.learningState(scope)
            assertEquals(before.number("revision"), after.number("revision"))
            assertEquals(before.obj("profile"), after.obj("profile"))
            assertEquals(before.obj("goal"), after.obj("goal"))
            var history = service.topicKnowledge(scope, manifest.version, manifest.topics.first().id, 0, true)["items"]!!.jsonArray
            assertEquals(1, history.size)
            assertEquals("Known", history.single().jsonObject.text("status"))
            assertEquals(independent.text("id"), history.single().jsonObject["sources"]!!.jsonArray.single().jsonObject.text("sourceEventId"))
            // The first forget removed legacy observations; durable ancestry must still support the second forget.
            service.op(scope, "memory.forget", json("category" to "personal", "key" to "job", "expectedVersion" to 1))
            scope = service.identity.issue(student, "operator")
            assertEquals("Unknown", service.learningState(scope).obj("profile").text("targetLevel"))
            assertTrue(service.learningState(scope).obj("goal").obj("content").isEmpty())
            history = service.topicKnowledge(scope, manifest.version, manifest.topics.first().id, 0, true)["items"]!!.jsonArray
            assertEquals(2, history.size)
            assertTrue(history.all { it.jsonObject.text("status") == "Unknown" && it.jsonObject.text("explanation").isEmpty() })
        }
    }

    @Test
    fun forgetUsesHistoricalFactSourcesAndAllGoalSourcesWithoutDroppingHypothesisStatus() = runTest {
        store { service, _ ->
            val student = service.identity.resolve("telegram", "93008")
            var scope = service.identity.issue(student, "operator")
            val oldPersonal = service.source(student)
            val newPersonal = service.source(student)
            val independent = service.source(student)
            val manifest = service.catalog()
            for ((index, source) in listOf(oldPersonal, newPersonal).withIndex()) {
                service.op(scope, "memory.record", json("sourceId" to source.text("id"), "evidence" to "work",
                    "category" to "personal", "key" to "interest", "kind" to "user_report", "value" to "work",
                    "expectedVersion" to index))
            }
            service.op(scope, "learning.update", JsonObject(update(independent) + json(
                "goal" to "Derived private goal", "sources" to JsonArray(listOf(independent, oldPersonal)))))
            service.op(scope, "learning.update", update(oldPersonal, 1, json("homework" to json("period" to "week"))))
            service.op(scope, "learning.update", update(independent, 2, json("targetLevel" to "B2", "homework" to json("minutes" to 30))))
            service.op(scope, "learning.topic.record", JsonObject(topic(manifest, independent, "Presumed Not Known", 0) +
                json("sources" to JsonArray(emptyList()), "explanation" to "Unproven private explanation")))
            service.op(scope, "memory.forget", json("category" to "personal", "key" to "interest", "expectedVersion" to 2))
            scope = service.identity.issue(student, "operator")
            val state = service.learningState(scope)
            assertTrue(state.obj("goal").obj("content").isEmpty())
            assertEquals(kotlinx.serialization.json.JsonNull, state.obj("profile").obj("homework")["minutes"])
            assertEquals(kotlinx.serialization.json.JsonNull, state.obj("profile").obj("homework")["period"])
            assertEquals("B2", state.obj("profile").text("targetLevel"))
            val history = service.topicKnowledge(scope, manifest.version, manifest.topics.first().id, 0, true)["items"]!!.jsonArray
            assertEquals(2, history.size)
            assertTrue(history.all { it.jsonObject.text("status") == "Presumed Not Known" && it.jsonObject.text("explanation").isEmpty() })
            service.database.transaction { c, now ->
                StudentLearningPrivacy().forgetPersonalFact(c, student, "personal", "interest", now)
                assertEquals(2L, c.rows("SELECT count(*) FROM student_topic_knowledge WHERE student_id=?", student) { it.getLong(1) }.single())
                assertTrue(c.documents("SELECT content FROM learning_goal WHERE student_id=?", student).all { it.isEmpty() })
            }
        }
    }

    @Test
    fun ownershipRolesAndConcurrentVersionsAreEnforced() = runTest {
        store { service, _ ->
            val student = service.identity.resolve("telegram", "93003")
            val other = service.identity.resolve("telegram", "93004")
            val scope = service.identity.issue(student, "operator")
            val source = service.source(student)
            val foreign = service.source(other)
            assertEquals("learning_source_not_owned", assertFailsWith<FoundationFailure> {
                service.op(scope, "learning.update", update(foreign))
            }.code)
            val learning = service.identity.issue(student, "learning")
            assertEquals("not_permitted", assertFailsWith<FoundationFailure> { service.op(learning, "learning.update", update(source)) }.code)
            val results = coroutineScope {
                listOf(async { runCatching { service.op(scope, "learning.update", update(source)) } },
                    async { runCatching { service.op(scope, "learning.update", update(source)) } }).map { it.await() }
            }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals("version_conflict", (results.single { it.isFailure }.exceptionOrNull() as FoundationFailure).code)
            assertEquals(0L, service.learningState(service.identity.issue(other, "operator")).number("revision"))
            service.op(scope, "student.delete", json())
            service.database.transaction(readOnly = true) { c, _ ->
                assertEquals(0L, c.rows("SELECT count(*) FROM student_learning_state WHERE student_id=?", student) { it.getLong(1) }.single())
            }
        }
    }
}
