package com.eliteteam.speakingcoach.foundation

import java.sql.DriverManager
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PostLessonReviewIntegrationTest {
    @Test
    fun completedRegularLessonCreatesOneReviewWithoutInventingKnowledgeOrNextMeeting() = runTest {
        val url = requireNotNull(System.getenv("TEST_POSTGRES_URL"))
        val schema = "review_${newId().replace("-", "")}" 
        DriverManager.getConnection(url).use { it.createStatement().execute("CREATE SCHEMA $schema") }
        val scoped = url + (if ('?' in url) "&" else "?") + "currentSchema=$schema"
        try {
            FoundationService(FoundationDatabase(scoped)).use { service ->
                val student = service.identity.resolve("telegram", System.nanoTime().toString())
                val scope = service.identity.issue(student, "operator")
                suspend fun command(action: String, args: JsonObject, id: String = newId()) = service.command(scope,
                    json("version" to 1, "operationId" to id, "action" to action, "args" to args))
                val lesson = command("lesson.create", json("kind" to "regular", "launchMode" to "executable",
                    "startsAt" to Instant.now().plusSeconds(600).toString(), "durationMinutes" to 30, "timezone" to "UTC"))
                service.database.transaction { c, _ -> c.update("UPDATE lesson SET status='in_progress' WHERE id=?", lesson.text("id")) }
                command("lesson.complete", json("lessonId" to lesson.text("id"), "expectedVersion" to lesson.number("version"),
                    "reason" to "end_button"))
                val pending = service.database.transaction(readOnly = true) { c, _ ->
                    c.documents("SELECT to_jsonb(j) FROM foundation_job j WHERE kind='lesson.review' AND target_id=?", lesson.text("id"))
                }
                assertEquals(1, pending.size)
                val claimed = command("job.claim", json("kind" to "lesson.review")).obj("job")
                val args = json("jobId" to claimed.text("id"), "fence" to claimed.number("fence"), "result" to json())
                val receipt = newId()
                assertEquals("succeeded", command("job.apply", args, receipt).text("status"))
                assertEquals("succeeded", command("job.apply", args, receipt).text("status"))
                val snapshot = service.snapshot(scope, "learning")
                val review = assertNotNull(snapshot["postLessonReview"] as? JsonObject)
                assertEquals(1, review.number("revision"))
                assertEquals("deferred_no_approved_sp17_conclusion", review.obj("document").text("topicKnowledgeDecision"))
                assertEquals(0, (review.obj("document")["tasks"] as kotlinx.serialization.json.JsonArray).size)
                val next = assertNotNull(snapshot["nextLessonContext"] as? JsonObject)
                assertNull(next.optionalText("nextTaskId"))
                assertEquals("false", next.text("priorityReady"))
                val history = service.database.transaction(readOnly = true) { c, _ ->
                    c.rows("SELECT count(*) FROM post_lesson_review_history WHERE student_id=?", student) { it.getLong(1) }.single()
                }
                assertEquals(1, history)
                service.database.transaction { c, now ->
                    c.update("""INSERT INTO lesson_evaluation(student_id,lesson_id,source_revision,analyzer_version,result,created_at)
                        VALUES (?,?,0,'test-review',?::jsonb,?)""", student, lesson.text("id"),
                        json("status" to "insufficient_evidence", "notes" to kotlinx.serialization.json.JsonArray(emptyList())), now)
                    PostLessonReview().enqueue(c, student, lesson.text("id"), now)
                }
                val revised = command("job.claim", json("kind" to "lesson.review")).obj("job")
                assertEquals("succeeded", command("job.apply", json("jobId" to revised.text("id"),
                    "fence" to revised.number("fence"), "result" to json())).text("status"))
                assertEquals(2, (service.snapshot(scope, "learning")["postLessonReview"] as JsonObject).number("revision"))
                assertEquals(2, service.database.transaction(readOnly = true) { c, _ ->
                    c.rows("SELECT count(*) FROM post_lesson_review_history WHERE student_id=?", student) { it.getLong(1) }.single()
                })
            }
        } finally {
            DriverManager.getConnection(url).use { it.createStatement().execute("DROP SCHEMA $schema CASCADE") }
        }
    }
}
