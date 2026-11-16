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

class TrialIntegrationTestV55 {
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
        val schema = "sp06_${newId().replace("-", "")}"
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
            "startsAt" to starts.toString(), "timezone" to "UTC", "durationMinutes" to 30))
    }
    private suspend fun open(s: FoundationService, subject: String, id: String, now: Instant) = TrialAccess(s).access(
        json("action" to "open", "initData" to auth(subject, now), "lessonId" to id, "attemptId" to newId()), bot)

    private fun voice(l: JsonObject, a: JsonObject) = json("lessonId" to l.text("id"), "callSessionId" to a.text("callSessionId"))
    private suspend fun ready(s: FoundationService, l: JsonObject, a: JsonObject) = s.op(a.text("scope"), "trial.ready", voice(l, a))
    private suspend fun advance(s: FoundationService, clock: TestClock, l: JsonObject, a: JsonObject, seconds: Long) {
        repeat((seconds / 30).toInt()) {
            clock.value = clock.value.plusSeconds(30)
            s.op(a.text("scope"), "voice.heartbeat", voice(l, a))
        }
    }
    private suspend fun events(s: FoundationService, l: JsonObject, token: String, offset: Int = 0) = s.database.transaction(readOnly = true) { c, now ->
        TrialCallEvents().read(c, authenticateScope(c, token, now), json("lessonId" to l.text("id"), "offset" to offset), now)
    }

    @Test fun reconnectKeepsConnectedTimeAndPhaseAndCanFinishBeyondSlot() = runTest {
        store { s, clock ->
            val l = lesson(s, "100", clock.value)
            val a = open(s, "100", l.text("id"), clock.value)
            assertEquals(0L, ready(s, l, a).number("connectedSeconds"))
            advance(s, clock, l, a, 900)
            s.op(a.text("scope"), "trial.phase", JsonObject(voice(l, a) + json("phase" to "conversation")))
            s.op(a.text("scope"), "voice.end", JsonObject(voice(l, a) + json("reason" to "technical_disconnect")))
            clock.value = clock.value.plusSeconds(600)
            val b = open(s, "100", l.text("id"), clock.value)
            assertEquals(900L, b.obj("trialExecution").number("connectedSeconds"))
            assertEquals("conversation", b.obj("trialExecution").text("phase"))
            ready(s, l, b)
            advance(s, clock, l, b, 900)
            assertEquals(1800L, events(s, l, b.text("scope")).obj("execution").number("connectedSeconds"))
            s.op(b.text("scope"), "voice.end", JsonObject(voice(l, b) + json("reason" to "natural_goodbye")))
            assertEquals("lesson_unavailable", assertFailsWith<FoundationFailure> { open(s, "100", l.text("id"), clock.value) }.code)
        }
    }

    @Test fun reconnectAfterOriginalSlotIsRejectedAndEarlyButtonIsTerminal() = runTest {
        store { s, clock ->
            val l = lesson(s, "100", clock.value)
            val a = open(s, "100", l.text("id"), clock.value)
            ready(s, l, a)
            advance(s, clock, l, a, 30)
            s.op(a.text("scope"), "voice.end", JsonObject(voice(l, a) + json("reason" to "technical_disconnect")))
            clock.value = clock.value.plusSeconds(1800)
            assertEquals("lesson_expired", assertFailsWith<FoundationFailure> { open(s, "100", l.text("id"), clock.value) }.code)
            val other = lesson(s, "200", clock.value)
            val b = open(s, "200", other.text("id"), clock.value)
            ready(s, other, b)
            val end = JsonObject(voice(other, b) + json("reason" to "end_button"))
            assertEquals("true", s.op(b.text("scope"), "voice.end", end, "end-once").text("completed"))
            s.op(b.text("scope"), "voice.end", end, "end-once")
            assertEquals("lesson_unavailable", assertFailsWith<FoundationFailure> { open(s, "200", other.text("id"), clock.value) }.code)
        }
    }

    @Test fun preferencesAreScopedExplicitRevisionedAndRejectAssessmentWrites() = runTest {
        store { s, clock ->
            val l = lesson(s, "100", clock.value)
            val a = open(s, "100", l.text("id"), clock.value)
            ready(s, l, a)
            val source = s.op(a.text("scope"), "voice.append", JsonObject(voice(l, a) + json("sourceId" to "u1", "role" to "user",
                "body" to "Three lessons. No homework. Duration unknown.", "occurredAt" to clock.value.toString())))
            val args = JsonObject(voice(l, a) + json("sourceId" to source.text("eventId"), "expectedRevision" to 0,
                "changes" to JsonArray(listOf(json("field" to "lessonsPerWeek", "value" to 3, "evidence" to "Three lessons"),
                    json("field" to "consent", "value" to "no", "evidence" to "No homework"),
                    json("field" to "lessonDurationMinutes", "value" to null, "evidence" to "Duration unknown")))))
            val saved = s.op(a.text("scope"), "trial.preferences", args, "preferences-once")
            assertEquals("3", saved.obj("profile").obj("intensity").text("lessonsPerWeek"))
            assertEquals("no", saved.obj("profile").obj("homework").text("consent"))
            assertEquals(saved, s.op(a.text("scope"), "trial.preferences", args, "preferences-once"))
            assertEquals("version_conflict", assertFailsWith<FoundationFailure> { s.op(a.text("scope"), "trial.preferences", args) }.code)
            val forbidden = JsonObject(args + json("expectedRevision" to 1, "changes" to JsonArray(listOf(json("field" to "targetLevel", "value" to "B2", "evidence" to "Three lessons")))))
            assertEquals("preference_not_allowed", assertFailsWith<FoundationFailure> { s.op(a.text("scope"), "trial.preferences", forbidden) }.code)
            val invalid = JsonObject(args + json("expectedRevision" to 1, "changes" to JsonArray(listOf(json("field" to "lessonsPerWeek", "value" to "2–3", "evidence" to "Three lessons")))))
            assertFailsWith<FoundationFailure> { s.op(a.text("scope"), "trial.preferences", invalid) }
            val other = lesson(s, "200", clock.value)
            val b = open(s, "200", other.text("id"), clock.value)
            assertFailsWith<FoundationFailure> { s.op(b.text("scope"), "trial.preferences", JsonObject(args + voice(other, b))) }
        }
    }

    @Test fun rawEventsDeduplicateAndCorrectionsKeepIdentityWithSeparateRevision() = runTest {
        store { s, clock ->
            val l = lesson(s, "100", clock.value)
            val a = open(s, "100", l.text("id"), clock.value)
            ready(s, l, a)
            val args = JsonObject(voice(l, a) + json("sourceId" to "provider-item", "role" to "user", "body" to "I works",
                "occurredAt" to clock.value.toString(), "revision" to 1, "metadata" to json("providerItemId" to "item", "finality" to "final")))
            val first = s.op(a.text("scope"), "voice.append", args)
            assertEquals(first.text("eventId"), s.op(a.text("scope"), "voice.append", args).text("eventId"))
            val corrected = s.op(a.text("scope"), "voice.append", JsonObject(args + json("revision" to 2, "body" to "I work")))
            assertEquals(first.text("eventId"), corrected.text("eventId"))
            assertEquals(first.text("rawEventId"), corrected.text("rawEventId"))
            assertEquals(2L, corrected.number("revision"))
            assertTrue(corrected.number("sequence") > first.number("sequence"))
            val transcript = events(s, l, a.text("scope"))["events"]!!.jsonArray.map { it.jsonObject }.single { it.text("kind") == "transcript" }
            assertEquals("I work", transcript.text("body"))
            assertEquals("null", transcript.obj("metadata")["asrConfidence"].toString())
            assertTrue(transcript.obj("metadata")["uncertaintySignals"]!!.jsonArray.isEmpty())
            assertFailsWith<FoundationFailure> { s.op(a.text("scope"), "voice.append", JsonObject(args + json("body" to "conflicting replay"))) }
        }
    }

    @Test fun completedTrialExtractsOnlyMemoryAndNeverQueuesOldRoadmap() = runTest {
        store { s, clock ->
            val l = lesson(s, "100", clock.value)
            val a = open(s, "100", l.text("id"), clock.value)
            ready(s, l, a)
            s.op(a.text("scope"), "voice.end", JsonObject(voice(l, a) + json("reason" to "end_button")))
            val operator = s.identity.issue(l.text("student_id"), "operator")
            val job = s.op(operator, "job.claim", json("kind" to "lesson.evaluate")).obj("job")
            assertEquals("trial-memory-v55", job.obj("input_refs").text("analyzerVersion"))
            val result = json("status" to "insufficient_evidence", "observations" to JsonArray(emptyList()),
                "facts" to JsonArray(emptyList()), "candidates" to JsonArray(emptyList()), "notes" to JsonArray(emptyList()))
            val bad = JsonObject(result + json("observations" to JsonArray(listOf(json("dimension" to "grammar")))))
            assertEquals("legacy_trial_assessment_forbidden", assertFailsWith<FoundationFailure> {
                s.op(operator, "job.apply", json("jobId" to job.text("id"), "fence" to job.number("fence"), "result" to bad))
            }.code)
            s.op(operator, "job.apply", json("jobId" to job.text("id"), "fence" to job.number("fence"), "result" to result))
            s.database.transaction { c, _ ->
                assertEquals(0L, c.rows("SELECT count(*) FROM memory_observation") { it.getLong(1) }.single())
                assertEquals(0L, c.rows("SELECT count(*) FROM foundation_job WHERE kind='roadmap.prepare'") { it.getLong(1) }.single())
            }
        }
    }

    @Test fun resetDeletesRawTransportWithoutForeignKeyOrReplayLeak() = runTest {
        store { s, clock ->
            val l = lesson(s, "100", clock.value)
            val a = open(s, "100", l.text("id"), clock.value)
            ready(s, l, a)
            s.op(a.text("scope"), "voice.append", JsonObject(voice(l, a) + json("sourceId" to "u", "role" to "user", "body" to "Private fact",
                "occurredAt" to clock.value.toString())))
            s.op(s.identity.issue(l.text("student_id"), "operator"), "student.delete", json())
            s.database.transaction { c, _ -> assertEquals(0L, c.rows("SELECT count(*) FROM trial_call_event") { it.getLong(1) }.single()) }
            assertFailsWith<FoundationFailure> { events(s, l, a.text("scope")) }
        }
    }
    @Test fun expiredLeaseDoesNotCountOfflineTimeAndSweepClosesAbandonedTrial() = runTest {
        store { s, clock ->
            val l = lesson(s, "100", clock.value)
            val a = open(s, "100", l.text("id"), clock.value)
            ready(s, l, a)
            advance(s, clock, l, a, 60)
            clock.value = clock.value.plusSeconds(1800)
            val operator = s.identity.issue(l.text("student_id"), "operator")
            assertEquals(60L, events(s, l, operator).obj("execution").number("connectedSeconds"))
            val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { error("sweep must not send") })
            try { TrialWorker(s, http, "http://unused", "test", bot, "http://mini").sweep() } finally { http.close() }
            val result = events(s, l, operator)
            assertEquals("completed", result.obj("lesson").text("status"))
            assertEquals("technical_disconnect", result.obj("lesson").text("outcome_reason"))
            assertEquals(60L, result.obj("execution").number("connectedSeconds"))
        }
    }

}
