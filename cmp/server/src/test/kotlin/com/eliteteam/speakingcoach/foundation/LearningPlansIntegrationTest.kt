package com.eliteteam.speakingcoach.foundation

import java.sql.DriverManager
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LearningPlansIntegrationTest {
    private suspend fun store(block: suspend (FoundationService, String) -> Unit) {
        val url = requireNotNull(System.getenv("TEST_POSTGRES_URL"))
        val schema = "plans_${newId().replace("-", "")}"
        DriverManager.getConnection(url).use { it.createStatement().execute("CREATE SCHEMA $schema") }
        val scoped = url + (if ('?' in url) "&" else "?") + "currentSchema=$schema"
        try { FoundationService(FoundationDatabase(scoped)).use { block(it, scoped) } }
        finally { DriverManager.getConnection(url).use { it.createStatement().execute("DROP SCHEMA $schema CASCADE") } }
    }

    private suspend fun op(s: FoundationService, scope: String, action: String, args: JsonObject, id: String = newId()) =
        s.command(scope, json("version" to 1, "operationId" to id, "action" to action, "args" to args))

    private suspend fun setup(s: FoundationService): Pair<String, JsonObject> {
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
        return scope to task
    }

    private fun createArgs(task: JsonObject) = json("taskId" to task.text("id"), "expectedTaskRevision" to task.number("revision"),
        "definition" to planFixture(), "component" to "test")

    private fun cursorArgs(plan: JsonObject, stage: String?, reason: String = "plan_transition") = json(
        "planId" to plan.text("id"), "expectedPlanRevision" to plan.number("revision"),
        "expectedStateRevision" to plan.obj("currentState").number("stateRevision"),
        "currentStageId" to stage, "component" to "test", "reason" to reason)

    private fun reviseArgs(task: JsonObject, plan: JsonObject, definition: JsonObject) = json(
        "taskId" to task.text("id"), "expectedTaskRevision" to task.number("revision"), "planId" to plan.text("id"),
        "expectedPlanRevision" to plan.number("revision"), "expectedStateRevision" to plan.obj("currentState").number("stateRevision"),
        "definition" to definition, "component" to "test")

    @Test
    fun planAttachesAtomicallyAndPersistsWithoutChangingLearningIdentityOrProgress() = runTest {
        store { s, url ->
            val (scope, before) = setup(s)
            assertEquals(JsonNull, before.obj("details")["plan"])
            val operation = newId()
            val response = op(s, scope, "learning.plan.create", createArgs(before), operation)
            val plan = response.obj("plan")
            assertEquals(response, op(s, scope, "learning.plan.create", createArgs(before), operation))
            val task = s.learningTask(scope, before.text("id")).obj("task")
            for (key in listOf("skill_id", "topic_id", "problem_id", "goal_revision", "progress", "lifecycle")) assertEquals(before[key], task[key])
            assertEquals(json("id" to plan.text("id"), "revision" to 1), task.obj("details")["plan"])
            assertEquals(2L, task.number("revision"))
            assertEquals(JsonNull, plan.obj("currentState")["currentStageId"])
            FoundationService(FoundationDatabase(url)).use { restarted ->
                val fresh = restarted.identity.issue(before.text("student_id"), "operator")
                assertEquals(response, restarted.learningPlan(fresh, plan.text("id")))
                assertEquals(task, restarted.learningTask(fresh, task.text("id")).obj("task"))
            }
            assertEquals("plan_already_exists", assertFailsWith<FoundationFailure> {
                op(s, scope, "learning.plan.create", createArgs(task))
            }.code)
        }
    }

    @Test
    fun historicalReferencesSurviveReweightReorderAndStageRemoval() = runTest {
        store { s, _ ->
            val (scope, original) = setup(s)
            val plan = op(s, scope, "learning.plan.create", createArgs(original)).obj("plan")
            var task = s.learningTask(scope, original.text("id")).obj("task")
            val old = s.learningStage(scope, task.text("id"), plan.text("id"), 1, "exercise").obj("stage")
            val next = JsonObject(planFixture() + json("stages" to JsonArray(LearningPlanModel.stages(planFixture()).map {
                when (it.text("id")) {
                    "exercise" -> JsonObject(it + json("weight" to 0.4))
                    "converse" -> JsonObject(it + json("weight" to 0.6))
                    else -> it
                }
            }), "order" to JsonArray(listOf("converse", "exercise", "explain").map(::JsonPrimitive))))
            val revised = op(s, scope, "learning.plan.revise", reviseArgs(task, plan, next)).obj("plan")
            assertEquals(old, s.learningStage(scope, task.text("id"), plan.text("id"), 1, "exercise").obj("stage"))
            assertEquals("0.4", s.learningStage(scope, task.text("id"), plan.text("id"), 2, "exercise").obj("stage").text("weight"))
            task = s.learningTask(scope, task.text("id")).obj("task")
            val conversationOnly = json("schemaVersion" to 1,
                "stages" to JsonArray(listOf(JsonObject(LearningPlanModel.stage(next, "converse") + json("weight" to 1)))),
                "order" to JsonArray(listOf(JsonPrimitive("converse"))), "transitions" to JsonArray(emptyList()))
            op(s, scope, "learning.plan.revise", reviseArgs(task, revised, conversationOnly))
            assertEquals(old, s.learningStage(scope, task.text("id"), plan.text("id"), 1, "exercise").obj("stage"))
            assertEquals("stage_not_in_revision", assertFailsWith<FoundationFailure> {
                s.learningStage(scope, task.text("id"), plan.text("id"), 3, "exercise")
            }.code)
            val current = s.learningPlan(scope, plan.text("id")).obj("plan")
            task = s.learningTask(scope, task.text("id")).obj("task")
            val reused = JsonObject(planFixture() + json("stages" to JsonArray(LearningPlanModel.stages(planFixture()).map {
                if (it.text("id") == "exercise") JsonObject(it + json("method" to "Exposure")) else it
            })))
            assertEquals("stage_identity_changed", assertFailsWith<FoundationFailure> {
                op(s, scope, "learning.plan.revise", reviseArgs(task, current, reused))
            }.code)
            assertEquals(3L, s.learningPlan(scope, plan.text("id")).obj("plan").number("revision"))
        }
    }

    @Test
    fun cursorSupportsRepeatReturnAndUserChoiceWithoutScoreOrDefinitionMutation() = runTest {
        store { s, _ ->
            val (scope, original) = setup(s)
            var plan = op(s, scope, "learning.plan.create", createArgs(original)).obj("plan")
            val task = s.learningTask(scope, original.text("id")).obj("task")
            for (stage in listOf("explain", "exercise", "converse", "exercise", "exercise", "explain")) {
                plan = op(s, scope, "learning.plan.select-stage", cursorArgs(plan, stage)).obj("plan")
                assertEquals(1L, plan.number("revision"))
                assertEquals(stage, plan.obj("currentState").text("currentStageId"))
            }
            assertEquals("stage_transition_not_allowed", assertFailsWith<FoundationFailure> {
                op(s, scope, "learning.plan.select-stage", cursorArgs(plan, "converse"))
            }.code)
            plan = op(s, scope, "learning.plan.select-stage", cursorArgs(plan, "converse", "student_request")).obj("plan")
            plan = op(s, scope, "learning.plan.select-stage", cursorArgs(plan, null, "student_request")).obj("plan")
            assertEquals(JsonNull, plan.obj("currentState")["currentStageId"])
            assertEquals(task, s.learningTask(scope, task.text("id")).obj("task"))
            assertEquals(8, s.learningPlan(scope, plan.text("id"), history = true)["items"]!!.jsonArray.size)
        }
    }

    @Test
    fun invalidWritesAndConcurrentRevisionsNeverPartiallyAttach() = runTest {
        store { s, _ ->
            val (scope, original) = setup(s)
            val bad = JsonObject(planFixture() + json("order" to JsonArray(emptyList())))
            assertFailsWith<FoundationFailure> { op(s, scope, "learning.plan.create", JsonObject(createArgs(original) + json("definition" to bad))) }
            assertEquals(original, s.learningTask(scope, original.text("id")).obj("task"))
            val plan = op(s, scope, "learning.plan.create", createArgs(original)).obj("plan")
            val task = s.learningTask(scope, original.text("id")).obj("task")
            val args = reviseArgs(task, plan, planFixture())
            val results = coroutineScope { (1..2).map { async { runCatching { op(s, scope, "learning.plan.revise", args) } } }.map { it.await() } }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals("version_conflict", (results.single { it.isFailure }.exceptionOrNull() as FoundationFailure).code)
            assertEquals(2, s.learningPlan(scope, plan.text("id"), history = true)["items"]!!.jsonArray.size)
            assertEquals(3L, s.learningTask(scope, original.text("id")).obj("task").number("revision"))
            val current = s.learningPlan(scope, plan.text("id")).obj("plan")
            val selected = op(s, scope, "learning.plan.select-stage", cursorArgs(current, "exercise")).obj("plan")
            val freshTask = s.learningTask(scope, task.text("id")).obj("task")
            val onlyBinary = json("schemaVersion" to 1, "stages" to JsonArray(listOf(LearningPlanModel.stages(planFixture()).first())),
                "order" to JsonArray(listOf(JsonPrimitive("explain"))), "transitions" to JsonArray(emptyList()))
            assertEquals("stage_not_in_revision", assertFailsWith<FoundationFailure> {
                op(s, scope, "learning.plan.revise", reviseArgs(freshTask, selected, onlyBinary))
            }.code)
            assertEquals(freshTask, s.learningTask(scope, task.text("id")).obj("task"))
            op(s, scope, "learning.plan.revise", JsonObject(reviseArgs(freshTask, selected, onlyBinary) + json("currentStageId" to null)))
        }
    }

    @Test
    fun ownershipAndTaskPatchCannotBypassPlanValidation() = runTest {
        store { s, _ ->
            val (scope, original) = setup(s)
            val plan = op(s, scope, "learning.plan.create", createArgs(original)).obj("plan")
            val otherStudent = s.identity.resolve("telegram", System.nanoTime().toString())
            val other = s.identity.issue(otherStudent, "operator")
            assertEquals("not_owned", assertFailsWith<FoundationFailure> { s.learningPlan(other, plan.text("id")) }.code)
            assertEquals("not_owned", assertFailsWith<FoundationFailure> {
                s.learningStage(other, original.text("id"), plan.text("id"), 1, "exercise")
            }.code)
            assertEquals("not_owned", assertFailsWith<FoundationFailure> {
                op(s, other, "learning.plan.select-stage", cursorArgs(plan, "exercise"))
            }.code)
            val voice = s.identity.issue(original.text("student_id"), "voice")
            assertEquals("not_permitted", assertFailsWith<FoundationFailure> {
                op(s, voice, "learning.plan.select-stage", cursorArgs(plan, "exercise"))
            }.code)
            val second = op(s, scope, "learning.task.create", json("goalRevision" to 1, "catalogVersion" to original.text("catalog_version"),
                "skillId" to "grammar", "topicId" to original.text("topic_id"), "problemId" to "grammar_unknown", "component" to "test")).obj("task")
            assertEquals("plan_task_mismatch", assertFailsWith<FoundationFailure> {
                s.learningStage(scope, second.text("id"), plan.text("id"), 1, "exercise")
            }.code)
            for (ref in listOf(json("id" to plan.text("id"), "revision" to 1), json("id" to "missing", "revision" to 1))) {
                assertFailsWith<FoundationFailure> { op(s, scope, "learning.task.update", json("taskId" to second.text("id"),
                    "expectedRevision" to 1, "component" to "test", "patch" to json("plan" to ref))) }
            }
            val task = s.learningTask(scope, original.text("id")).obj("task")
            assertEquals("invalid_plan_revision", assertFailsWith<FoundationFailure> {
                op(s, scope, "learning.task.update", json("taskId" to task.text("id"), "expectedRevision" to task.number("revision"),
                    "component" to "test", "patch" to json("plan" to json("id" to plan.text("id")))))
            }.code)
        }
    }

    @Test
    fun retentionAndPersonalForgetPreserveStructureWhileLearningForgetCascades() = runTest {
        store { s, _ ->
            val (scope, task) = setup(s)
            val plan = op(s, scope, "learning.plan.create", createArgs(task))
            val student = task.text("student_id")
            val event = s.history.accept(student, newId(), "I like cycling", Instant.now(), false)
            op(s, scope, "memory.record", json("sourceId" to event.text("event_id"), "evidence" to "cycling", "category" to "personal",
                "key" to "hobby", "kind" to "user_report", "value" to "cycling", "expectedVersion" to 0))
            op(s, scope, "memory.forget", json("category" to "personal", "key" to "hobby", "expectedVersion" to 1))
            var fresh = s.identity.issue(student, "operator")
            assertEquals(plan, s.learningPlan(fresh, plan.obj("plan").text("id")))
            s.database.transaction { c, now ->
                c.update("UPDATE conversation_event SET expires_at=? WHERE student_id=?", now.minusSeconds(1), student)
                FoundationMaintenance().prune(c, authenticateScope(c, fresh, now), now)
            }
            assertEquals(plan, s.learningPlan(fresh, plan.obj("plan").text("id")))
            op(s, fresh, "learning.forget", json("expectedRevision" to s.learningState(fresh).number("revision")))
            fresh = s.identity.issue(student, "operator")
            assertEquals("not_owned", assertFailsWith<FoundationFailure> { s.learningPlan(fresh, plan.obj("plan").text("id")) }.code)
            s.database.transaction(readOnly = true) { c, _ ->
                for (table in listOf("learning_plan", "learning_plan_revision", "learning_plan_history")) {
                    assertEquals(0L, c.rows("SELECT count(*) FROM $table") { it.getLong(1) }.single())
                }
            }
            assertTrue(s.learningTasks(fresh, null, null, null, 0)["items"]!!.jsonArray.isEmpty())
        }
    }
}
