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

class TrialIntegrationTest {
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

    @Test fun signatureRejectsTamperingDuplicateFieldsAndStaleAuth() {
        val now = Instant.parse("2026-11-11T10:00:00Z")
        assertEquals("100", telegramMiniSubject(auth("100", now), bot, now))
        assertFailsWith<FoundationFailure> { telegramMiniSubject(auth("100", now) + "&auth_date=1", bot, now) }
        assertFailsWith<FoundationFailure> { telegramMiniSubject(auth("100", now), "wrong", now) }
        assertFailsWith<FoundationFailure> { telegramMiniSubject(auth("100", now.minusSeconds(86401)), bot, now) }
    }

    @Test fun admissionEnforcesOwnershipTimeAndSingleEpisodeThenFencesOldTransport() = runTest {
        store { s, clock ->
            val l = lesson(s, "100", clock.value.plusSeconds(60))
            assertEquals("too_early", assertFailsWith<FoundationFailure> { open(s, "100", l.text("id"), clock.value) }.code)
            assertEquals("not_owned", assertFailsWith<FoundationFailure> { open(s, "200", l.text("id"), clock.value) }.code)
            clock.value = clock.value.plusSeconds(60)
            val first = open(s, "100", l.text("id"), clock.value)
            assertEquals("active_episode", assertFailsWith<FoundationFailure> { open(s, "100", l.text("id"), clock.value) }.code)
            clock.value = clock.value.plusSeconds(46)
            val second = open(s, "100", l.text("id"), clock.value)
            assertNotEquals(first.text("callSessionId"), second.text("callSessionId"))
            assertEquals("stale_episode", assertFailsWith<FoundationFailure> { s.op(first.text("scope"), "voice.heartbeat", json("lessonId" to l.text("id"), "callSessionId" to first.text("callSessionId"))) }.code)
            s.op(second.text("scope"), "voice.end", json("lessonId" to l.text("id"), "callSessionId" to second.text("callSessionId"), "reason" to "technical_disconnect"))
            clock.value = clock.value.plusSeconds(86400 * 2)
            val third = open(s, "100", l.text("id"), clock.value)
            assertEquals("in_progress", third.obj("lesson").text("status"))
        }
    }

    @Test fun naturalEndIsIdempotentAndQueuesOneEvaluationAndNotice() = runTest {
        store { s, clock ->
            val l = lesson(s, "100", clock.value)
            val a = open(s, "100", l.text("id"), clock.value)
            val args = json("lessonId" to l.text("id"), "callSessionId" to a.text("callSessionId"), "reason" to "natural_goodbye")
            val result = s.op(a.text("scope"), "voice.end", args, "end")
            assertEquals(result, s.op(a.text("scope"), "voice.end", args, "end"))
            assertEquals("lesson_unavailable", assertFailsWith<FoundationFailure> { open(s, "100", l.text("id"), clock.value) }.code)
            s.database.transaction { c, _ ->
                assertEquals(1L, c.rows("SELECT count(*) FROM trial_notice WHERE kind='followup'") { it.getLong(1) }.single())
                assertEquals(1L, c.rows("SELECT count(*) FROM foundation_job WHERE kind='lesson.evaluate'") { it.getLong(1) }.single())
            }
        }
    }

    @Test fun demoScheduleCreatesOnlyBlockedLessonsAndNoEntitlement() = runTest {
        store { s, clock ->
            val student = s.identity.resolve("telegram", "100")
            val source = s.history.accept(student, "schedule", "Каждый день в 14:00 на 30 минут", clock.value, false)
            val scope = s.identity.issue(student, "operator")
            val saved = s.op(scope, "trial.schedule", json("sourceId" to source.text("event_id"), "evidence" to "Каждый день",
                "timezone" to "UTC", "startDate" to "2026-11-11", "localTime" to "14:00", "weekdays" to JsonArray((1..7).map { JsonPrimitive(it) }), "durationMinutes" to 30))
            val id = saved.obj("schedule").text("id")
            val access = TrialAccess(s)
            val payment = json("action" to "demo_pay", "initData" to auth("100", clock.value), "scheduleId" to id)
            assertEquals(access.access(payment, bot), access.access(payment, bot))
            val list = access.access(json("action" to "list", "initData" to auth("100", clock.value)), bot)
            assertEquals(14, list["lessons"]!!.jsonArray.size)
            val placeholder = list["lessons"]!!.jsonArray.first().jsonObject
            assertEquals("lesson_unavailable", assertFailsWith<FoundationFailure> { open(s, "100", placeholder.text("id"), clock.value) }.code)
            s.database.transaction { c, _ ->
                assertEquals(0L, c.rows("SELECT count(*) FROM entitlement") { it.getLong(1) }.single())
                assertEquals(1L, c.rows("SELECT count(*) FROM trial_demo_receipt") { it.getLong(1) }.single())
            }
        }
    }

    @Test fun audioArchivePagesBeyondLegacyLimitAndKeepsOwnership() = runTest {
        store { s, clock ->
            val l = lesson(s, "100", clock.value)
            val a = open(s, "100", l.text("id"), clock.value)
            s.database.transaction { c, now ->
                c.update("INSERT INTO trial_audio(object_key,student_id,lesson_id,call_session_id,data_epoch,direction,sequence,created_at,expires_at,status) SELECT 'trial/page/' || n,?,?,?,1,'user',n,?,?,'stored' FROM generate_series(0,2104) AS n",
                    l.text("student_id"), l.text("id"), a.text("callSessionId"), now, now.plusSeconds(3600))
            }
            val access = TrialAccess(s)
            val keys = mutableSetOf<String>()
            var cursor: String? = null
            do {
                val page = access.access(json("action" to "audio", "initData" to auth("100", clock.value), "lessonId" to l.text("id"), "cursor" to cursor), bot)
                val objects = page["objects"]!!.jsonArray
                assertTrue(objects.size <= 200)
                objects.forEach { assertTrue(keys.add(it.jsonObject.text("object_key"))) }
                cursor = page.optionalText("nextCursor")
            } while (cursor != null)
            assertEquals(2105, keys.size)
            assertEquals("not_owned", assertFailsWith<FoundationFailure> {
                access.access(json("action" to "audio", "initData" to auth("200", clock.value), "lessonId" to l.text("id"), "cursor" to "trial/page/0"), bot)
            }.code)
        }
    }

    @Test fun audioExpiryAndDeletionRevokeAccessWithoutExtendingDeadline() = runTest {
        store { s, clock ->
            val l = lesson(s, "100", clock.value)
            val a = open(s, "100", l.text("id"), clock.value)
            val args = json("lessonId" to l.text("id"), "callSessionId" to a.text("callSessionId"), "direction" to "user", "sequence" to 0)
            val first = s.op(a.text("scope"), "voice.audio", args)
            clock.value = clock.value.plusSeconds(5)
            assertEquals(first, s.op(a.text("scope"), "voice.audio", args))
            val student = l.text("student_id")
            val operator = s.identity.issue(student, "operator")
            s.op(operator, "student.delete", json())
            s.database.transaction { c, _ ->
                val audio = c.document("SELECT to_jsonb(a) FROM trial_audio a WHERE object_key=?", first.text("objectKey"))!!
                assertEquals("purge", audio.text("status"))
            }
            assertFailsWith<FoundationFailure> { s.op(a.text("scope"), "voice.heartbeat", json("lessonId" to l.text("id"), "callSessionId" to a.text("callSessionId"))) }
        }
    }
    @Test fun reconnectDuringFollowupGenerationSuppressesTelegramDelivery() = runTest {
        store { s, clock ->
            val l = lesson(s, "100", clock.value)
            val a = open(s, "100", l.text("id"), clock.value)
            s.op(a.text("scope"), "voice.end", json("lessonId" to l.text("id"), "callSessionId" to a.text("callSessionId"), "reason" to "technical_disconnect"))
            clock.value = clock.value.plusSeconds(31)
            var telegramCalls = 0
            val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
                if (request.url.host == "ai.test") {
                    open(s, "100", l.text("id"), clock.value)
                    respond("{\"reply\":\"Вернёмся к разговору?\"}", io.ktor.http.HttpStatusCode.OK)
                } else {
                    telegramCalls++
                    respond("{\"ok\":true}", io.ktor.http.HttpStatusCode.OK)
                }
            })
            http.use { TrialWorker(s, it, "https://ai.test", "internal", bot, "https://mini.test/miniapp").tick() }
            assertEquals(0, telegramCalls)
        }
    }

    @Test fun unknownTelegramDeliveryIsNeverAutomaticallyRepeated() = runTest {
        store { s, clock ->
            val l = lesson(s, "100", clock.value)
            val a = open(s, "100", l.text("id"), clock.value)
            s.op(a.text("scope"), "voice.end", json("lessonId" to l.text("id"), "callSessionId" to a.text("callSessionId"), "reason" to "natural_goodbye"))
            clock.value = clock.value.plusSeconds(4)
            var telegramCalls = 0
            val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
                if (request.url.host == "ai.test") respond("{\"reply\":\"Обсудим удобный формат?\"}", io.ktor.http.HttpStatusCode.OK)
                else { telegramCalls++; respond("upstream disconnected", io.ktor.http.HttpStatusCode.BadGateway) }
            })
            http.use {
                val worker = TrialWorker(s, it, "https://ai.test", "internal", bot, "https://mini.test/miniapp")
                worker.tick(); worker.tick()
            }
            assertEquals(1, telegramCalls)
            s.database.transaction { c, _ ->
                assertEquals("unknown", c.document("SELECT to_jsonb(n) FROM trial_notice n WHERE lesson_id=?", l.text("id"))!!.text("status"))
            }
        }
    }

}
