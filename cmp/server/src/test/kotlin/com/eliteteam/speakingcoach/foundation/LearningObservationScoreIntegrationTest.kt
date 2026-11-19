package com.eliteteam.speakingcoach.foundation

import java.sql.DriverManager
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LearningObservationScoreIntegrationTest {
    private suspend fun op(s: FoundationService, scope: String, action: String, args: JsonObject) =
        s.command(scope, json("version" to 1, "operationId" to newId(), "action" to action, "args" to args))

    @Test fun issuedExerciseObservationsReplaceRevisionsAndResetWithPlan() = runTest {
        val url = requireNotNull(System.getenv("TEST_POSTGRES_URL"))
        val schema = "obscore_${newId().replace("-", "")}"
        DriverManager.getConnection(url).use { it.createStatement().execute("CREATE SCHEMA $schema") }
        val scoped = url + (if ('?' in url) "&" else "?") + "currentSchema=$schema"
        try {
            FoundationService(FoundationDatabase(scoped)).use { s ->
                val manifest = catalogFixture()
                LearningCatalog(s.database).import(LearningCatalogModel.document(manifest).toString())
                LearningCatalog(s.database).publish(manifest.version, "synthetic-test", "fixture only")
                val student = s.identity.resolve("telegram", System.nanoTime().toString())
                val scope = s.identity.issue(student, "operator")
                val goal = s.history.accept(student, newId(), "B2 for work", Instant.now(), false)
                val goalSource = s.database.transaction(readOnly = true) { c, _ ->
                    val e = c.document("SELECT to_jsonb(e) FROM conversation_event e WHERE id=?", goal.text("event_id"))!!
                    JsonArray(listOf(json("type" to "event", "id" to e.text("id"), "revision" to e.number("sequence"))))
                }
                op(s, scope, "learning.update", json("expectedRevision" to 0, "patch" to json(), "goal" to "B2 for work",
                    "component" to "test", "sources" to goalSource))
                val topic = manifest.topics.first { it.skillId == "grammar" }
                val task = op(s, scope, "learning.task.create", json("goalRevision" to 1, "catalogVersion" to manifest.version,
                    "skillId" to "grammar", "topicId" to topic.id, "problemId" to "grammar_application", "component" to "test")).obj("task")
                val taskId = task.text("id")
                val plan = op(s, scope, "learning.plan.create", json("taskId" to taskId,
                    "expectedTaskRevision" to task.number("revision"), "definition" to planFixture(), "component" to "test")).obj("plan")
                val planId = plan.text("id")
                val lesson = op(s, scope, "lesson.create", json("kind" to "regular", "launchMode" to "placeholder",
                    "startsAt" to Instant.now().plusSeconds(3600).toString(), "durationMinutes" to 30, "timezone" to "UTC"))
                val lessonId = lesson.text("id")
                val session = newId()
                s.database.transaction { c, now ->
                    c.update("""INSERT INTO call_session(id,student_id,lesson_id,attempt_id,status,started_at)
                        VALUES (?,?,?,?, 'connected',?)""", session, student, lessonId, newId(), now)
                }
                val content = op(s, scope, "learning.content.create", json("document" to contentFixture(task, planId),
                    "component" to "test")).obj("content")
                op(s, scope, "learning.content.issue", json("contentId" to content.text("id"), "expectedRevision" to 1,
                    "lessonId" to lessonId))
                suspend fun answer(index: Int, method: String = "exercises", stageId: String = "exercise",
                    actionId: String = "${content.text("id")}:1", body: String = "I went to work yesterday."): JsonObject {
                    val event = s.database.transaction { c, now -> LessonManagement().append(c,
                        ExecutionScope(student, "operator", 1), json("lessonId" to lessonId, "callSessionId" to session,
                        "sourceId" to "answer-$index", "role" to "user", "body" to body,
                        "occurredAt" to Instant.now().toString()), now) }
                    return JsonObject(observationFixture(event.text("eventId"), event.number("sequence")) + json(
                        "attemptId" to "exercise-attempt-$index", "evidenceSlot" to "exercise-$index",
                        "lessonId" to lessonId, "practice" to "regular", "method" to method,
                        "catalogVersion" to manifest.version, "topicId" to topic.id, "problemId" to "grammar_application",
                        "taskId" to taskId, "stageRef" to json("planId" to planId, "planRevision" to 1,
                            "stageId" to stageId, "stageAttemptId" to null), "actionId" to actionId,
                        "route" to "stage_progress"))
                }
                val uncertainBase = answer(0)
                val uncertain = JsonObject(uncertainBase + json("quality" to json("status" to "uncertain",
                    "signals" to JsonArray(listOf(json("reason" to "uncertain_asr", "component" to "test",
                        "sourceEventId" to uncertainBase.text("anchorEventId")))))))
                op(s, scope, "learning.observation.record", json("observation" to uncertain))
                assertEquals(0.0, s.learningScore(scope, taskId)["progress"]!!.jsonPrimitive.doubleOrNull)
                val invalidBase = answer(-2)
                val invalid = JsonObject(invalidBase + json("outcome" to "invalid", "support" to "unknown",
                    "initialOutcome" to "unknown", "quality" to json("status" to "invalid",
                        "signals" to JsonArray(listOf(json("reason" to "uncertain_asr", "component" to "test",
                            "sourceEventId" to invalidBase.text("anchorEventId")))))))
                op(s, scope, "learning.observation.record", json("observation" to invalid))
                assertEquals(0.0, s.learningScore(scope, taskId)["progress"]!!.jsonPrimitive.doubleOrNull)
                val hintedBase = answer(-1)
                val hint = s.database.transaction { c, now -> LessonManagement().append(c,
                    ExecutionScope(student, "operator", 1), json("lessonId" to lessonId, "callSessionId" to session,
                    "sourceId" to "hint-1", "role" to "assistant", "body" to "Try the past form.",
                    "occurredAt" to Instant.now().toString()), now) }
                val hinted = JsonObject(hintedBase + json("outcome" to "hinted_correct", "initialOutcome" to "error",
                    "support" to "hinted", "sources" to JsonArray((hintedBase["sources"] as JsonArray) +
                        json("eventId" to hint.text("eventId"), "revision" to hint.number("sequence"),
                            "role" to "assistant", "purpose" to "hint"))))
                op(s, scope, "learning.observation.record", json("observation" to hinted))
                assertEquals(0.0, s.learningScore(scope, taskId)["progress"]!!.jsonPrimitive.doubleOrNull)
                var lastDocument: JsonObject? = null
                var lastObservation: JsonObject? = null
                var firstDocument: JsonObject? = null
                var firstObservation: JsonObject? = null
                for (n in 1..10) {
                    val document = answer(n)
                    val observed = op(s, scope, "learning.observation.record", json("observation" to document)).obj("observation")
                    assertEquals("verified", observed.text("referenceStatus"))
                    lastDocument = document
                    lastObservation = observed
                    if (n == 1) { firstDocument = document; firstObservation = observed }
                }
                val passed = s.learningScore(scope, taskId)
                assertEquals(30.0, passed["progress"]!!.jsonPrimitive.doubleOrNull)
                assertTrue((passed["stages"]!!.jsonArray[1] as JsonObject).text("completed").toBoolean())
                assertFalse(passed.text("masteryReady").toBoolean())
                val taskRevision = s.learningTask(scope, taskId).obj("task").number("revision")
                op(s, scope, "learning.observation.record", json("observation" to lastDocument!!))
                assertEquals(taskRevision, s.learningTask(scope, taskId).obj("task").number("revision"))
                val corrected = JsonObject(lastDocument + json("outcome" to "uncorrected_error", "initialOutcome" to "error"))
                op(s, scope, "learning.observation.revise", json("observationId" to lastObservation!!.text("id"),
                    "expectedRevision" to lastObservation.number("revision"), "observation" to corrected, "reason" to "correction"))
                val afterCorrection = s.learningScore(scope, taskId)
                assertFalse((afterCorrection["stages"]!!.jsonArray[1] as JsonObject).text("completed").toBoolean())
                assertTrue(afterCorrection["progress"]!!.jsonPrimitive.doubleOrNull!! < 30.0)
                op(s, scope, "learning.observation.invalidate", json("observationId" to lastObservation.text("id"),
                    "expectedRevision" to 2, "component" to "test", "reason" to "invalid evidence"))
                assertTrue(s.learningScore(scope, taskId)["progress"]!!.jsonPrimitive.doubleOrNull!! >
                    afterCorrection["progress"]!!.jsonPrimitive.doubleOrNull!!)
                op(s, scope, "learning.observation.record", json("observation" to answer(11)))
                assertEquals(30.0, s.learningScore(scope, taskId)["progress"]!!.jsonPrimitive.doubleOrNull)
                val conversationContent = op(s, scope, "learning.content.create", json("document" to
                    contentFixture(task, planId, "Conversation", "converse"), "component" to "test")).obj("content")
                op(s, scope, "learning.content.issue", json("contentId" to conversationContent.text("id"),
                    "expectedRevision" to 1, "lessonId" to lessonId))
                for (n in 1..10) op(s, scope, "learning.observation.record", json("observation" to
                    answer(100 + n, "conversation", "converse", "${conversationContent.text("id")}:1")))
                val numericReady = s.learningScore(scope, taskId)
                assertEquals(100.0, numericReady["progress"]!!.jsonPrimitive.doubleOrNull)
                assertFalse(numericReady.text("masteryReady").toBoolean())
                val explanationContent = op(s, scope, "learning.content.create", json("document" to
                    contentFixture(task, planId, "Explanation", "explain"), "component" to "test")).obj("content")
                op(s, scope, "learning.content.issue", json("contentId" to explanationContent.text("id"),
                    "expectedRevision" to 1, "lessonId" to lessonId))
                op(s, scope, "learning.observation.record", json("observation" to
                    answer(200, "explanation", "explain", "${explanationContent.text("id")}:1")))
                val mastered = s.learningScore(scope, taskId)
                assertTrue(mastered.text("masteryReady").toBoolean())
                assertEquals(100.0, s.learningTask(scope, taskId).obj("task")["progress"]!!.jsonPrimitive.doubleOrNull)
                val reopened = op(s, scope, "learning.score.fixture", json("taskId" to taskId, "planId" to planId,
                    "planRevision" to 1, "stageId" to "exercise", "fixtureEventId" to "operator-reopen",
                    "reopen" to true, "reason" to "manual_correction"))
                assertEquals(70.0, reopened["progress"]!!.jsonPrimitive.doubleOrNull)
                assertFalse(reopened.text("masteryReady").toBoolean())
                val earlierError = JsonObject(firstDocument!! + json("outcome" to "uncorrected_error",
                    "initialOutcome" to "error"))
                op(s, scope, "learning.observation.revise", json("observationId" to firstObservation!!.text("id"),
                    "expectedRevision" to 1, "observation" to earlierError, "reason" to "correct earlier analysis"))
                assertEquals(70.0, s.learningScore(scope, taskId)["progress"]!!.jsonPrimitive.doubleOrNull)
                val laterAnswer = answer(12)
                op(s, scope, "learning.observation.record", json("observation" to laterAnswer))
                assertTrue(s.learningScore(scope, taskId)["progress"]!!.jsonPrimitive.doubleOrNull!! > 70.0)
                s.database.transaction { c, now ->
                    c.update("UPDATE conversation_event SET expires_at=? WHERE student_id=? AND id=?",
                        now.minusSeconds(1), student, laterAnswer.text("anchorEventId"))
                }
                op(s, scope, "retention.prune", json())
                assertEquals(70.0, s.learningScore(scope, taskId)["progress"]!!.jsonPrimitive.doubleOrNull)
                val planCurrent = s.learningPlan(scope, planId).obj("plan")
                val currentTask = s.learningTask(scope, taskId).obj("task")
                op(s, scope, "learning.plan.revise", json("taskId" to taskId, "planId" to planId,
                    "expectedTaskRevision" to currentTask.number("revision"), "expectedPlanRevision" to 1,
                    "expectedStateRevision" to planCurrent.obj("currentState").number("stateRevision"),
                    "definition" to planFixture(), "component" to "test"))
                assertEquals(0.0, s.learningScore(scope, taskId)["progress"]!!.jsonPrimitive.doubleOrNull)
                assertEquals(0.0, s.learningTask(scope, taskId).obj("task")["progress"]!!.jsonPrimitive.doubleOrNull)
                val oldHistorySize = s.learningScore(scope, taskId, history = true)["items"]!!.jsonArray.size
                assertTrue(oldHistorySize > 20)
                op(s, scope, "retention.prune", json())
                assertEquals(oldHistorySize, s.learningScore(scope, taskId, history = true)["items"]!!.jsonArray.size)
            }
        } finally {
            DriverManager.getConnection(url).use { it.createStatement().execute("DROP SCHEMA $schema CASCADE") }
        }
    }
}
