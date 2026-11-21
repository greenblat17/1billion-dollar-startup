package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.DiagnosticOutput
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertFalse
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
    private suspend fun lesson(s: FoundationService, subject: String, starts: Instant, duration: Long = 30): JsonObject {
        val student = s.identity.resolve("telegram", subject)
        return s.op(s.identity.issue(student, "operator"), "lesson.create", json("kind" to "trial", "launchMode" to "executable",
            "startsAt" to starts.toString(), "timezone" to "UTC", "durationMinutes" to duration))
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
            DiagnosticOutput().use { logs ->
                advance(s, clock, l, a, 900)
                assertTrue(logs.events().isEmpty())
            }
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

    @Test fun sp24PreservesEnglishClockAndPreferredAddressAcrossReconnect() = runTest {
        store { s, clock ->
            val start = clock.value
            val l = lesson(s, "100", start, duration = TRIAL_SLOT_MINUTES)
            val a = open(s, "100", l.text("id"), clock.value)
            assertEquals(1200L, ready(s, l, a).number("plannedSeconds"))
            val first = voice(l, a)
            val source = s.op(a.text("scope"), "voice.append", JsonObject(first + json(
                "sourceId" to "name-1", "role" to "user", "body" to "Зови меня Алекс", "occurredAt" to clock.value.toString())))
            val saved = s.op(a.text("scope"), "student.preferred_address", JsonObject(first + json(
                "sourceId" to source.text("eventId"), "value" to "Алекс", "evidence" to "Зови меня Алекс")))
            assertEquals("voice_explicit", saved.text("source"))
            assertEquals(source.text("eventId"), saved.text("sourceEventId"))
            advance(s, clock, l, a, 120)
            assertEquals(120_000L, s.op(a.text("scope"), "trial.phase", JsonObject(first + json(
                "phase" to "conversation"))).number("conversationStartMs"))
            advance(s, clock, l, a, 420)
            s.op(a.text("scope"), "voice.end", JsonObject(first + json("reason" to "technical_disconnect")))
            clock.value = clock.value.plusSeconds(60)
            val b = open(s, "100", l.text("id"), clock.value)
            val second = voice(l, b)
            val state = ready(s, l, b)
            assertEquals(540_000L, state.number("connectedMs"))
            assertEquals(1_020_000L, state.number("conversationEndMs"))
            val reopened = s.snapshot(b.text("scope"), "voice", l.text("id"))
            assertEquals(saved, reopened.obj("student").obj("profile").obj("preferredAddress"))
            advance(s, clock, l, b, 450)
            val wrapup = JsonObject(second + json("phase" to "wrapup"))
            clock.value = clock.value.plusMillis(29_999)
            assertEquals("trial_conversation_in_progress", assertFailsWith<FoundationFailure> {
                s.op(b.text("scope"), "trial.phase", wrapup)
            }.code)
            clock.value = clock.value.plusMillis(1)
            assertEquals("wrapup", s.op(b.text("scope"), "trial.phase", wrapup).text("phase"))
            s.op(b.text("scope"), "voice.end", JsonObject(second + json("reason" to "natural_goodbye")))
            val operator = s.identity.issue(l.text("student_id"), "operator")
            assertEquals("trial.observe", s.op(operator, "job.claim", json("kind" to "trial.observe")).obj("job").text("kind"))
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

    @Test fun sp24ReconnectRestoresOnlySupportedDialogueCheckpoints() = runTest {
        store { s, clock ->
            val l = lesson(s, "100", clock.value, duration = TRIAL_SLOT_MINUTES)
            val a = open(s, "100", l.text("id"), clock.value)
            ready(s, l, a)
            val first = voice(l, a)
            fun turn(id: String, role: String, body: String, metadata: JsonObject = json()) = JsonObject(first + json(
                "sourceId" to id, "role" to role, "body" to body,
                "occurredAt" to clock.value.toString(), "metadata" to metadata,
            ))
            val learner = s.op(a.text("scope"), "voice.append", turn("reply", "user", "Лучше без имени"))
            val decision = JsonObject(first + json("decision" to "name_refused", "sourceId" to learner.text("eventId"),
                "evidence" to "Лучше без имени"))
            s.op(a.text("scope"), "trial.dialogue", decision, "name-refusal")
            s.op(a.text("scope"), "trial.dialogue", decision, "name-refusal")
            val interrupted = s.op(a.text("scope"), "voice.append", turn("format", "assistant", "[Прервана]",
                json("finality" to "partial", "playbackComplete" to false, "trialDialogueInterrupted" to "explain_format")))
            s.op(a.text("scope"), "voice.end", JsonObject(first + json("reason" to "technical_disconnect")))
            val reopened = open(s, "100", l.text("id"), clock.value)
            val checkpoints = reopened.obj("trialDialogue")
            assertEquals(learner.number("sequence"), checkpoints.number("name_refused"))
            assertEquals(interrupted.number("sequence"), checkpoints.number("interrupted_explain_format"))
            assertFalse("explain_format" in checkpoints)
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

    @Test fun completedTrialObservationJobAppliesRealSourceOnceAndRejectsListening() = runTest {
        store { s, clock ->
            val catalog = catalogFixture()
            LearningCatalog(s.database).import(LearningCatalogModel.document(catalog).toString())
            LearningCatalog(s.database).publish(catalog.version, "synthetic", "test fixture only")
            val l = lesson(s, "100", clock.value)
            val a = open(s, "100", l.text("id"), clock.value)
            ready(s, l, a)
            val source = s.op(a.text("scope"), "voice.append", JsonObject(voice(l, a) + json(
                "sourceId" to "u1", "role" to "user", "body" to "I work here",
                "occurredAt" to clock.value.toString(), "metadata" to json("finality" to "final"))))
            s.op(a.text("scope"), "voice.end", JsonObject(voice(l, a) + json("reason" to "end_button")))
            val operator = s.identity.issue(l.text("student_id"), "operator")
            var job = s.op(operator, "job.claim", json("kind" to "trial.observe")).obj("job")
            assertEquals("trial-observation-v1", job.obj("input_refs").text("analyzerVersion"))
            val document = JsonObject(observationFixture(source.text("eventId"), source.number("sequence")) + json(
                "attemptId" to source.text("eventId"), "evidenceSlot" to "grammar-example",
                "lessonId" to l.text("id"), "occurredAt" to clock.value.toString(),
                "catalogVersion" to catalog.version, "practice" to "trial",
                "provenance" to json("component" to "trial-observation-extractor", "analyzerVersion" to "trial-observation-v1")))
            val listening = JsonObject(document + json("skillId" to "listening"))
            assertEquals("invalid_result", assertFailsWith<FoundationFailure> {
                s.op(operator, "job.apply", json("jobId" to job.text("id"), "fence" to job.number("fence"),
                    "result" to json("schemaVersion" to 1, "observations" to JsonArray(listOf(document, listening)))))
            }.code)
            assertEquals("not_started", events(s, l, operator).text("assessmentStatus"))
            assertTrue(s.op(operator, "job.claim", json("kind" to "trial.assess"))["job"] == kotlinx.serialization.json.JsonNull)
            assertTrue(s.learningObservations(operator)["items"]!!.jsonArray.isEmpty())
            s.op(operator, "job.fail", json("jobId" to job.text("id"), "fence" to job.number("fence"), "code" to "invalid_result"))
            assertEquals("not_started", events(s, l, operator).text("assessmentStatus"))
            s.op(operator, "job.retry", json("jobId" to job.text("id")))
            job = s.op(operator, "job.claim", json("kind" to "trial.observe")).obj("job")
            val args = json("jobId" to job.text("id"), "fence" to job.number("fence"),
                "result" to json("schemaVersion" to 1, "observations" to JsonArray(listOf(document)), "initialMaterializationOnly" to true))
            assertEquals("succeeded", s.op(operator, "job.apply", args, "apply-trial-once").text("status"))
            assertEquals("succeeded", s.op(operator, "job.apply", args, "apply-trial-once").text("status"))
            val items = s.learningObservations(operator, mapOf("lessonId" to l.text("id")))["items"]!!.jsonArray
            assertEquals(1, items.size)
            assertEquals("grammar", items.single().jsonObject.obj("document").text("skillId"))
            assertEquals("true", items.single().jsonObject.text("evidenceUsable"))
            // Another fenced job can reach apply after prior observations were materialized.
            val laterId = s.database.transaction { c, now -> c.enqueueJob(l.text("student_id"), "trial.observe",
                "filtered-reanalysis", l.text("id"), job.obj("input_refs"), now) }
            val later = s.op(operator, "job.claim", json("kind" to "trial.observe")).obj("job")
            assertEquals(laterId, later.text("id"))
            val replacement = JsonObject(document + json("evidenceSlot" to "different-example"))
            assertEquals("superseded", s.op(operator, "job.apply", json("jobId" to laterId, "fence" to later.number("fence"),
                "result" to json("schemaVersion" to 1, "observations" to JsonArray(listOf(replacement)),
                    "initialMaterializationOnly" to true))).text("status"))
            assertEquals(items, s.learningObservations(operator, mapOf("lessonId" to l.text("id")))["items"])
            assertEquals("queued", events(s, l, operator).text("assessmentStatus"))
            val assess = s.op(operator, "job.claim", json("kind" to "trial.assess")).obj("job")
            assertEquals(job.text("id"), assess.obj("input_refs").text("observationJobId"))
            val unknown = json("level" to "Unknown", "explanation" to "Insufficient independent evidence", "observationRefs" to JsonArray(emptyList()))
            val levels = json("overall" to unknown, "speaking" to unknown, "listening" to unknown,
                "grammar" to unknown, "vocabulary" to unknown)
            val ref = json("id" to items.single().jsonObject.text("id"), "revision" to items.single().jsonObject.number("revision"))
            val result = json("schemaVersion" to 1, "catalogVersion" to catalog.version, "baseLearningRevision" to 0,
                "observationRefs" to JsonArray(listOf(ref)), "levels" to levels, "topics" to JsonArray(emptyList()),
                "coverage" to json("active" to 1, "usableWithoutTopic" to 1, "uncertain" to 0, "nonIndependent" to 0))
            val updated = s.op(operator, "learning.update", json("expectedRevision" to 0,
                "patch" to json("currentLevels" to json("speaky" to json("skills" to json("grammar" to json(
                    "level" to "B1", "explanation" to "Later assessment"))))),
                "component" to "later-assessment", "sources" to JsonArray(listOf(json(
                    "type" to "event", "id" to source.text("eventId"), "revision" to source.number("sequence"))))))
            assertEquals(1L, updated.number("revision"))
            DiagnosticOutput().use { logs ->
                assertEquals("superseded", s.op(operator, "job.apply", json("jobId" to assess.text("id"),
                    "fence" to assess.number("fence"), "result" to result)).text("status"))
                val superseded = logs.events().single { it.text("event") == "trial_assessment_superseded" }
                assertEquals("learning_revision_changed", superseded.text("reason_code"))
                assertEquals("superseded", superseded.text("outcome"))
                assertEquals(assess.text("id"), superseded.text("job_id"))
            }
            assertEquals("B1", s.learningState(operator).obj("profile").obj("currentLevels").obj("speaky")
                .obj("skills").obj("grammar").text("level"))
        }
    }

    @Test fun assessmentWritesEvidenceBoundStateWithoutChangingSelfReport() = runTest {
        store { s, clock ->
            val catalog = catalogFixture()
            val grammarTopic = catalog.topics.first { it.skillId == "grammar" }.id
            LearningCatalog(s.database).import(LearningCatalogModel.document(catalog).toString())
            LearningCatalog(s.database).publish(catalog.version, "synthetic", "test fixture only")
            val l = lesson(s, "101", clock.value)
            val a = open(s, "101", l.text("id"), clock.value)
            ready(s, l, a)
            val sources = listOf("I work here", "I live nearby").mapIndexed { index, body ->
                s.op(a.text("scope"), "voice.append", JsonObject(voice(l, a) + json(
                    "sourceId" to "u$index", "role" to "user", "body" to body,
                    "occurredAt" to clock.value.plusSeconds(index.toLong()).toString(),
                    "metadata" to json("finality" to "final"))))
            }
            s.op(a.text("scope"), "voice.end", JsonObject(voice(l, a) + json("reason" to "end_button")))
            val operator = s.identity.issue(l.text("student_id"), "operator")
            val prior = s.op(operator, "learning.update", json("expectedRevision" to 0,
                "patch" to json("targetLevel" to "B2", "currentLevels" to json("selfAssessment" to json("skills" to json(
                    "listening" to json("level" to "B2", "explanation" to "Student report"))))),
                "goal" to "B2 for work", "component" to "onboarding", "sources" to JsonArray(listOf(json(
                    "type" to "event", "id" to sources.first().text("eventId"),
                    "revision" to sources.first().number("sequence"))))))
            val observe = s.op(operator, "job.claim", json("kind" to "trial.observe")).obj("job")
            val documents = sources.mapIndexed { index, source ->
                JsonObject(observationFixture(source.text("eventId"), source.number("sequence")) + json(
                    "attemptId" to source.text("eventId"), "evidenceSlot" to "grammar-$index",
                    "lessonId" to l.text("id"), "occurredAt" to clock.value.plusSeconds(index.toLong()).toString(),
                    "catalogVersion" to catalog.version, "topicId" to grammarTopic, "practice" to "trial",
                    "provenance" to json("component" to "trial-observation-extractor", "analyzerVersion" to "trial-observation-v1")))
            }
            s.op(operator, "job.apply", json("jobId" to observe.text("id"), "fence" to observe.number("fence"),
                "result" to json("schemaVersion" to 1, "observations" to JsonArray(documents))))
            val items = s.learningObservations(operator, mapOf("lessonId" to l.text("id")))["items"]!!.jsonArray
            val refs = JsonArray(items.map { item -> json("id" to item.jsonObject.text("id"),
                "revision" to item.jsonObject.number("revision")) })
            val assess = s.op(operator, "job.claim", json("kind" to "trial.assess")).obj("job")
            val unknown = json("level" to "Unknown", "explanation" to "Insufficient evidence", "observationRefs" to JsonArray(emptyList()))
            val grammar = json("level" to "A2", "explanation" to "Two independent grammar demonstrations; preliminary", "observationRefs" to refs)
            val result = json("schemaVersion" to 1, "catalogVersion" to catalog.version,
                "baseLearningRevision" to prior.number("revision"), "observationRefs" to refs,
                "levels" to json("overall" to unknown, "speaking" to unknown, "listening" to unknown,
                    "grammar" to grammar, "vocabulary" to unknown), "topics" to JsonArray(listOf(json(
                    "topicId" to grammarTopic, "status" to "Known", "explanation" to "Two independent correct Trial attempts",
                    "observationRefs" to refs))), "coverage" to json("active" to 2,
                    "usableWithoutTopic" to 0, "uncertain" to 0, "nonIndependent" to 0))
            val invalid = JsonObject(result + json("topics" to JsonArray(listOf(json(
                "topicId" to "invented-topic", "status" to "Known", "explanation" to "Invalid ID",
                "observationRefs" to refs)))))
            DiagnosticOutput().use { logs ->
                assertEquals("catalog_topic_not_found", assertFailsWith<FoundationFailure> {
                    s.op(operator, "job.apply", json("jobId" to assess.text("id"),
                        "fence" to assess.number("fence"), "result" to invalid))
                }.code)
                assertEquals(prior.number("revision"), s.learningState(operator).number("revision"))
                assertTrue(logs.events().none { it.text("event") == "trial_assessment_saved" })
                val apply = json("jobId" to assess.text("id"), "fence" to assess.number("fence"), "result" to result)
                repeat(2) {
                    assertEquals("succeeded", s.op(operator, "job.apply", apply, "assessment-apply-once").text("status"))
                }
                val saved = logs.events().single { it.text("event") == "trial_assessment_saved" }
                assertEquals("completed", saved.text("outcome"))
                assertEquals(l.text("id"), saved.text("lesson_id"))
                assertEquals(assess.text("id"), saved.text("job_id"))
                assertEquals(assess.number("fence"), saved.number("fence"))
                assertEquals(observe.text("id"), saved.text("source_job_id"))
                assertEquals(s.learningState(operator).number("revision"), saved.number("learning_revision"))
                assertEquals(2L, saved.number("count"))
            }
            val state = s.learningState(operator)
            assertEquals("A2", state.obj("profile").obj("currentLevels").obj("speaky").obj("skills").obj("grammar").text("level"))
            assertEquals("Unknown", state.obj("profile").obj("currentLevels").obj("speaky").obj("skills").obj("listening").text("level"))
            assertEquals("B2", state.obj("profile").obj("currentLevels").obj("selfAssessment").obj("skills").obj("listening").text("level"))
            assertEquals("post-trial-assessment", state.obj("provenance").obj("currentLevels.speaky.skills.grammar.level").text("component"))
            assertEquals("Known", s.topicKnowledge(operator, catalog.version, grammarTopic, 0, false)
                ["items"]!!.jsonArray.single().jsonObject.text("status"))
            assertEquals("succeeded", events(s, l, operator).text("assessmentStatus"))
            val firstGeneration = s.op(operator, "job.claim", json("kind" to "roadmap.generate")).obj("job")
            assertEquals("retry_wait", s.op(operator, "job.fail", json("jobId" to firstGeneration.text("id"),
                "fence" to firstGeneration.number("fence"), "code" to "provider_timeout")).text("status"))
            clock.value = clock.value.plusSeconds(5)
            val roadmapJob = s.op(operator, "job.claim", json("kind" to "roadmap.generate")).obj("job")
            assertEquals(firstGeneration.text("id"), roadmapJob.text("id"))
            assertEquals(firstGeneration.number("fence") + 1, roadmapJob.number("fence"))
            assertEquals(state.number("revision"), roadmapJob.obj("input_refs").number("learningRevision"))
            val speakingTopic = catalog.topics.first { it.skillId == "speaking" }
            val candidate = json("topicId" to speakingTopic.id, "skillId" to "speaking", "cefr" to speakingTopic.cefr,
                "problemId" to "speaking_barrier", "knowledge" to "Unknown", "provisional" to true,
                "reason" to "Coverage hypothesis; verify in practice.", "orderReason" to "Level proposal.",
                "goalFocus" to false, "taskId" to null, "order" to 1)
            val proposal = json("schemaVersion" to 1, "catalogVersion" to catalog.version,
                "goalRevision" to 1, "targetLevel" to "B2", "goal" to "B2 for work", "provisional" to true,
                "sequence" to JsonArray(listOf(candidate)), "taskCount" to 1, "assumptions" to JsonArray(emptyList()),
                "history" to JsonArray(emptyList()), "scope" to "initial_roadmap", "timeline" to null)
            val roadmapApply = json("jobId" to roadmapJob.text("id"), "fence" to roadmapJob.number("fence"), "result" to proposal)
            DiagnosticOutput().use { logs ->
                assertEquals("succeeded", s.op(operator, "job.apply", roadmapApply, "roadmap-apply-once").text("status"))
                assertEquals("succeeded", s.op(operator, "job.apply", roadmapApply, "roadmap-apply-once").text("status"))
                val saved = logs.events().single { it.text("event") == "trial_roadmap_saved" }
                assertEquals(1L, saved.number("roadmap_revision"))
                assertEquals(roadmapJob.text("id"), saved.text("job_id"))
                assertEquals(roadmapJob.number("fence"), saved.number("fence"))
                assertEquals(listOf("completed", "replayed"), logs.events().filter { it.text("event") == "backend_command" }
                    .map { it.text("outcome") })
            }
            val storedRoadmap = s.learningRoadmap(operator).obj("roadmap").obj("document")
            val taskId = storedRoadmap["sequence"]!!.jsonArray.single().jsonObject.text("taskId")
            assertEquals(0.0, LearningTaskModel.progress(s.learningTask(operator, taskId).obj("task")["progress"]))
            assertEquals(1, s.learningTasks(operator, null, null, null, 0)["items"]!!.jsonArray.size)
            val empty = JsonArray(emptyList())
            val priority = s.op(operator, "job.claim", json("kind" to "roadmap.prioritize")).obj("job")
            val decision = json("schemaVersion" to 1, "roadmapRevision" to 1, "trigger" to "initial",
                "orderedTasks" to JsonArray(listOf(json("taskId" to taskId, "priority" to 1, "reason" to "Initial work",
                    "evidence" to "hypothesis", "prerequisiteTaskIds" to empty))),
                "nextTaskId" to taskId, "focusTaskIds" to JsonArray(listOf(JsonPrimitive(taskId))),
                "deferredTaskIds" to empty, "declinedProblems" to empty, "needsRoadmapReview" to false,
                "explanation" to "Initial ordering", "scope" to "operational_priority")
            assertEquals("succeeded", s.op(operator, "job.apply", json("jobId" to priority.text("id"),
                "fence" to priority.number("fence"), "result" to json("expectedRevision" to 0, "decision" to decision))).text("status"))
            var preparations = 0
            var sends = 0
            DiagnosticOutput().use { logs ->
                HttpClient(MockEngine { request ->
                    if (request.url.host == "ai.test") {
                        preparations++
                        if (preparations == 1) respond("", HttpStatusCode.BadGateway)
                        else respond("""{"reply":"Маршрут","roadmapRevision":1}""", HttpStatusCode.OK)
                    } else {
                        sends++
                        respond("", HttpStatusCode.BadGateway)
                    }
                }).use { http ->
                    val worker = InitialRoadmapDelivery(s, http, "https://ai.test", "internal", bot, "https://mini.test")
                    worker.tick()
                    clock.value = clock.value.plusSeconds(30)
                    repeat(3) { worker.tick() }
                }
                val deliveryEvents = logs.events().filter { it.optionalText("operation") == "roadmap.deliver" }
                val failure = deliveryEvents.single { it.text("event") == "initial_roadmap_prepare_failed" }
                val retry = deliveryEvents.single { it.text("stage") == "prepare_retry" }
                assertEquals("retry_wait", retry.text("outcome"))
                assertEquals(failure.text("error_id"), retry.text("upstream_error_id"))
                assertEquals(listOf(1L, 2L), deliveryEvents.filter { it.text("stage") == "claim" }.map { it.number("attempt") })
                val unknownDelivery = deliveryEvents.single { it.text("event") == "initial_roadmap_delivery_unknown" }
                assertEquals("unknown", unknownDelivery.text("outcome"))
                assertEquals("telegram_response_unconfirmed", unknownDelivery.text("reason_code"))
                assertEquals(roadmapJob.text("id"), unknownDelivery.text("source_job_id"))
                assertEquals(roadmapJob.number("fence"), unknownDelivery.number("source_job_fence"))
            }
            assertEquals(2, preparations)
            assertEquals(1, sends)
            s.database.transaction(readOnly = true) { c, _ ->
                val lineage = c.rows("""SELECT d.status,d.lesson_id,generation.id,d.source_job_fence,
                    assessment.id,observation.id,attempt.outcome
                    FROM initial_roadmap_delivery d
                    JOIN foundation_job generation ON generation.id=d.source_job_id
                    JOIN foundation_job_attempt attempt ON attempt.job_id=generation.id AND attempt.fence=d.source_job_fence
                    JOIN foundation_job assessment ON assessment.id=generation.source_job_id
                    JOIN foundation_job observation ON observation.id=assessment.source_job_id
                    WHERE d.student_id=?""", l.text("student_id")) { row ->
                    (1..7).map(row::getString)
                }.single()
                assertEquals(listOf("unknown", l.text("id"), roadmapJob.text("id"), roadmapJob.number("fence").toString(),
                    assess.text("id"), observe.text("id"), "succeeded"), lineage)
            }
            items.forEach { item ->
                val row = item.jsonObject
                s.op(operator, "learning.observation.invalidate", json("observationId" to row.text("id"),
                    "expectedRevision" to row.number("revision"), "component" to "trial-source-correction",
                    "reason" to "Corrected source no longer supports the observation"))
            }
            s.database.transaction { c, now -> c.enqueueJob(l.text("student_id"), "trial.assess",
                "fixture-reassessment", l.text("id"), json("transcriptRevision" to assess.obj("input_refs").number("transcriptRevision"),
                    "observationJobId" to observe.text("id"), "catalogVersion" to catalog.version), now) }
            val reassess = s.op(operator, "job.claim", json("kind" to "trial.assess")).obj("job")
            val reset = json("schemaVersion" to 1, "catalogVersion" to catalog.version,
                "baseLearningRevision" to state.number("revision"), "observationRefs" to JsonArray(emptyList()),
                "levels" to json("overall" to unknown, "speaking" to unknown, "listening" to unknown,
                    "grammar" to unknown, "vocabulary" to unknown), "topics" to JsonArray(listOf(json(
                    "topicId" to grammarTopic, "status" to "Unknown",
                    "explanation" to "Earlier Trial evidence is no longer active", "observationRefs" to JsonArray(emptyList())))),
                "coverage" to json("active" to 0, "usableWithoutTopic" to 0, "uncertain" to 0, "nonIndependent" to 0))
            assertEquals("succeeded", s.op(operator, "job.apply", json("jobId" to reassess.text("id"),
                "fence" to reassess.number("fence"), "result" to reset)).text("status"))
            assertEquals("Unknown", s.learningState(operator).obj("profile").obj("currentLevels").obj("speaky")
                .obj("skills").obj("grammar").text("level"))
            assertEquals("Unknown", s.topicKnowledge(operator, catalog.version, grammarTopic, 0, false)
                ["items"]!!.jsonArray.single().jsonObject.text("status"))
            assertEquals(2, s.topicKnowledge(operator, catalog.version, grammarTopic, 0, true)["items"]!!.jsonArray.size)
            val regenerated = s.op(operator, "job.claim", json("kind" to "roadmap.generate")).obj("job")
            assertEquals(s.learningState(operator).number("revision"), regenerated.obj("input_refs").number("learningRevision"))
            val revised = JsonObject(proposal + json("sequence" to JsonArray(listOf(
                JsonObject(candidate + json("reason" to "Reassessment kept this provisional."))))))
            assertEquals("succeeded", s.op(operator, "job.apply", json("jobId" to regenerated.text("id"),
                "fence" to regenerated.number("fence"), "result" to revised)).text("status"))
            assertEquals(2, s.learningRoadmap(operator).obj("roadmap").number("revision"))
            assertEquals(1, s.learningRoadmapHistory(operator, 0)["items"]!!.jsonArray.size)
            s.database.transaction(readOnly = true) { c, _ ->
                val previous = c.document("SELECT to_jsonb(h) FROM learning_roadmap_history h WHERE student_id=? AND revision=1",
                    l.text("student_id"))!!
                assertEquals(roadmapJob.text("id"), previous.text("generation_job_id"))
                assertEquals(roadmapJob.number("fence"), previous.number("generation_fence"))
            }
            assertEquals(1, s.learningTasks(operator, null, null, null, 0)["items"]!!.jsonArray.size)
        }
    }

    @Test fun assessmentLogsWhyRoadmapWasSkippedWithoutEnqueueing() = runTest {
        store { s, clock ->
            val catalog = catalogFixture()
            LearningCatalog(s.database).import(LearningCatalogModel.document(catalog).toString())
            LearningCatalog(s.database).publish(catalog.version, "synthetic", "test fixture only")
            val empty = JsonArray(emptyList())
            val unknown = json("level" to "Unknown", "explanation" to "Insufficient evidence", "observationRefs" to empty)
            val cases = listOf(
                Triple("102", json("targetLevel" to "B2"), "missing_goal"),
                Triple("103", json(), "missing_target_level"),
                Triple("104", json(), "missing_goal_and_target_level"),
            )
            for ((subject, profile, expectedReason) in cases) {
                val l = lesson(s, subject, clock.value)
                val a = open(s, subject, l.text("id"), clock.value)
                ready(s, l, a)
                val source = s.op(a.text("scope"), "voice.append", JsonObject(voice(l, a) + json("sourceId" to "u1",
                    "role" to "user", "body" to "I work here", "occurredAt" to clock.value.toString(),
                    "metadata" to json("finality" to "final"))))
                s.op(a.text("scope"), "voice.end", JsonObject(voice(l, a) + json("reason" to "end_button")))
                val operator = s.identity.issue(l.text("student_id"), "operator")
                if (expectedReason != "missing_goal_and_target_level") {
                    val args = json("expectedRevision" to 0, "patch" to profile, "component" to "onboarding",
                        "sources" to JsonArray(listOf(json("type" to "event", "id" to source.text("eventId"),
                            "revision" to source.number("sequence")))))
                    val update = if (expectedReason == "missing_target_level") JsonObject(args + json("goal" to "Speak at work"))
                    else args
                    s.op(operator, "learning.update", update)
                }
                val observe = s.op(operator, "job.claim", json("kind" to "trial.observe")).obj("job")
                assertEquals("succeeded", s.op(operator, "job.apply", json("jobId" to observe.text("id"),
                    "fence" to observe.number("fence"), "result" to json("schemaVersion" to 1, "observations" to empty)))
                    .text("status"))
                val assessment = s.op(operator, "job.claim", json("kind" to "trial.assess")).obj("job")
                val result = json("schemaVersion" to 1, "catalogVersion" to catalog.version,
                    "baseLearningRevision" to s.learningState(operator).number("revision"), "observationRefs" to empty,
                    "levels" to json("overall" to unknown, "speaking" to unknown, "listening" to unknown,
                        "grammar" to unknown, "vocabulary" to unknown), "topics" to empty,
                    "coverage" to json("active" to 0, "usableWithoutTopic" to 0, "uncertain" to 0, "nonIndependent" to 0))
                val apply = json("jobId" to assessment.text("id"), "fence" to assessment.number("fence"), "result" to result)
                DiagnosticOutput().use { logs ->
                    repeat(2) {
                        assertEquals("succeeded", s.op(operator, "job.apply", apply, "assessment-once").text("status"))
                    }
                    val skip = logs.events().single { it.text("event") == "trial_roadmap_decision" }
                    assertEquals("skipped", skip.text("outcome"))
                    assertEquals(expectedReason, skip.text("reason_code"))
                    assertEquals(l.text("id"), skip.text("lesson_id"))
                    assertEquals(assessment.text("id"), skip.text("source_job_id"))
                    assertTrue(logs.events().none { it.optionalText("job_kind") == "roadmap.generate" })
                }
                assertEquals(kotlinx.serialization.json.JsonNull,
                    s.op(operator, "job.claim", json("kind" to "roadmap.generate"))["job"])
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
