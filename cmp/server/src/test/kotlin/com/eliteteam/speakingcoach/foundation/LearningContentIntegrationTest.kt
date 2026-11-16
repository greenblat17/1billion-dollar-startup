package com.eliteteam.speakingcoach.foundation

import java.sql.DriverManager
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LearningContentIntegrationTest {
    private suspend fun store(block: suspend (FoundationService) -> Unit) {
        val url = requireNotNull(System.getenv("TEST_POSTGRES_URL"))
        val schema = "content_${newId().replace("-", "")}"
        DriverManager.getConnection(url).use { it.createStatement().execute("CREATE SCHEMA $schema") }
        val scoped = url + (if ('?' in url) "&" else "?") + "currentSchema=$schema"
        try { FoundationService(FoundationDatabase(scoped)).use { block(it) } }
        finally { DriverManager.getConnection(url).use { it.createStatement().execute("DROP SCHEMA $schema CASCADE") } }
    }

    private suspend fun op(s: FoundationService, scope: String, action: String, args: JsonObject, id: String = newId()) =
        s.command(scope, json("version" to 1, "operationId" to id, "action" to action, "args" to args))

    private suspend fun setup(s: FoundationService): Triple<String, JsonObject, String> {
        val manifest = catalogFixture()
        LearningCatalog(s.database).import(LearningCatalogModel.document(manifest).toString())
        LearningCatalog(s.database).publish(manifest.version, "synthetic-test", "fixture only")
        val student = s.identity.resolve("telegram", System.nanoTime().toString())
        val scope = s.identity.issue(student, "operator")
        val event = s.history.accept(student, newId(), "I want B2 for work", Instant.now(), false)
        val source = s.database.transaction(readOnly = true) { c, _ ->
            val e = c.document("SELECT to_jsonb(e) FROM conversation_event e WHERE id=?", event.text("event_id"))!!
            JsonArray(listOf(json("type" to "event", "id" to e.text("id"), "revision" to e.number("sequence"))))
        }
        op(s, scope, "learning.update", json("expectedRevision" to 0, "patch" to json(), "goal" to "B2 for work",
            "component" to "test", "sources" to source))
        val topic = manifest.topics.first { it.skillId == "grammar" }
        val task = op(s, scope, "learning.task.create", json("goalRevision" to 1, "catalogVersion" to manifest.version,
            "skillId" to "grammar", "topicId" to topic.id, "problemId" to "grammar_application", "component" to "test")).obj("task")
        val plan = op(s, scope, "learning.plan.create", json("taskId" to task.text("id"),
            "expectedTaskRevision" to task.number("revision"), "definition" to planFixture(), "component" to "test")).obj("plan")
        return Triple(scope, s.learningTask(scope, task.text("id")).obj("task"), plan.text("id"))
    }

    @Test
    fun versionedMaterialKeepsTheIssuedWordsAndNeverChangesTaskProgress() = runTest {
        store { s ->
            val (scope, task, planId) = setup(s)
            val d = contentFixture(task, planId)
            val createArgs = json("document" to d, "component" to "fixture")
            val operation = newId()
            val first = op(s, scope, "learning.content.create", createArgs, operation).obj("content")
            assertEquals(first, op(s, scope, "learning.content.create", createArgs, operation).obj("content"))
            assertEquals("prepared", first.text("status"))
            val lesson = op(s, scope, "lesson.create", json("kind" to "regular", "launchMode" to "placeholder",
                "startsAt" to Instant.now().plusSeconds(3600).toString(), "durationMinutes" to 30, "timezone" to "UTC"))
            val issued = op(s, scope, "learning.content.issue", json("contentId" to first.text("id"),
                "expectedRevision" to 1, "lessonId" to lesson.text("id"))).obj("content")
            assertEquals("issued", issued.text("status"))
            val simpler = JsonObject(d + json("spokenInstruction" to "Say it in the past tense, using went."))
            val revised = op(s, scope, "learning.content.revise", json("contentId" to first.text("id"),
                "expectedRevision" to 1, "document" to simpler, "component" to "fixture", "reason" to "simplify")).obj("content")
            assertEquals(2L, revised.number("revision"))
            assertEquals("prepared", revised.text("status"))
            assertEquals(d, s.learningContent(scope, first.text("id"), revision = 1).obj("content").obj("document"))
            val defect = op(s, scope, "learning.content.mark-defective", json("contentId" to first.text("id"),
                "revision" to 1, "reason" to "ambiguous_condition", "component" to "fixture")).obj("content")
            assertEquals("defective", defect.text("status"))
            assertEquals("fixture", defect.text("defectComponent"))
            assertEquals(2, s.learningContent(scope, first.text("id"), history = true)["items"]!!.let { (it as JsonArray).size })
            assertEquals(task, s.learningTask(scope, task.text("id")).obj("task"))
            val plan = s.learningPlan(scope, planId).obj("plan")
            val changedDefinition = JsonObject(planFixture() + json("stages" to JsonArray(
                LearningPlanModel.stages(planFixture()).map { stage -> when (stage.text("id")) {
                    "exercise" -> JsonObject(stage + json("weight" to 0.4))
                    "converse" -> JsonObject(stage + json("weight" to 0.6))
                    else -> stage
                } })))
            op(s, scope, "learning.plan.revise", json("taskId" to task.text("id"), "planId" to planId,
                "expectedTaskRevision" to task.number("revision"), "expectedPlanRevision" to 1,
                "expectedStateRevision" to plan.obj("currentState").number("stateRevision"),
                "definition" to changedDefinition, "component" to "fixture"))
            assertEquals(d, s.learningContent(scope, first.text("id"), revision = 1).obj("content").obj("document"))
            assertEquals("stale_content_stage", assertFailsWith<FoundationFailure> {
                op(s, scope, "learning.content.create", createArgs)
            }.code)
            val currentRef = JsonObject(d.obj("stageRef") + json("planRevision" to 2))
            val current = JsonObject(d + json("stageRef" to currentRef))
            assertEquals(2L, op(s, scope, "learning.content.create", json("document" to current,
                "component" to "fixture")).obj("content").obj("document").obj("stageRef").number("planRevision"))
        }
    }

    @Test
    fun mismatchedStagePermissionsAndUnvalidatedIssueAreRejected() = runTest {
        store { s ->
            val (scope, task, planId) = setup(s)
            val d = contentFixture(task, planId)
            val wrong = JsonObject(d + json("method" to "Conversation", "actionKind" to "topic_driven",
                "payload" to json("openingPrompt" to "What did you do?",
                    "targetOpportunities" to JsonArray(listOf(JsonPrimitive("Use a past form."))))))
            assertEquals("content_method_mismatch", assertFailsWith<FoundationFailure> {
                op(s, scope, "learning.content.create", json("document" to wrong, "component" to "fixture"))
            }.code)
            val pending = JsonObject(d + json("validation" to json("status" to "pending", "component" to "fixture",
                "reasons" to JsonArray(emptyList()))))
            val content = op(s, scope, "learning.content.create", json("document" to pending, "component" to "fixture")).obj("content")
            val otherStudent = s.identity.resolve("telegram", System.nanoTime().toString())
            val other = s.identity.issue(otherStudent, "operator")
            assertEquals("not_owned", assertFailsWith<FoundationFailure> { s.learningContent(other, content.text("id")) }.code)
            val voice = s.identity.issue(task.text("student_id"), "voice")
            assertEquals("not_permitted", assertFailsWith<FoundationFailure> {
                op(s, voice, "learning.content.create", json("document" to d, "component" to "fixture"))
            }.code)
            val lesson = op(s, scope, "lesson.create", json("kind" to "regular", "launchMode" to "placeholder",
                "startsAt" to Instant.now().plusSeconds(3600).toString(), "durationMinutes" to 30, "timezone" to "UTC"))
            assertEquals("content_not_validated", assertFailsWith<FoundationFailure> {
                op(s, scope, "learning.content.issue", json("contentId" to content.text("id"),
                    "expectedRevision" to 1, "lessonId" to lesson.text("id")))
            }.code)
            assertEquals("version_conflict", assertFailsWith<FoundationFailure> {
                op(s, scope, "learning.content.revise", json("contentId" to content.text("id"),
                    "expectedRevision" to 2, "document" to d, "component" to "fixture", "reason" to "validate"))
            }.code)
        }
    }

    @Test
    fun personalForgetClearsMaterialAndLearningForgetCascadesWithoutRemovingPlanFirst() = runTest {
        store { s ->
            val (scope, task, planId) = setup(s)
            val first = op(s, scope, "learning.content.create", json("document" to contentFixture(task, planId),
                "component" to "fixture")).obj("content")
            val student = task.text("student_id")
            val event = s.history.accept(student, newId(), "I like cycling", Instant.now(), false)
            op(s, scope, "memory.record", json("sourceId" to event.text("event_id"), "evidence" to "cycling",
                "category" to "personal", "key" to "hobby", "kind" to "user_report", "value" to "cycling", "expectedVersion" to 0))
            op(s, scope, "memory.forget", json("category" to "personal", "key" to "hobby", "expectedVersion" to 1))
            val fresh = s.identity.issue(student, "operator")
            assertEquals("not_owned", assertFailsWith<FoundationFailure> { s.learningContent(fresh, first.text("id")) }.code)
            assertEquals(1L, s.learningPlan(fresh, planId).obj("plan").number("revision"))
            val second = op(s, fresh, "learning.content.create", json("document" to contentFixture(task, planId),
                "component" to "fixture")).obj("content")
            op(s, fresh, "learning.forget", json("expectedRevision" to s.learningState(fresh).number("revision")))
            val after = s.identity.issue(student, "operator")
            assertEquals("not_owned", assertFailsWith<FoundationFailure> { s.learningContent(after, second.text("id")) }.code)
            s.database.transaction(readOnly = true) { c, _ ->
                assertEquals(0L, c.rows("SELECT count(*) FROM learning_content_revision") { it.getLong(1) }.single())
            }
            assertTrue(s.learningTasks(after, null, null, null, 0)["items"]!!.let { (it as JsonArray).isEmpty() })
        }
    }
}
