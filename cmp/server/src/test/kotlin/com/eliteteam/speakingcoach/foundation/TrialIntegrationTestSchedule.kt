package com.eliteteam.speakingcoach.foundation

import io.ktor.client.engine.mock.respond
import java.net.URLEncoder
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TrialIntegrationTestSchedule {
    private val bot = "123:test-only-token"
    private class TestClock(var value: Instant = Instant.parse("2026-11-11T10:00:00Z")) : Clock() {
        override fun instant() = value
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private fun auth(subject: String, now: Instant): String {
        fun hmac(key: ByteArray, body: String): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256")); doFinal(body.toByteArray())
        }
        val fields = sortedMapOf("auth_date" to now.epochSecond.toString(), "user" to "{\"id\":$subject}", "query_id" to "test")
        val hash = hmac(hmac("WebAppData".toByteArray(), bot), fields.entries.joinToString("\n") { "${it.key}=${it.value}" }).joinToString("") { "%02x".format(it) }
        return (fields + ("hash" to hash)).entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
    }
    private suspend fun store(clock: TestClock = TestClock(), block: suspend (FoundationService, TestClock) -> Unit) {
        val raw = requireNotNull(System.getenv("TEST_POSTGRES_URL"))
        val url = if (raw.startsWith("jdbc:")) raw else "jdbc:$raw"
        val schema = "sp02_${newId().replace("-", "")}"
        DriverManager.getConnection(url).use { it.createStatement().use { sql -> sql.execute("CREATE SCHEMA $schema") } }
        try {
            FoundationService(FoundationDatabase(url + (if ('?' in url) "&" else "?") + "currentSchema=$schema", clock)).use { block(it, clock) }
        } finally {
            DriverManager.getConnection(url).use { it.createStatement().use { sql -> sql.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }
    private suspend fun FoundationService.op(scope: String, action: String, args: JsonObject, id: String = newId()) =
        command(scope, json("version" to 1, "operationId" to id, "action" to action, "args" to args))
    private suspend fun lesson(s: FoundationService, subject: String, starts: Instant): JsonObject {
        val student = s.identity.resolve("telegram", subject)
        return s.op(s.identity.issue(student, "operator"), "lesson.create", json("kind" to "trial", "launchMode" to "executable",
            "startsAt" to starts.toString(), "timezone" to "UTC", "durationMinutes" to 15))
    }
    private suspend fun open(s: FoundationService, subject: String, id: String, now: Instant) = TrialAccess(s).access(
        json("action" to "open", "initData" to auth(subject, now), "lessonId" to id, "attemptId" to newId()), bot)

    private suspend fun agree(s: FoundationService, clock: TestClock, zone: String = "UTC", date: String = "2026-11-11", time: String = "14:00"): Pair<String, JsonObject> {
        val student = s.identity.resolve("telegram", "100")
        val source = s.history.accept(student, newId(), "Согласен каждый день", clock.value, false)
        val scope = s.identity.issue(student, "operator")
        val result = s.op(scope, "schedule.agree", json("sourceId" to source.text("event_id"), "evidence" to "Согласен",
            "timezone" to zone, "startDate" to date, "localTime" to time, "weekdays" to JsonArray((1..7).map { JsonPrimitive(it) }), "durationMinutes" to 30))
        return scope to result.obj("schedule")
    }
    private fun worker(s: FoundationService) = TrialWorker(s, io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine {
        error("No outbound requests allowed during sweep")
    }), "http://test", "test", bot, "https://test")

    @Test fun dstGapAndFoldNeverChooseAnOffset() {
        val zone = ZoneId.of("Europe/Berlin")
        for (date in listOf("2027-03-28", "2027-10-31")) {
            assertTrue(assertFailsWith<FoundationFailure> {
                scheduleInstant(java.time.LocalDate.parse(date), java.time.LocalTime.parse("02:30"), zone)
            }.code.startsWith("schedule_time_needs_clarification:"))
        }
        assertEquals(Instant.parse("2027-03-27T01:30:00Z"), scheduleInstant(java.time.LocalDate.parse("2027-03-27"), java.time.LocalTime.parse("02:30"), zone))
        assertEquals(Instant.parse("2027-03-29T00:30:00Z"), scheduleInstant(java.time.LocalDate.parse("2027-03-29"), java.time.LocalTime.parse("02:30"), zone))
    }

    @Test fun repeatedMaterializationPreservesMovedAndCancelledOccurrences() = runTest {
        store { s, clock ->
            val (scope, schedule) = agree(s, clock)
            val lessons = s.op(scope, "lesson.list", json())["lessons"]!!.jsonArray.map { it.jsonObject }
            assertEquals(14, lessons.size)
            assertTrue(lessons.all { it.text("launch_mode") == "executable" })
            val moved = lessons[0]
            val cancelled = lessons[1]
            s.op(scope, "lesson.reschedule", json("lessonId" to moved.text("id"), "expectedVersion" to 1, "startsAt" to "2026-11-11T15:00:00Z", "timezone" to "UTC"))
            s.op(scope, "lesson.cancel", json("lessonId" to cancelled.text("id"), "expectedVersion" to 1))
            repeat(2) { worker(s).sweep() }
            s.database.transaction { c, _ ->
                assertEquals(14L, c.rows("SELECT count(*) FROM lesson WHERE schedule_id=?", schedule.text("id")) { it.getLong(1) }.single())
                assertEquals(2L, c.rows("SELECT count(*) FROM schedule_exception") { it.getLong(1) }.single())
                assertEquals("cancelled", c.document("SELECT to_jsonb(l) FROM lesson l WHERE id=?", cancelled.text("id"))!!.text("status"))
                assertEquals(Instant.parse("2026-11-11T15:00:00Z"), Instant.parse(c.document("SELECT to_jsonb(l) FROM lesson l WHERE id=?", moved.text("id"))!!.text("starts_at")))
            }
        }
    }

    @Test fun rollingHorizonPreservesDstIssueAndResolutionWithoutDuplicates() = runTest {
        store(TestClock(Instant.parse("2027-03-01T10:00:00Z"))) { s, clock ->
            val (_, schedule) = agree(s, clock, "Europe/Berlin", "2027-03-02", "02:30")
            clock.value = Instant.parse("2027-03-20T10:00:00Z")
            repeat(2) { worker(s).sweep() }
            val student = schedule.text("student_id")
            val scope = s.identity.issue(student, "operator")
            val issues = s.op(scope, "trial.schedules", json())["timeIssues"]!!.jsonArray
            assertEquals(1, issues.size)
            val issue = issues.single().jsonObject
            assertEquals("2027-03-28", issue.text("local_date"))
            val source = s.history.accept(student, newId(), "В этот день в 03:30", clock.value, false)
            val args = json("scheduleId" to schedule.text("id"), "expectedVersion" to 1, "occurrenceKey" to issue.text("occurrence_key"),
                "localTime" to "03:30", "sourceId" to source.text("event_id"), "evidence" to "03:30")
            val result = s.op(scope, "schedule.resolve_time", args, "resolve")
            assertEquals(result, s.op(scope, "schedule.resolve_time", args, "resolve"))
            assertEquals(Instant.parse("2027-03-28T01:30:00Z"), Instant.parse(result.text("starts_at")))
            worker(s).sweep()
            assertEquals(0, s.op(scope, "trial.schedules", json())["timeIssues"]!!.jsonArray.size)
        }
    }

    @Test fun remindersDeduplicateAndNoShowBecomesMissedWithoutPenalty() = runTest {
        store { s, clock ->
            val (scope, _) = agree(s, clock)
            val first = s.op(scope, "lesson.next", json()).obj("lesson")
            val id = first.text("id")
            clock.value = Instant.parse(first.text("starts_at")).minusSeconds(600)
            repeat(2) { worker(s).sweep() }
            clock.value = clock.value.plusSeconds(600)
            repeat(2) { worker(s).sweep() }
            clock.value = clock.value.plusSeconds(301)
            repeat(2) { worker(s).sweep() }
            s.database.transaction { c, _ -> assertEquals(3L, c.rows("SELECT count(*) FROM trial_notice WHERE lesson_id=?", id) { it.getLong(1) }.single()) }
            clock.value = clock.value.plusSeconds(1500)
            worker(s).sweep()
            s.database.transaction { c, _ ->
                assertEquals("missed", c.document("SELECT to_jsonb(l) FROM lesson l WHERE id=?", id)!!.text("status"))
                assertEquals(0L, c.rows("SELECT count(*) FROM entitlement") { it.getLong(1) }.single())
            }
        }
    }

    @Test fun cancellationAndAdmissionUseTheSameStudentLock() = runTest {
        store { s, clock ->
            // The shared admission locking path is already executable for trial on this branch.
            val l = lesson(s, "100", clock.value)
            val scope = s.identity.issue(l.text("student_id"), "operator")
            s.op(scope, "lesson.cancel", json("lessonId" to l.text("id"), "expectedVersion" to 1))
            assertEquals("lesson_unavailable", assertFailsWith<FoundationFailure> { open(s, "100", l.text("id"), clock.value) }.code)
            val second = lesson(s, "100", clock.value)
            open(s, "100", second.text("id"), clock.value)
            assertEquals("version_conflict", assertFailsWith<FoundationFailure> {
                s.op(scope, "lesson.cancel", json("lessonId" to second.text("id"), "expectedVersion" to 1))
            }.code)
        }
    }
    @Test fun concurrentAdmissionAndCancellationHaveOneWinner() = runTest {
        store { s, clock ->
            repeat(6) { index ->
                val subject = (1000 + index).toString()
                val lesson = lesson(s, subject, clock.value)
                val scope = s.identity.issue(lesson.text("student_id"), "operator")
                val outcomes = kotlinx.coroutines.coroutineScope {
                    val cancel = async(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { s.op(scope, "lesson.cancel", json("lessonId" to lesson.text("id"), "expectedVersion" to 1)) }
                    }
                    val admit = async(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { open(s, subject, lesson.text("id"), clock.value) }
                    }
                    listOf(cancel.await(), admit.await())
                }
                assertEquals(1, outcomes.count { it.isSuccess })
                assertTrue(outcomes.single { it.isFailure }.exceptionOrNull() is FoundationFailure)
            }
        }
    }

    @Test fun rescheduleDuringReminderGenerationFencesDelivery() = runTest {
        store { s, clock ->
            val (initialScope, _) = agree(s, clock)
            val lesson = s.op(initialScope, "lesson.next", json()).obj("lesson")
            clock.value = Instant.parse(lesson.text("starts_at")).minusSeconds(600)
            val scope = s.identity.issue(lesson.text("student_id"), "operator")
            var sends = 0
            val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
                if (request.url.host == "ai.test") {
                    s.op(scope, "lesson.reschedule", json("lessonId" to lesson.text("id"), "expectedVersion" to 1,
                        "startsAt" to clock.value.plusSeconds(7200).toString(), "timezone" to "UTC"))
                    respond("{\"reply\":\"Скоро урок\"}")
                } else { sends++; respond("{\"ok\":true}") }
            })
            TrialWorker(s, http, "https://ai.test", "test", bot, "https://mini.test").tick()
            assertEquals(0, sends)
            s.database.transaction { c, _ ->
                assertEquals("cancelled", c.document("SELECT to_jsonb(n) FROM trial_notice n WHERE lesson_id=? AND kind='reminder'", lesson.text("id"))!!.text("status"))
            }
            http.close()
        }
    }

    @Test fun unknownRegularReminderDeliveryIsNeverRepeated() = runTest {
        store { s, clock ->
            val (scope, _) = agree(s, clock)
            val lesson = s.op(scope, "lesson.next", json()).obj("lesson")
            clock.value = Instant.parse(lesson.text("starts_at")).minusSeconds(600)
            var sends = 0
            val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
                if (request.url.host == "ai.test") respond("{\"reply\":\"Скоро урок\"}")
                else { sends++; throw java.io.IOException("uncertain delivery") }
            })
            val worker = TrialWorker(s, http, "https://ai.test", "test", bot, "https://mini.test")
            assertFailsWith<java.io.IOException> { worker.tick() }
            worker.tick()
            assertEquals(1, sends)
            http.close()
        }
    }

    @Test fun scheduleRevisionChangesFrequencyTimeAndDurationAndKeepsStartedLesson() = runTest {
        store { s, clock ->
            val (scope, schedule) = agree(s, clock)
            val before = s.op(scope, "lesson.list", json())["lessons"]!!.jsonArray.map { it.jsonObject }
            s.database.transaction { c, _ -> c.update("UPDATE lesson SET status='in_progress' WHERE id=?", before.first().text("id")) }
            val source = s.history.accept(schedule.text("student_id"), newId(), "Теперь два раза по 45 минут", clock.value, false)
            val args = json("sourceId" to source.text("event_id"), "evidence" to "Теперь два раза", "scheduleId" to schedule.text("id"),
                "expectedVersion" to 1, "timezone" to "Europe/Moscow", "startDate" to "2026-11-12", "localTime" to "20:00",
                "weekdays" to JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(4))), "durationMinutes" to 45)
            val updated = s.op(scope, "schedule.agree", args, "change")
            assertEquals(updated, s.op(scope, "schedule.agree", args, "change"))
            assertEquals(2L, updated.obj("schedule").number("version"))
            val after = s.op(scope, "lesson.list", json())["lessons"]!!.jsonArray.map { it.jsonObject }
            assertEquals(5, after.size)
            assertEquals(4, after.count { it.number("duration_minutes") == 45L })
            assertEquals(1, after.count { it.text("status") == "in_progress" })
        }
    }

    @Test fun dstClarificationIsSentOnceAndDeletedWithStudent() = runTest {
        store(TestClock(Instant.parse("2027-03-01T10:00:00Z"))) { s, clock ->
            val (_, schedule) = agree(s, clock, "Europe/Berlin", "2027-03-02", "02:30")
            clock.value = Instant.parse("2027-03-20T10:00:00Z")
            var sends = 0
            val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
                assertEquals("api.telegram.org", request.url.host)
                sends++
                respond("{\"ok\":true}")
            })
            val worker = TrialWorker(s, http, "https://ai.test", "test", bot, "https://mini.test")
            repeat(2) { worker.tick() }
            assertEquals(1, sends)
            val scope = s.identity.issue(schedule.text("student_id"), "operator")
            s.op(scope, "student.delete", json())
            s.database.transaction { c, _ -> assertEquals(0L, c.rows("SELECT count(*) FROM schedule_time_issue") { it.getLong(1) }.single()) }
            http.close()
        }
    }

}
