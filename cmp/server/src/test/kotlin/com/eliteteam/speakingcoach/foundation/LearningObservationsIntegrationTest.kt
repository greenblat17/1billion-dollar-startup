package com.eliteteam.speakingcoach.foundation

import java.sql.DriverManager
import java.time.Instant
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

class LearningObservationsIntegrationTest {
    private suspend fun store(resolver: ObservationStageResolver = learningPlanObservationStage,
        block: suspend (FoundationService, String) -> Unit) {
        val url = requireNotNull(System.getenv("TEST_POSTGRES_URL"))
        val schema = "observations_${newId().replace("-", "")}"
        DriverManager.getConnection(url).use { it.createStatement().execute("CREATE SCHEMA $schema") }
        val scoped = url + (if ('?' in url) "&" else "?") + "currentSchema=$schema"
        try { FoundationService(FoundationDatabase(scoped), observationStages = resolver).use { block(it, scoped) } }
        finally { DriverManager.getConnection(url).use { it.createStatement().execute("DROP SCHEMA $schema CASCADE") } }
    }

    private suspend fun setup(s: FoundationService): Triple<String, String, JsonObject> {
        val manifest = catalogFixture()
        LearningCatalog(s.database).import(LearningCatalogModel.document(manifest).toString())
        LearningCatalog(s.database).publish(manifest.version, "synthetic", "test fixture only")
        val student = s.identity.resolve("telegram", System.nanoTime().toString())
        val scope = s.identity.issue(student, "operator")
        val event = s.history.accept(student, newId(), "She works. I have worked here.", Instant.now(), false)
        val e = s.database.transaction(readOnly = true) { c, _ -> c.document("SELECT to_jsonb(e) FROM conversation_event e WHERE id=?", event.text("event_id"))!! }
        return Triple(student, scope, JsonObject(observationFixture(e.text("id"), e.number("sequence")) + json("catalogVersion" to manifest.version)))
    }

    private suspend fun FoundationService.op(scope: String, action: String, args: JsonObject, operation: String = newId()) =
        command(scope, json("version" to 1, "operationId" to operation, "action" to action, "args" to args))
    private suspend fun FoundationService.record(scope: String, d: JsonObject) = op(scope, "learning.observation.record", json("observation" to d)).obj("observation")
    private fun revision(o: JsonObject, d: JsonObject) = json("observationId" to o.text("id"), "expectedRevision" to o.number("revision"), "observation" to d, "reason" to "Corrected interpretation")

    @Test fun repeatConcurrentCreateAndRestartKeepOneIdentity() = runTest {
        store { s, url ->
            val (student, scope, d) = setup(s)
            val before = s.learningState(scope)
            val first = s.record(scope, d)
            val results = coroutineScope { (1..4).map { async { s.record(scope, d) } }.map { it.await() } }
            assertTrue(results.all { it == first })
            assertEquals(1, s.learningObservations(scope)["items"]!!.jsonArray.size)
            assertEquals(before, s.learningState(scope))
            FoundationService(FoundationDatabase(url)).use { restarted ->
                assertEquals(first, restarted.learningObservation(restarted.identity.issue(student, "operator"), first.text("id")).obj("observation"))
            }
            assertEquals("observation_identity_conflict", assertFailsWith<FoundationFailure> {
                s.record(scope, JsonObject(d + json("attemptId" to "invented-new-attempt")))
            }.code)
        }
    }

    @Test fun revisionsReplaceEvidenceAndKeepOriginalSources() = runTest {
        store { s, _ ->
            val (_, scope, d) = setup(s)
            val first = s.record(scope, d)
            val corrected = JsonObject(d + json("outcome" to "self_corrected_error", "initialOutcome" to "error",
                "provenance" to json("component" to "reanalysis", "analyzerVersion" to "v2")))
            val next = s.op(scope, "learning.observation.revise", revision(first, corrected)).obj("observation")
            assertEquals(2L, next.number("revision"))
            assertEquals(1, s.learningObservations(scope)["items"]!!.jsonArray.size)
            val history = s.learningObservation(scope, first.text("id"), true)["items"]!!.jsonArray
            assertEquals(2, history.size)
            assertEquals("independent_correct", history.last().jsonObject.obj("document").text("outcome"))
            assertEquals("false", history.last().jsonObject.text("isCurrent"))
            assertEquals("version_conflict", assertFailsWith<FoundationFailure> { s.op(scope, "learning.observation.revise", revision(first, corrected)) }.code)
            val invalid = s.op(scope, "learning.observation.invalidate", json("observationId" to next.text("id"), "expectedRevision" to 2,
                "component" to "test", "reason" to "defective content")).obj("observation")
            assertEquals("false", invalid.text("evidenceUsable"))
            assertEquals(3, s.learningObservation(scope, first.text("id"), true)["items"]!!.jsonArray.size)
        }
    }

    @Test fun catalogOwnershipSourceRevisionAndRoleAreEnforced() = runTest {
        store { s, _ ->
            val (student, scope, d) = setup(s)
            for (patch in listOf(json("topicId" to "made-up"), json("problemId" to "made-up"), json("skillId" to "not-a-skill"))) {
                assertEquals("invalid_observation_catalog", assertFailsWith<FoundationFailure> { s.record(scope, JsonObject(d + patch)) }.code)
            }
            val foreign = s.identity.issue(s.identity.resolve("telegram", System.nanoTime().toString()), "operator")
            assertEquals("observation_source_not_owned", assertFailsWith<FoundationFailure> { s.record(foreign, d) }.code)
            val o = s.record(scope, d)
            assertEquals("not_owned", assertFailsWith<FoundationFailure> { s.learningObservation(foreign, o.text("id")) }.code)
            assertEquals("not_permitted", assertFailsWith<FoundationFailure> { s.record(s.identity.issue(student, "voice"), d) }.code)
            val source = (d["sources"] as JsonArray).single().jsonObject
            val stale = JsonObject(d + json("sources" to JsonArray(listOf(JsonObject(source + json("revision" to 999))))))
            assertEquals("stale_observation_source", assertFailsWith<FoundationFailure> { s.record(scope, stale) }.code)
        }
    }

    @Test fun uncertainAndRepeatedEvidenceNeverBecomeScoringEligible() = runTest {
        store { s, _ ->
            val (_, scope, d) = setup(s)
            val first = s.record(scope, d)
            val q = json("status" to "uncertain", "signals" to JsonArray(listOf(json("reason" to "uncertain_asr", "component" to "fixture", "sourceEventId" to d.text("anchorEventId")))))
            val uncertain = s.record(scope, JsonObject(d + json("evidenceSlot" to "second", "attemptId" to "second", "quality" to q)))
            assertEquals("false", uncertain.text("evidenceUsable"))
            val repeat = s.record(scope, JsonObject(d + json("evidenceSlot" to "repeat", "attemptId" to "repeat", "repeatOf" to first.text("id"))))
            assertEquals("false", repeat.text("evidenceUsable"))
            assertEquals("true", first.text("evidenceUsable")) // null ASR confidence is not invalid.
        }
    }

    @Test fun oneProblemCannotCountTwiceAndUnknownCanBeClassifiedLater() = runTest {
        store { s, _ ->
            val (_, scope, d) = setup(s)
            val topic = catalogFixture().topics.first { it.skillId == "grammar" }.id
            val classified = JsonObject(d + json("topicId" to topic, "problemId" to "grammar_application", "route" to "problem_detection"))
            val first = s.record(scope, d)
            val next = s.op(scope, "learning.observation.revise", revision(first, classified)).obj("observation")
            assertEquals("true", next.text("scoringEligible"))
            assertEquals("duplicate_problem_evidence", assertFailsWith<FoundationFailure> {
                s.record(scope, JsonObject(classified + json("evidenceSlot" to "second-tag")))
            }.code)
            val newApplication = JsonObject(classified + json("evidenceSlot" to "new-application", "attemptId" to "new-application"))
            s.record(scope, newApplication)
            assertEquals(2, s.learningObservations(scope)["items"]!!.jsonArray.size)
        }
    }

    @Test fun learningSourcesAndExpiryDoNotChangeKnowledgeByThemselves() = runTest {
        store { s, _ ->
            val (student, scope, d) = setup(s)
            val first = s.record(scope, d)
            val source = JsonArray(listOf(json("type" to "learning_observation", "id" to first.text("id"), "revision" to 1)))
            val profile = s.op(scope, "learning.update", json("expectedRevision" to 0, "patch" to json("targetLevel" to "B2"), "component" to "test", "sources" to source))
            s.database.transaction { c, now -> c.update("UPDATE conversation_event SET expires_at=? WHERE student_id=?", now.minusSeconds(1), student) }
            assertEquals("false", s.learningObservation(scope, first.text("id")).obj("observation").text("evidenceUsable"))
            assertEquals(profile.obj("profile"), s.learningState(scope).obj("profile"))
            assertEquals("source_expired", assertFailsWith<FoundationFailure> {
                s.op(scope, "learning.update", json("expectedRevision" to 1, "patch" to json("targetLevel" to "C1"), "component" to "test", "sources" to source))
            }.code)
        }
    }

    @Test fun fullLearningForgetAndDeleteRemoveJournalAndHistory() = runTest {
        store { s, _ ->
            val (student, scope, d) = setup(s)
            s.record(scope, d)
            s.op(scope, "learning.forget", json("expectedRevision" to 0))
            val token = s.identity.issue(student, "operator")
            assertEquals(0, s.learningObservations(token)["items"]!!.jsonArray.size)
            val input = s.history.accept(student, newId(), "A new independent answer", Instant.now(), false)
            val event = s.database.transaction(readOnly = true) { c, _ -> c.document("SELECT to_jsonb(e) FROM conversation_event e WHERE id=?", input.text("event_id"))!! }
            s.record(token, JsonObject(observationFixture(event.text("id"), event.number("sequence")) + json("catalogVersion" to d.text("catalogVersion"))))
            s.op(token, "student.delete", json())
            s.database.transaction(readOnly = true) { c, _ ->
                assertEquals(0L, c.rows("SELECT count(*) FROM learning_observation_revision WHERE student_id=?", student) { it.getLong(1) }.single())
            }
        }
    }

    @Test fun deletionLedgerReplayCannotRestoreEvidence() = runTest {
        store { s, _ ->
            val (student, scope, d) = setup(s)
            s.record(scope, d)
            s.database.transaction { c, now ->
                c.update("""INSERT INTO foundation_deletion(id,student_id,kind,category,fact_key,deleted_at,resulting_epoch)
                    VALUES (?,?,'forget','learning','state',?,2)""", newId(), student, now)
            }
            assertEquals(1L, s.op(scope, "deletion.replay", json()).number("replayed"))
            val fresh = s.identity.issue(student, "operator")
            assertEquals(0, s.learningObservations(fresh)["items"]!!.jsonArray.size)
            assertEquals(0L, s.op(fresh, "deletion.replay", json()).number("replayed"))
        }
    }

    @Test fun newStartDeletesEvidenceAndReplayDoesNotResetAgain() = runTest {
        store { s, _ ->
            val (student, scope, d) = setup(s)
            s.record(scope, d)
            val subject = s.database.transaction(readOnly = true) { c, _ -> c.rows("SELECT subject FROM student_identity WHERE student_id=?", student) { it.getString(1) }.single() }
            val at = Instant.now()
            val chat = subject.toLong()
            val request = com.eliteteam.speakingcoach.ai.TutorRequest("tg-$chat", "message:2", "/start", at.epochSecond.toDouble())
            assertTrue(FoundationChat(s).prepareIntroduction(request, chat, 2))
            val fresh = s.identity.resolve("telegram", subject)
            assertTrue(fresh != student)
            assertTrue(FoundationChat(s).prepareIntroduction(request, chat, 2))
            assertEquals(fresh, s.identity.resolve("telegram", subject))
            s.database.transaction(readOnly = true) { c, _ ->
                assertEquals(0L, c.rows("SELECT count(*) FROM learning_observation WHERE student_id=?", student) { it.getLong(1) }.single())
                assertEquals(0L, c.rows("SELECT count(*) FROM learning_observation_revision WHERE student_id=?", student) { it.getLong(1) }.single())
            }
        }
    }

    @Test fun realLearningPlanStageResolvesForObservation() = runTest {
        store { s, _ ->
            val (_, scope, d) = setup(s)
            val src = (d["sources"] as JsonArray).single().jsonObject
            val sources = JsonArray(listOf(json("type" to "event", "id" to src.text("eventId"), "revision" to src.number("revision"))))
            s.op(scope, "learning.update", json("expectedRevision" to 0, "patch" to json(), "goal" to "B2 for work",
                "component" to "test", "sources" to sources))
            val topic = catalogFixture().topics.first { it.skillId == "grammar" }.id
            val task = s.op(scope, "learning.task.create", json("goalRevision" to 1, "catalogVersion" to d.text("catalogVersion"),
                "skillId" to "grammar", "topicId" to topic, "problemId" to "grammar_application", "component" to "test")).obj("task")
            val plan = s.op(scope, "learning.plan.create", json("taskId" to task.text("id"),
                "expectedTaskRevision" to task.number("revision"), "definition" to planFixture(), "component" to "test")).obj("plan")
            val ref = json("planId" to plan.text("id"), "planRevision" to plan.number("revision"),
                "stageId" to "converse", "stageAttemptId" to null)
            val linked = JsonObject(d + json("topicId" to topic, "problemId" to "grammar_application",
                "taskId" to task.text("id"), "stageRef" to ref, "route" to "stage_progress"))
            val observed = s.record(scope, linked)
            assertEquals("verified", observed.text("referenceStatus"))
            assertEquals("numeric", observed.text("stageKind"))
            assertEquals("true", observed.text("scoringEligible"))
            val wrong = JsonObject(linked + json("stageRef" to JsonObject(ref + json("stageId" to "missing"))))
            assertEquals("stage_not_in_revision", assertFailsWith<FoundationFailure> { s.record(scope, wrong) }.code)
        }
    }

    @Test fun historicalStagePortAndTaskOwnershipDoNotRequirePlanMigration() = runTest {
        store(unresolvedObservationStage) { s, url ->
            val (student, scope, d) = setup(s)
            val src = (d["sources"] as JsonArray).single().jsonObject
            val sources = JsonArray(listOf(json("type" to "event", "id" to src.text("eventId"), "revision" to src.number("revision"))))
            s.op(scope, "learning.update", json("expectedRevision" to 0, "patch" to json(), "goal" to "B2 for work", "component" to "test", "sources" to sources))
            val topic = catalogFixture().topics.first { it.skillId == "grammar" }.id
            val task = s.op(scope, "learning.task.create", json("goalRevision" to 1, "catalogVersion" to d.text("catalogVersion"),
                "skillId" to "grammar", "topicId" to topic, "problemId" to "grammar_application", "component" to "test", "sources" to sources)).obj("task")
            val classified = JsonObject(d + json("topicId" to topic, "problemId" to "grammar_application", "route" to "problem_detection"))
            assertEquals("observation_active_task", assertFailsWith<FoundationFailure> { s.record(scope, classified) }.code)
            val ref = json("planId" to "historical-plan", "planRevision" to 1, "stageId" to "conversation", "stageAttemptId" to null)
            val linked = JsonObject(classified + json("taskId" to task.text("id"), "stageRef" to ref, "route" to "stage_progress"))
            val first = s.record(scope, linked)
            assertEquals("unresolved", first.text("referenceStatus"))
            assertEquals("false", first.text("scoringEligible"))
            val resolver = ObservationStageResolver { _, owner, taskId, reference ->
                assertEquals(student, owner); assertEquals(task.text("id"), taskId); assertEquals(ref, reference)
                json("id" to "conversation", "method" to "Conversation", "kind" to "numeric", "required" to true, "weight" to 1)
            }
            FoundationService(FoundationDatabase(url), observationStages = resolver).use { integrated ->
                val token = integrated.identity.issue(student, "operator")
                val resolved = integrated.learningObservation(token, first.text("id")).obj("observation")
                assertEquals("verified", resolved.text("referenceStatus"))
                assertEquals("true", resolved.text("scoringEligible"))
                assertEquals(task, integrated.learningTask(token, task.text("id")).obj("task"))
            }
            val binary = ObservationStageResolver { _, _, _, _ -> json("method" to "Conversation", "kind" to "binary") }
            FoundationService(FoundationDatabase(url), observationStages = binary).use { integrated ->
                val resolved = integrated.learningObservation(integrated.identity.issue(student, "operator"), first.text("id")).obj("observation")
                assertEquals("verified", resolved.text("referenceStatus"))
                assertEquals("false", resolved.text("scoringEligible"))
            }
        }
    }

    @Test fun personalForgetFollowsAllSourceRevisionsAndPreservesIndependentEvidence() = runTest {
        store { s, _ ->
            val (student, scope, d) = setup(s)
            val another = s.history.accept(student, newId(), "I enjoy cooking", Instant.now(), false)
            val e = s.database.transaction(readOnly = true) { c, _ -> c.document("SELECT to_jsonb(e) FROM conversation_event e WHERE id=?", another.text("event_id"))!! }
            val contextSource = json("eventId" to e.text("id"), "revision" to e.number("sequence"), "role" to "user", "purpose" to "context")
            val withContext = JsonObject(d + json("sources" to JsonArray((d["sources"] as JsonArray) + contextSource)))
            val observation = s.record(scope, withContext)
            s.op(scope, "memory.record", json("sourceId" to e.text("id"), "evidence" to "cooking", "category" to "personal",
                "key" to "interest", "kind" to "user_report", "value" to "cooking", "expectedVersion" to 0))
            val refs = JsonArray(listOf(json("type" to "learning_observation", "id" to observation.text("id"), "revision" to 1)))
            s.op(scope, "learning.update", json("expectedRevision" to 0, "patch" to json("targetLevel" to "B2"), "component" to "test", "sources" to refs))
            s.op(scope, "learning.observation.revise", revision(observation, d)) // Old context still belongs to provenance history.
            val independent = s.record(scope, JsonObject(d + json("evidenceSlot" to "independent", "attemptId" to "independent")))
            s.op(scope, "memory.forget", json("category" to "personal", "key" to "interest", "expectedVersion" to 1))
            val token = s.identity.issue(student, "operator")
            assertEquals("not_owned", assertFailsWith<FoundationFailure> { s.learningObservation(token, observation.text("id")) }.code)
            val survivor = s.learningObservation(token, independent.text("id")).obj("observation")
            assertEquals("", survivor.obj("document").text("rationale"))
            assertEquals("false", survivor.text("evidenceUsable")) // Existing forget clears raw history.
            assertEquals("Unknown", s.learningState(token).obj("profile").text("targetLevel"))
        }
    }

    @Test fun sourceCorrectionInvalidatesOldInterpretationUntilExplicitRevision() = runTest {
        store { s, _ ->
            val (_, scope, d) = setup(s)
            val first = s.record(scope, d)
            val oldSource = (d["sources"] as JsonArray).single().jsonObject
            s.database.transaction { c, _ -> c.update("UPDATE conversation_event SET sequence=sequence+1 WHERE id=?", d.text("anchorEventId")) }
            assertEquals("false", s.learningObservation(scope, first.text("id")).obj("observation").text("evidenceUsable"))
            val corrected = JsonObject(d + json("sources" to JsonArray(listOf(JsonObject(oldSource + json("revision" to oldSource.number("revision") + 1))))))
            val next = s.op(scope, "learning.observation.revise", revision(first, corrected)).obj("observation")
            assertEquals("true", next.text("evidenceUsable"))
            assertEquals(first.text("id"), next.text("id"))
        }
    }
}
