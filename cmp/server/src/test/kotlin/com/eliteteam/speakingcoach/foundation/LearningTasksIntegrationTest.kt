package com.eliteteam.speakingcoach.foundation

import java.sql.DriverManager
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LearningTasksIntegrationTest {
    private suspend fun store(block: suspend (FoundationService, String) -> Unit) {
        val url = requireNotNull(System.getenv("TEST_POSTGRES_URL"))
        val schema = "tasks_${newId().replace("-", "")}"
        DriverManager.getConnection(url).use { it.createStatement().execute("CREATE SCHEMA $schema") }
        val scoped = url + (if ('?' in url) "&" else "?") + "currentSchema=$schema"
        try { FoundationService(FoundationDatabase(scoped)).use { block(it, scoped) } }
        finally { DriverManager.getConnection(url).use { it.createStatement().execute("DROP SCHEMA $schema CASCADE") } }
    }

    private suspend fun FoundationService.op(scope: String, action: String, args: JsonObject, id: String = newId()) =
        command(scope, json("version" to 1, "operationId" to id, "action" to "learning.task.$action", "args" to args))

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
        s.command(scope, json("version" to 1, "operationId" to newId(), "action" to "learning.update", "args" to
            json("expectedRevision" to 0, "patch" to json(), "goal" to "B2 for work", "component" to "test", "sources" to source)))
        val topic = manifest.topics.first { it.skillId == "grammar" }
        return scope to json("goalRevision" to 1, "catalogVersion" to manifest.version, "skillId" to "grammar",
            "topicId" to topic.id, "problemId" to "grammar_application", "component" to "test", "sources" to source,
            "reason" to "Personal context for this task")
    }

    private fun change(task: JsonObject, vararg fields: Pair<String, Any?>) = JsonObject(json(
        "taskId" to task.text("id"), "expectedRevision" to task.number("revision"), "component" to "test") + json(*fields))

    @Test
    fun lifecycleRetainsIdentityHistoryAndPlanAcrossRestart() = runTest {
        store { s, url ->
            val (scope, args) = setup(s)
            val created = s.op(scope, "create", args)
            var task = created.obj("task")
            val id = task.text("id")
            assertEquals("Identified", task.text("lifecycle"))
            assertEquals(0.0, LearningTaskModel.progress(task["progress"]))
            task = s.op(scope, "update", change(task, "patch" to json("priority" to 7,
                "plan" to json("id" to "plan-1", "revision" to 1), "homework" to JsonArray(listOf(json("id" to "homework-1")))))).obj("task")
            for ((state, progress) in listOf("In Progress" to 25, "Mastered" to 100, "Reopened" to 100, "In Progress" to 40)) {
                task = s.op(scope, "transition", change(task, "lifecycle" to state, "progress" to progress, "decisionId" to newId())).obj("task")
            }
            val before = task
            task = s.op(scope, "update", change(task, "patch" to json("priority" to null))).obj("task")
            assertEquals(before["progress"], task["progress"])
            assertEquals(before.obj("details")["plan"], task.obj("details")["plan"])
            val history = s.learningTask(scope, id, true)["items"]!!.jsonArray
            assertEquals(7, history.size)
            assertTrue(history.any { it.jsonObject.obj("state").text("lifecycle") == "Mastered" })
            // A prepared in-progress lesson fixture; exercise the real completion operation without AI.
            val lesson = s.command(scope, json("version" to 1, "operationId" to newId(), "action" to "lesson.create", "args" to
                json("kind" to "trial", "launchMode" to "executable", "startsAt" to Instant.now().plusSeconds(600).toString(),
                    "durationMinutes" to 30, "timezone" to "UTC")))
            s.database.transaction { c, _ -> c.update("UPDATE lesson SET status='in_progress' WHERE id=?", lesson.text("id")) }
            s.command(scope, json("version" to 1, "operationId" to newId(), "action" to "lesson.complete", "args" to
                json("lessonId" to lesson.text("id"), "expectedVersion" to lesson.number("version"), "reason" to "end_button")))
            assertEquals(task, s.learningTask(scope, id).obj("task"))
            FoundationService(FoundationDatabase(url)).use { restarted ->
                val token = restarted.identity.issue(task.text("student_id"), "operator")
                assertEquals(task, restarted.learningTask(token, id).obj("task"))
            }
            val topics = s.topicKnowledge(scope, args.text("catalogVersion"), args.text("topicId"), 0, false)["items"]!!.jsonArray
            assertEquals("Unknown", topics.single().jsonObject.text("status"))
        }
    }

    @Test
    fun replayConcurrentCreateAndPublishedVersionDoNotDuplicateWork() = runTest {
        store { s, _ ->
            val (scope, args) = setup(s)
            val operation = newId()
            val first = s.op(scope, "create", args, operation)
            assertEquals(first, s.op(scope, "create", args, operation))
            assertEquals("operation_conflict", assertFailsWith<FoundationFailure> {
                s.op(scope, "create", JsonObject(args + json("reason" to "different")), operation)
            }.code)
            val results = coroutineScope { (1..4).map { async { s.op(scope, "create", args) } }.map { it.await() } }
            assertTrue(results.all { it.obj("task").text("id") == first.obj("task").text("id") && it.text("created") == "false" })
            val next = catalogFixture().copy(version = "next-published")
            LearningCatalog(s.database).import(LearningCatalogModel.document(next).toString())
            LearningCatalog(s.database).publish(next.version, "test", "fixture")
            assertEquals(first.obj("task"), s.op(scope, "create", JsonObject(args + json("catalogVersion" to next.version))).obj("task"))
            val other = s.op(scope, "create", JsonObject(args + json("problemId" to "grammar_unknown"))).obj("task")
            assertTrue(other.text("id") != first.obj("task").text("id"))
            assertEquals(2, s.learningTasks(scope, null, null, null, 0)["items"]!!.jsonArray.size)
        }
    }

    @Test
    fun staleWritesInvalidCatalogAndForeignStudentsAreRejected() = runTest {
        store { s, _ ->
            val (scope, args) = setup(s)
            for (patch in listOf(json("skillId" to "vocabulary"), json("problemId" to "missing"), json("topicId" to "missing"))) {
                assertEquals("invalid_task_catalog_links", assertFailsWith<FoundationFailure> {
                    s.op(scope, "create", JsonObject(args + patch))
                }.code)
            }
            assertEquals("task_goal_required", assertFailsWith<FoundationFailure> {
                s.op(scope, "create", JsonObject(args + json("goalRevision" to 99)))
            }.code)
            val draft = catalogFixture().copy(version = "unpublished")
            LearningCatalog(s.database).import(LearningCatalogModel.document(draft).toString())
            assertEquals("catalog_not_published", assertFailsWith<FoundationFailure> {
                s.op(scope, "create", JsonObject(args + json("catalogVersion" to draft.version)))
            }.code)
            val task = s.op(scope, "create", args).obj("task")
            val other = s.identity.resolve("telegram", System.nanoTime().toString())
            val foreign = s.identity.issue(other, "operator")
            assertEquals("not_owned", assertFailsWith<FoundationFailure> { s.learningTask(foreign, task.text("id")) }.code)
            assertEquals("not_owned", assertFailsWith<FoundationFailure> {
                s.op(foreign, "update", change(task, "patch" to json("priority" to 2)))
            }.code)
            val voice = s.identity.issue(task.text("student_id"), "voice")
            assertEquals("not_permitted", assertFailsWith<FoundationFailure> { s.op(voice, "create", args) }.code)
            val patch = change(task, "patch" to json("priority" to 2))
            val outcomes = coroutineScope { (1..2).map { async { runCatching { s.op(scope, "update", patch) } } }.map { it.await() } }
            assertEquals(1, outcomes.count { it.isSuccess })
            assertEquals("version_conflict", (outcomes.single { it.isFailure }.exceptionOrNull() as FoundationFailure).code)
            assertEquals(2, s.learningTask(scope, task.text("id"), true)["items"]!!.jsonArray.size)
        }
    }

    @Test
    fun externalScoreDoesNotInferMasteryAndReopenDoesNotReset() = runTest {
        store { s, _ ->
            val (scope, args) = setup(s)
            var task = s.op(scope, "create", args).obj("task")
            assertEquals("invalid_task_transition", assertFailsWith<FoundationFailure> {
                s.op(scope, "transition", change(task, "lifecycle" to "Mastered", "decisionId" to "premature"))
            }.code)
            task = s.op(scope, "transition", change(task, "lifecycle" to "In Progress", "decisionId" to "practice")).obj("task")
            for (invalid in listOf(JsonPrimitive(-1), JsonPrimitive(101), JsonPrimitive("50"))) {
                assertEquals("invalid_task_progress", assertFailsWith<FoundationFailure> {
                    s.op(scope, "progress", change(task, "progress" to invalid, "decisionId" to "bad"))
                }.code)
            }
            task = s.op(scope, "progress", change(task, "progress" to 100, "decisionId" to "score-1")).obj("task")
            assertEquals("In Progress", task.text("lifecycle"))
            task = s.op(scope, "transition", change(task, "lifecycle" to "Mastered", "decisionId" to "mastery")).obj("task")
            assertEquals(task, s.op(scope, "create", args).obj("task"))
            task = s.op(scope, "transition", change(task, "lifecycle" to "Reopened", "decisionId" to "review")).obj("task")
            assertEquals(100.0, LearningTaskModel.progress(task["progress"]))
            val same = s.op(scope, "transition", change(task, "lifecycle" to "Reopened", "decisionId" to "repeat")).obj("task")
            assertEquals(task, same)
        }
    }

    @Test
    fun personalForgetPreservesTaskWhileLearningForgetDeletesIt() = runTest {
        store { s, _ ->
            var (scope, args) = setup(s)
            val source = args["sources"]!!.jsonArray.single().jsonObject
            val task = s.op(scope, "create", args).obj("task")
            s.command(scope, json("version" to 1, "operationId" to newId(), "action" to "memory.record", "args" to
                json("sourceId" to source.text("id"), "evidence" to "work", "category" to "personal", "key" to "job",
                    "kind" to "user_report", "value" to "work", "expectedVersion" to 0)))
            s.command(scope, json("version" to 1, "operationId" to newId(), "action" to "memory.forget", "args" to
                json("category" to "personal", "key" to "job", "expectedVersion" to 1)))
            val expired = scope
            scope = s.identity.issue(task.text("student_id"), "operator")
            assertEquals(task, s.learningTask(scope, task.text("id")).obj("task"))
            assertEquals("", s.learningTask(scope, task.text("id"), true)["items"]!!.jsonArray.single().jsonObject.text("reason"))
            assertEquals("scope_expired", assertFailsWith<FoundationFailure> { s.learningTask(expired, task.text("id")) }.code)
            val revision = s.learningState(scope).number("revision")
            s.command(scope, json("version" to 1, "operationId" to newId(), "action" to "learning.forget", "args" to
                json("expectedRevision" to revision)))
            scope = s.identity.issue(task.text("student_id"), "operator")
            assertTrue(s.learningTasks(scope, null, null, null, 0)["items"]!!.jsonArray.isEmpty())
            s.database.transaction(readOnly = true) { c, _ ->
                assertEquals(0L, c.rows("SELECT count(*) FROM learning_task_history WHERE student_id=?", task.text("student_id")) { it.getLong(1) }.single())
            }
        }
    }
    @Test
    fun retentionAndGoalChangePreserveWorkAndDeletionReplayRemovesIt() = runTest {
        store { s, _ ->
            val (scope, args) = setup(s)
            val task = s.op(scope, "create", args).obj("task")
            val student = task.text("student_id")
            s.database.transaction { c, now ->
                c.update("UPDATE conversation_event SET expires_at=? WHERE student_id=?", now.minusSeconds(1), student)
                FoundationMaintenance().prune(c, authenticateScope(c, scope, now), now)
                c.update("INSERT INTO learning_goal(student_id,revision,content,created_at) VALUES (?,2,?::jsonb,?)",
                    student, json("value" to "A new goal"), now)
            }
            assertEquals(task, s.learningTask(scope, task.text("id")).obj("task"))
            val history = s.learningTask(scope, task.text("id"), true)["items"]!!.jsonArray.single().jsonObject
            assertEquals("true", history["sources"]!!.jsonArray.single().jsonObject.text("sourceContentExpired"))
            // Simulate the external deletion ledger imported after restoring an earlier database snapshot.
            s.database.transaction { c, now ->
                c.update("""INSERT INTO foundation_deletion(id,student_id,kind,category,fact_key,deleted_at,resulting_epoch)
                    VALUES (?,?,'forget','learning','state',?,2)""", newId(), student, now)
            }
            val replay = json("version" to 1, "operationId" to newId(), "action" to "deletion.replay", "args" to json())
            assertEquals(1L, s.command(scope, replay).number("replayed"))
            val fresh = s.identity.issue(student, "operator")
            assertTrue(s.learningTasks(fresh, null, null, null, 0)["items"]!!.jsonArray.isEmpty())
            assertEquals(0L, s.command(fresh, JsonObject(replay + json("operationId" to newId()))).number("replayed"))
        }
    }

    @Test
    fun startDeletesTasksAndReplayDoesNotResetAgain() = runTest {
        store { s, _ ->
            val (scope, args) = setup(s)
            val task = s.op(scope, "create", args).obj("task")
            val old = task.text("student_id")
            val subject = s.database.transaction(readOnly = true) { c, _ ->
                c.rows("SELECT subject FROM student_identity WHERE student_id=?", old) { it.getString(1) }.single()
            }
            val chat = subject.toLong()
            val at = Instant.now()
            val request = com.eliteteam.speakingcoach.ai.TutorRequest("tg-$chat", "message:2", "/start", at.epochSecond.toDouble())
            val bridge = FoundationChat(s)
            assertTrue(bridge.prepareIntroduction(request, chat, 2))
            val fresh = s.identity.resolve("telegram", subject)
            assertTrue(fresh != old)
            assertTrue(bridge.prepareIntroduction(request, chat, 2))
            assertEquals(fresh, s.identity.resolve("telegram", subject))
            s.database.transaction(readOnly = true) { c, _ ->
                assertEquals(0L, c.rows("SELECT count(*) FROM learning_task WHERE student_id=?", old) { it.getLong(1) }.single())
                assertEquals(0L, c.rows("SELECT count(*) FROM learning_task_history WHERE student_id=?", old) { it.getLong(1) }.single())
            }
        }
    }

    @Test
    fun taskPaginationIsStableAndHistoryIsPaged() = runTest {
        store { s, _ ->
            val (scope, args) = setup(s)
            val manifest = catalogFixture()
            for (topic in manifest.topics.take(51)) {
                val problem = manifest.problems.first { it.skillId == topic.skillId }
                s.op(scope, "create", JsonObject(args + json("topicId" to topic.id, "skillId" to topic.skillId, "problemId" to problem.id)))
            }
            val first = s.learningTasks(scope, null, null, null, 0)
            val second = s.learningTasks(scope, null, null, null, 50)
            assertEquals(50, first["items"]!!.jsonArray.size)
            assertEquals(50L, first.number("nextOffset"))
            assertEquals(1, second["items"]!!.jsonArray.size)
            val ids = (first["items"]!!.jsonArray + second["items"]!!.jsonArray).map { it.jsonObject.text("id") }
            assertEquals(51, ids.toSet().size)
            var task = first["items"]!!.jsonArray.first().jsonObject
            repeat(50) { index -> task = s.op(scope, "update", change(task, "patch" to json("priority" to index))).obj("task") }
            assertEquals(50, s.learningTask(scope, task.text("id"), true)["items"]!!.jsonArray.size)
            assertEquals(1, s.learningTask(scope, task.text("id"), true, 50)["items"]!!.jsonArray.size)
        }
    }

}
