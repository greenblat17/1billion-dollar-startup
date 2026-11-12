package com.eliteteam.speakingcoach.foundation

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.server.testing.testApplication
import com.eliteteam.speakingcoach.speakyModule
import com.eliteteam.speakingcoach.AppConfig
import java.sql.DriverManager
import java.time.Instant
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FoundationIntegrationTest {
    private suspend fun withStore(clock: Clock = Clock.systemUTC(), block: suspend (FoundationService) -> Unit) {
        val raw = requireNotNull(System.getenv("TEST_POSTGRES_URL")) { "TEST_POSTGRES_URL is required; SP-00 integration tests cannot skip PostgreSQL" }
        val url = if (raw.startsWith("jdbc:")) raw else "jdbc:$raw"
        val schema = "sp00_${newId().replace("-", "")}"
        DriverManager.getConnection(url).use { it.createStatement().use { sql -> sql.execute("CREATE SCHEMA $schema") } }
        val scoped = url + (if ('?' in url) "&" else "?") + "currentSchema=$schema"
        try {
            FoundationService(FoundationDatabase(scoped, clock)).use { block(it) }
        } finally {
            DriverManager.getConnection(url).use { it.createStatement().use { sql -> sql.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }

    private suspend fun FoundationService.op(token: String, action: String, args: JsonObject, id: String = newId()): JsonObject =
        command(token, json("version" to 1, "operationId" to id, "action" to action, "args" to args))

    private suspend fun FoundationService.goal(student: String, value: String, revision: Long): JsonObject {
        val request = newId()
        val input = history.accept(student, request, value, Instant.now(), false)
        val token = identity.issue(student, "text", request)
        op(token, "run.claim", json())
        val result = op(token, "memory.record", json("sourceId" to input.text("event_id"), "evidence" to value,
            "category" to "learning", "key" to "goal", "value" to value, "kind" to "user_report", "expectedVersion" to revision))
        op(token, "run.complete", json("reply" to "saved", "degraded" to false))
        return result
    }

    @Test
    fun cutoverRejectsCorruptManifestAndCompletesOnlyAfterAllTutorImports() = runTest {
        withStore { service ->
            val state = json("learnerId" to "tg-100090", "conversationId" to "import-cutover-test", "revision" to 3,
                "bookings" to JsonArray(emptyList()), "personal" to json(), "learning" to json())
            val entry = json("sourceKey" to "tutor:v1:tg-100090", "subject" to "100090", "stateJson" to state.toString(), "sha256" to digest(state.toString()))
            fun manifest(item: JsonObject) = json("schemaVersion" to 1, "sourceFrozenAt" to "2026-01-01T00:00:00Z", "entries" to JsonArray(listOf(item))).toString()
            val cutover = TutorCutover(service)
            assertEquals("import_checksum_mismatch", assertFailsWith<FoundationFailure> {
                cutover.apply(manifest(JsonObject(entry + json("sha256" to "invalid"))))
            }.code)
            service.database.transaction(readOnly = true) { c, _ -> assertEquals(0L, c.rows("SELECT count(*) FROM foundation_cutover") { it.getLong(1) }.single()) }
            val raw = manifest(entry)
            // Simulate an interrupted process after its student receipt committed, before the marker.
            TutorStateImport(service).import("100090", state)
            service.database.transaction(readOnly = true) { c, _ -> assertEquals(0L, c.rows("SELECT count(*) FROM foundation_cutover") { it.getLong(1) }.single()) }
            assertEquals(1, cutover.apply(raw))
            assertEquals(1, cutover.apply(raw))
            assertEquals("cutover_already_completed", assertFailsWith<FoundationFailure> { cutover.apply(raw + " ") }.code)
        }
    }

    @Test
    fun reminderTestAdapterRunsOnceAndRecordsItsDeliveryReceipt() = kotlinx.coroutines.runBlocking {
        withStore { service ->
            val student = service.identity.resolve("telegram", "100091")
            val operator = service.identity.issue(student, "operator")
            val now = Instant.now()
            val lesson = service.op(operator, "lesson.create", json("kind" to "regular", "launchMode" to "placeholder",
                "startsAt" to now.plusSeconds(3600).toString(), "durationMinutes" to 15, "timezone" to "UTC"))
            val request = json("lessonId" to lesson.text("id"), "expectedVersion" to 1,
                "dueAt" to now.toString(), "deadline" to now.plusSeconds(1000).toString())
            val job = service.op(operator, "reminder.request", request)
            var deliveries = 0
            val adapter = object : LearningComputer {
                override suspend fun compute(scope: String, job: JsonObject): JsonObject {
                    deliveries++
                    return json("outcome" to "sent", "receipt" to "local-test-delivery")
                }
            }
            val worker = FoundationWorker(service, adapter, mapOf("notification.reminder" to adapter))
            assertTrue(worker.runOnce())
            assertEquals(job, service.op(operator, "reminder.request", request))
            assertEquals(false, worker.runOnce())
            assertEquals(1, deliveries)
            service.database.transaction(readOnly = true) { c, _ ->
                assertEquals("sent", c.document("SELECT to_jsonb(d) FROM reminder_delivery d WHERE job_id=?", job.text("jobId"))!!.text("outcome"))
            }
        }
    }

    @Test
    fun realBackupRestoreReplaysDeletionLedgerBeforeServingContext() = kotlinx.coroutines.runBlocking {
        withStore { service ->
            val student = service.identity.resolve("telegram", "100092")
            service.goal(student, "private backup goal", 0)
            val schema = service.database.transaction(readOnly = true) { c, _ -> c.rows("SELECT current_schema()") { it.getString(1) }.single() }
            check(schema.matches(Regex("sp00_[a-z0-9]+")))
            val raw = requireNotNull(System.getenv("TEST_POSTGRES_URL")).removePrefix("jdbc:")
            val backup = java.nio.file.Files.createTempFile("sp00-restore-", ".dump")
            fun pg(tool: String, vararg args: String) {
                val mac = java.nio.file.Path.of("/Applications/Postgres.app/Contents/Versions/latest/bin/$tool")
                val executable = System.getenv(tool.uppercase()) ?: if (java.nio.file.Files.isExecutable(mac)) mac.toString() else tool
                val builder = ProcessBuilder(listOf(executable, "--dbname=$raw") + args)
                builder.environment()["PGDATABASE"] = raw
                builder.redirectErrorStream(true)
                val process = builder.start()
                val output = process.inputStream.bufferedReader().readText()
                check(process.waitFor() == 0) { "$tool failed: $output" }
            }
            try {
                pg("pg_dump", "--format=custom", "--schema=$schema", "--file=$backup")
                val operator = service.identity.issue(student, "operator")
                service.op(operator, "memory.forget", json("category" to "learning", "key" to "goal", "expectedVersion" to 1))
                val ledger = service.database.transaction(readOnly = true) { c, _ -> c.documents("SELECT to_jsonb(d) FROM foundation_deletion d WHERE student_id=?", student) }
                service.close()
                DriverManager.getConnection("jdbc:$raw").use { connection ->
                    connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
                }
                pg("pg_restore", "--no-owner", backup.toString())
                val scoped = "jdbc:$raw" + (if ('?' in raw) "&" else "?") + "currentSchema=$schema"
                FoundationService(FoundationDatabase(scoped)).use { restored ->
                    restored.database.transaction { c, _ ->
                        ledger.forEach { record -> c.update("INSERT INTO foundation_deletion SELECT * FROM jsonb_populate_record(NULL::foundation_deletion,?::jsonb)", record) }
                    }
                    val recovery = restored.identity.issue(student, "operator")
                    assertEquals(1L, restored.op(recovery, "deletion.replay", json()).number("replayed"))
                    val fresh = restored.identity.issue(student, "operator")
                    assertEquals(json(), restored.snapshot(fresh, "text").obj("goal").obj("content"))
                    assertTrue(restored.snapshot(fresh, "text")["events"]!!.jsonArray.isEmpty())
                    assertEquals(0L, restored.op(fresh, "deletion.replay", json()).number("replayed"))
                }
            } finally {
                java.nio.file.Files.deleteIfExists(backup)
            }
        }
    }

    @Test
    fun scheduleHandlerMaterializesOneOccurrenceAndCannotResurrectACancellation() = kotlinx.coroutines.runBlocking {
        withStore { service ->
            val student = service.identity.resolve("telegram", "100095")
            val operator = service.identity.issue(student, "operator")
            val starts = Instant.now().plusSeconds(3600)
            val schedule = service.op(operator, "schedule.create", json("timezone" to "UTC", "effectiveFrom" to starts.toString(), "agreement" to json("frequency" to "explicit")))
            val request = json("scheduleId" to schedule.text("id"), "expectedVersion" to 1, "from" to starts.toString(), "until" to starts.plusSeconds(86400).toString())
            val job = service.op(operator, "schedule.materialize.request", request)
            assertEquals(job, service.op(operator, "schedule.materialize.request", request))
            val result = json("occurrences" to JsonArray(listOf(json("key" to "one", "startsAt" to starts.toString(), "durationMinutes" to 15))))
            var calls = 0
            val handler = object : LearningComputer {
                override suspend fun compute(scope: String, job: JsonObject): JsonObject {
                    calls++
                    assertEquals(schedule.text("id"), job.text("target_id"))
                    return result
                }
            }
            val unused = object : LearningComputer {
                override suspend fun compute(scope: String, job: JsonObject): JsonObject = error("unexpected model call")
            }
            assertTrue(FoundationWorker(service, unused, mapOf("schedule.materialize" to handler)).runOnce())
            assertEquals(1, calls)
            val lesson = service.database.transaction(readOnly = true) { c, _ -> c.document("SELECT to_jsonb(l) FROM lesson l WHERE schedule_id=?", schedule.text("id"))!! }
            service.op(operator, "lesson.cancel", json("lessonId" to lesson.text("id"), "expectedVersion" to 1))
            val second = JsonObject(request + json("until" to starts.plusSeconds(2 * 86400).toString()))
            service.op(operator, "schedule.materialize.request", second)
            val claimed = service.op(operator, "job.claim", json("kind" to "schedule.materialize")).obj("job")
            service.op(operator, "job.apply", json("jobId" to claimed.text("id"), "fence" to claimed.number("fence"), "result" to result))
            service.database.transaction(readOnly = true) { c, _ ->
                assertEquals(listOf("cancelled"), c.rows("SELECT status FROM lesson WHERE schedule_id=?", schedule.text("id")) { it.getString(1) })
            }
        }
    }

    @Test
    fun reminderContractSkipsCancelledLessonsAndNeverResendsUnknownDelivery() = runTest {
        val clock = MutableClock()
        withStore(clock) { service ->
            val student = service.identity.resolve("telegram", "100094")
            val operator = service.identity.issue(student, "operator")
            val lesson = service.op(operator, "lesson.create", json("kind" to "regular", "launchMode" to "placeholder",
                "startsAt" to clock.instant().plusSeconds(3600).toString(), "durationMinutes" to 15, "timezone" to "UTC"))
            val requested = service.op(operator, "reminder.request", json("lessonId" to lesson.text("id"), "expectedVersion" to 1,
                "dueAt" to clock.instant().toString(), "deadline" to clock.instant().plusSeconds(1000).toString()))
            val claimed = service.op(operator, "job.claim", json("kind" to "notification.reminder")).obj("job")
            assertEquals(requested.text("jobId"), claimed.text("id"))
            clock.current = clock.current.plusSeconds(61)
            assertEquals(kotlinx.serialization.json.JsonNull, service.op(operator, "job.claim", json("kind" to "notification.reminder"))["job"])
            service.database.transaction(readOnly = true) { c, _ ->
                assertEquals("delivery_unknown", c.document("SELECT to_jsonb(j) FROM foundation_job j WHERE id=?", claimed.text("id"))!!.text("error_code"))
            }
            service.op(operator, "lesson.cancel", json("lessonId" to lesson.text("id"), "expectedVersion" to 1))
            assertEquals("version_conflict", assertFailsWith<FoundationFailure> {
                service.op(operator, "reminder.request", json("lessonId" to lesson.text("id"), "expectedVersion" to 1,
                    "dueAt" to clock.instant().toString(), "deadline" to clock.instant().plusSeconds(1000).toString()))
            }.code)
        }
    }

    @Test
    fun isolatedShippingRuntimeRequiresCutoverAndRegistersNoLegacyRoutes() = kotlinx.coroutines.runBlocking {
        withStore { service ->
            val manifest = json("schemaVersion" to 1, "sourceFrozenAt" to Instant.now().minusSeconds(1).toString(), "entries" to JsonArray(emptyList())).toString()
            assertEquals(0, TutorCutover(service).apply(manifest))
            assertEquals(0, TutorCutover(service).apply(manifest))
            val schema = service.database.transaction(readOnly = true) { c, _ -> c.rows("SELECT current_schema()") { it.getString(1) }.single() }
            val raw = requireNotNull(System.getenv("TEST_POSTGRES_URL"))
            val url = raw + (if ('?' in raw) "&" else "?") + "currentSchema=$schema"
            val config = AppConfig(null, null, null, "http://127.0.0.1:1", "local-test", 0, "", "", null, url)
            testApplication {
                application { speakyModule(config) }
                assertEquals(200, client.get("/health").status.value)
                for (path in listOf("/internal/foundation/v1/legacy/calls", "/internal/foundation/v1/legacy/feedback", "/admin/metrics", "/internal/calls/open")) {
                    assertEquals(404, client.post(path).status.value)
                }
                assertEquals(401, client.post("/internal/foundation/v1/commands").status.value)
            }
        }
    }

    @Test
    fun workerRenewalExtendsOnlyItsLiveFencedCapability() = runTest {
        val clock = MutableClock()
        withStore(clock) { service ->
            val student = service.identity.resolve("telegram", "100098")
            val operator = service.identity.issue(student, "operator")
            service.op(operator, "lesson.create", json("kind" to "trial", "launchMode" to "executable",
                "startsAt" to clock.instant().toString(), "durationMinutes" to 15, "timezone" to "UTC"))
            val job = service.op(operator, "job.claim", json("kind" to "lesson.prepare")).obj("job")
            val capability = service.identity.issueJob(job)
            clock.current = clock.current.plusSeconds(50)
            service.op(capability, "job.renew", json("jobId" to job.text("id"), "fence" to job.number("fence")))
            clock.current = clock.current.plusSeconds(20)
            service.op(capability, "job.apply", json("jobId" to job.text("id"), "fence" to job.number("fence"),
                "result" to json("objective" to "practice", "topic" to "travel", "strategy" to "guided conversation")))
            assertEquals("stale_job", assertFailsWith<FoundationFailure> {
                service.op(capability, "job.renew", json("jobId" to job.text("id"), "fence" to job.number("fence")))
            }.code)
        }
    }

    @Test
    fun forgettingGoalErasesHistoricalRevisionsAndInvalidatesOldScopes() = runTest {
        withStore { service ->
            val student = service.identity.resolve("telegram", "100099")
            service.goal(student, "private work objective", 0)
            service.goal(student, "new private objective", 1)
            val operator = service.identity.issue(student, "operator")
            assertEquals("version_conflict", assertFailsWith<FoundationFailure> {
                service.op(operator, "memory.forget", json("category" to "learning", "key" to "goal", "expectedVersion" to 1))
            }.code)
            service.op(operator, "memory.forget", json("category" to "learning", "key" to "goal", "expectedVersion" to 2))
            assertEquals("scope_expired", assertFailsWith<FoundationFailure> { service.snapshot(operator, "text") }.code)
            service.database.transaction { connection, _ ->
                assertEquals(3L, connection.goalRevision(student))
                assertEquals(0L, connection.rows("SELECT count(*) FROM learning_goal WHERE student_id=? AND (content <> '{}'::jsonb OR source_event_id IS NOT NULL)", student) { it.getLong(1) }.single())
            }
            service.goal(student, "explicit new goal", 3)
            val fresh = service.identity.issue(student, "operator")
            assertEquals("explicit new goal", service.snapshot(fresh, "text").obj("goal").obj("content").text("value"))
        }
    }

    @Test
    fun chatGoalAndLessonObservationShareStateWithoutAllowingAnOldJobToRewriteTheGoal() = runTest {
        withStore { service ->
            val student = service.identity.resolve("telegram", "1001")
            service.goal(student, "work meetings", 0)
            val operator = service.identity.issue(student, "operator")
            val lesson = service.op(operator, "lesson.create", json("kind" to "trial", "launchMode" to "executable",
                "startsAt" to Instant.now().toString(), "durationMinutes" to 15, "timezone" to "UTC"))
            val lessonId = lesson.text("id")
            val voice = service.identity.issue(student, "voice", lesson = lessonId)
            assertEquals("work meetings", service.snapshot(voice, "voice", lessonId).obj("goal").obj("content").text("value"))
            val prepare = service.op(operator, "job.claim", json("kind" to "lesson.prepare")).obj("job")
            service.op(operator, "job.apply", json("jobId" to prepare.text("id"), "fence" to prepare.number("fence"),
                "result" to json("objective" to "speak about work", "topic" to "meetings", "strategy" to "guided conversation")))
            assertEquals("ready", service.snapshot(voice, "voice", lessonId).obj("preparation").text("status"))
            val episode = service.op(operator, "lesson.episode", json("lessonId" to lessonId, "attemptId" to "attempt-1"))
            val append = json("lessonId" to lessonId, "callSessionId" to episode.text("id"), "sourceId" to "audio-1",
                "role" to "user", "body" to "Yesterday I go to work", "occurredAt" to Instant.now().toString())
            val event = service.op(operator, "lesson.append", append)
            val repeat = service.op(operator, "lesson.append", append)
            assertEquals(event.text("eventId"), repeat.text("eventId"))
            service.op(operator, "lesson.end_episode", json("lessonId" to lessonId, "callSessionId" to episode.text("id"), "reason" to "technical_disconnect"))
            val second = service.op(operator, "lesson.episode", json("lessonId" to lessonId, "attemptId" to "attempt-2"))
            assertNotEquals(episode.text("id"), second.text("id"))
            service.op(operator, "lesson.end_episode", json("lessonId" to lessonId, "callSessionId" to second.text("id"), "reason" to "natural_goodbye"))
            val current = service.snapshot(operator, "voice", lessonId).obj("lesson")
            service.op(operator, "lesson.complete", json("lessonId" to lessonId, "expectedVersion" to current.number("version"), "reason" to "observed_completion"))
            val evaluation = service.op(operator, "job.claim", json("kind" to "lesson.evaluate")).obj("job")
            service.goal(student, "relocation", 1)
            val result = json("status" to "evaluated", "observations" to JsonArray(listOf(json("key" to "past-tense", "dimension" to "grammar",
                "content" to "Past tense needs practice", "uncertainty" to "limited", "sourceId" to event.text("eventId")))))
            val apply = json("jobId" to evaluation.text("id"), "fence" to evaluation.number("fence"), "result" to result)
            service.op(operator, "job.apply", apply, "apply-evaluation")
            service.op(operator, "job.apply", apply, "apply-evaluation")
            val chat = service.snapshot(operator, "text")
            assertEquals("relocation", chat.obj("goal").obj("content").text("value"))
            assertEquals(1, chat["observations"]!!.jsonArray.size)
        }
    }

    @Test
    fun capabilitiesVersionsAndReceiptsPreventCrossStudentWritesAndRepeatedChanges() = runTest {
        withStore { service ->
            val alice = service.identity.resolve("telegram", "2001")
            val bob = service.identity.resolve("telegram", "2002")
            val token = service.identity.issue(alice, "operator")
            val other = service.identity.issue(bob, "operator")
            val create = json("kind" to "trial", "launchMode" to "placeholder", "startsAt" to Instant.now().plusSeconds(600).toString(),
                "durationMinutes" to 15, "timezone" to "UTC")
            val lesson = service.op(token, "lesson.create", create, "create")
            assertEquals(lesson, service.op(token, "lesson.create", create, "create"))
            assertEquals("not_owned", assertFailsWith<FoundationFailure> { service.snapshot(other, "voice", lesson.text("id")) }.code)
            assertEquals("operation_conflict", assertFailsWith<FoundationFailure> {
                service.op(token, "lesson.create", JsonObject(create + ("durationMinutes" to kotlinx.serialization.json.JsonPrimitive(30))), "create")
            }.code)
            assertEquals("version_conflict", assertFailsWith<FoundationFailure> {
                service.op(token, "lesson.cancel", json("lessonId" to lesson.text("id"), "expectedVersion" to 999))
            }.code)
            assertEquals("scope_expired", assertFailsWith<FoundationFailure> { service.snapshot("forged", "text") }.code)
        }
    }

    @Test
    fun personalCandidatesRemainHiddenUntilAUserConfirmsTheirSource() = runTest {
        withStore { service ->
            val student = service.identity.resolve("telegram", "3001")
            val input = service.history.accept(student, "1", "I visited Paris", Instant.now(), false)
            val firstTurn = service.identity.issue(student, "text", "1")
            service.op(firstTurn, "run.claim", json())
            service.op(firstTurn, "run.complete", json("reply" to "Understood", "degraded" to false))
            val operator = service.identity.issue(student, "operator")
            val candidate = service.op(operator, "memory.propose", json("sourceId" to input.text("event_id"), "key" to "likes_travel", "value" to "likes travel"))
            assertTrue(service.snapshot(operator, "text")["facts"]!!.jsonArray.isEmpty())
            val confirmation = service.history.accept(student, "2", "Yes, I like travelling", Instant.now(), false)
            val text = service.identity.issue(student, "text", "2")
            service.op(text, "run.claim", json())
            service.op(text, "memory.confirm", json("candidateId" to candidate.text("id"), "expectedVersion" to 1,
                "sourceId" to confirmation.text("event_id"), "evidence" to "Yes, I like travelling"))
            assertEquals(1, service.snapshot(text, "text")["facts"]!!.jsonArray.size)
        }
    }
    @Test
    fun expiredWorkerCannotCommitAndRetentionDoesNotRenewSourceDeadlines() = runTest {
        val clock = MutableClock()
        withStore(clock) { service ->
            val student = service.identity.resolve("telegram", "4001")
            var operator = service.identity.issue(student, "operator")
            service.op(operator, "lesson.create", json("kind" to "trial", "launchMode" to "executable",
                "startsAt" to clock.instant().toString(), "durationMinutes" to 15, "timezone" to "UTC"))
            val first = service.op(operator, "job.claim", json()).obj("job")
            clock.current = clock.current.plusSeconds(61)
            val second = service.op(operator, "job.claim", json()).obj("job")
            assertEquals(first.text("id"), second.text("id"))
            assertTrue(second.number("fence") > first.number("fence"))
            assertEquals("stale_job", assertFailsWith<FoundationFailure> {
                service.op(operator, "job.apply", json("jobId" to first.text("id"), "fence" to first.number("fence"),
                    "result" to json("objective" to "speak", "topic" to "travel", "strategy" to "conversation")))
            }.code)
            val input = service.history.accept(student, "raw-message", "I enjoy travel", clock.instant(), false)
            service.op(operator, "memory.record", json("sourceId" to input.text("event_id"), "evidence" to "travel", "category" to "personal",
                "key" to "interest", "value" to "travel", "kind" to "user_report", "expectedVersion" to 0))
            clock.current = clock.current.plusSeconds(HISTORY_SECONDS + 1)
            operator = service.identity.issue(student, "operator")
            service.op(operator, "retention.prune", json())
            val context = service.snapshot(operator, "text")
            assertTrue(context["events"]!!.jsonArray.isEmpty())
            assertEquals("travel", context["facts"]!!.jsonArray.single().jsonObject.text("value"))
            service.op(operator, "memory.forget", json("category" to "personal", "key" to "interest", "expectedVersion" to 1))
            assertEquals("scope_expired", assertFailsWith<FoundationFailure> { service.snapshot(operator, "text") }.code)
            val fresh = service.identity.issue(student, "operator")
            assertTrue(service.snapshot(fresh, "text")["facts"]!!.jsonArray.isEmpty())
        }
    }

    @Test
    fun confirmedPaymentIsIdempotentAndDoesNotOwnTheSchedule() = runTest {
        withStore { service ->
            val student = service.identity.resolve("telegram", "5001")
            val scope = service.identity.issue(student, "operator")
            val schedule = service.op(scope, "schedule.create", json("timezone" to "Europe/Moscow",
                "effectiveFrom" to Instant.now().toString(), "agreement" to json("days" to "Monday")))
            val create = json("kind" to "regular", "launchMode" to "executable", "startsAt" to Instant.now().plusSeconds(3600).toString(),
                "durationMinutes" to 30, "timezone" to "Europe/Moscow", "scheduleId" to schedule.text("id"), "occurrenceKey" to "first")
            val lesson = service.op(scope, "lesson.create", create)
            service.op(scope, "lesson.cancel", json("lessonId" to lesson.text("id"), "expectedVersion" to 1))
            assertEquals("cancelled", service.op(scope, "lesson.create", create).text("status"))
            val payment = json("provider" to "test", "externalId" to newId(), "validFrom" to Instant.now().toString(),
                "validUntil" to Instant.now().plusSeconds(86400).toString())
            val first = service.op(scope, "access.confirm_payment", payment)
            assertEquals(first, service.op(scope, "access.confirm_payment", payment))
            assertEquals("cancelled", service.snapshot(scope, "voice", lesson.text("id")).obj("lesson").text("status"))
        }
    }

    @Test
    fun workerUsesBoundCapabilityAndCommitsPreparationOutsideTheClaimTransaction() = kotlinx.coroutines.runBlocking {
        withStore { service ->
            val student = service.identity.resolve("telegram", "6001")
            val operator = service.identity.issue(student, "operator")
            val lesson = service.op(operator, "lesson.create", json("kind" to "trial", "launchMode" to "executable",
                "startsAt" to Instant.now().toString(), "durationMinutes" to 15, "timezone" to "UTC"))
            var calls = 0
            val worker = FoundationWorker(service, object : LearningComputer {
                override suspend fun compute(scope: String, job: JsonObject): JsonObject {
                    calls++
                    assertEquals(lesson.text("id"), service.snapshot(scope, "learning", lesson.text("id")).obj("lesson").text("id"))
                    assertEquals("not_owned", assertFailsWith<FoundationFailure> { service.snapshot(scope, "learning") }.code)
                    assertEquals("not_permitted", assertFailsWith<FoundationFailure> { service.op(scope, "job.claim", json()) }.code)
                    return json("objective" to "practice", "topic" to "work", "strategy" to "guided conversation")
                }
            })
            assertTrue(worker.runOnce())
            assertEquals(false, worker.runOnce())
            assertEquals(1, calls)
            assertEquals("ready", service.snapshot(operator, "voice", lesson.text("id")).obj("preparation").text("status"))
        }
    }

    @Test
    fun workerRenewsDuringAComputationLongerThanTheOriginalLease() = kotlinx.coroutines.runBlocking {
        withStore { service ->
            val student = service.identity.resolve("telegram", "6002")
            val operator = service.identity.issue(student, "operator")
            val lesson = service.op(operator, "lesson.create", json("kind" to "trial", "launchMode" to "executable",
                "startsAt" to Instant.now().toString(), "durationMinutes" to 15, "timezone" to "UTC"))
            val worker = FoundationWorker(service, object : LearningComputer {
                override suspend fun compute(scope: String, job: JsonObject): JsonObject {
                    kotlinx.coroutines.delay(65_000)
                    // The initial capability/lease expired at 60 seconds without actual worker renewal.
                    service.snapshot(scope, "learning", lesson.text("id"))
                    return json("objective" to "practice", "topic" to "work", "strategy" to "guided conversation")
                }
            })
            assertTrue(worker.runOnce())
            assertEquals("ready", service.snapshot(operator, "voice", lesson.text("id")).obj("preparation").text("status"))
            service.database.transaction(readOnly = true) { c, _ ->
                assertEquals(1L, c.rows("SELECT attempts FROM foundation_job WHERE target_id=?", lesson.text("id")) { it.getLong(1) }.single())
                assertTrue(c.rows("SELECT count(*) FROM foundation_operation WHERE student_id=? AND action='job.renew'", student) { it.getLong(1) }.single() >= 2)
            }
        }
    }

    @Test
    fun telegramBridgePersistsInputAndDeliveryAndRejectsAGroupIdentity() = runTest {
        withStore { service ->
            val bridge = FoundationChat(service)
            val request = com.eliteteam.speakingcoach.ai.TutorRequest("tg-7001", "message:1", "Hello", Instant.now().epochSecond.toDouble())
            val scope = bridge.accept(request, false)
            assertEquals("Hello", service.op(scope, "run.claim", json()).obj("input").text("body"))
            val saved = service.op(scope, "run.complete", json("reply" to "Hello again", "degraded" to false))
            bridge.delivered(com.eliteteam.speakingcoach.ai.TutorDelivery(request.sessionId, request.requestId, replyMessageId = 42))
            val answer = bridge.withDelivery(request, com.eliteteam.speakingcoach.ai.TutorResponse(saved.text("conversationId"), saved.text("reply")))
            assertEquals(42L, answer.replyMessageId)
            val repeat = bridge.accept(request, false)
            assertEquals("ready", service.op(repeat, "run.claim", json()).text("status"))
            assertEquals("invalid_identity", assertFailsWith<FoundationFailure> { bridge.accept(request.copy(sessionId = "tg--123"), false) }.code)
        }
    }

    @Test
    fun deletionLedgerReappliesAfterRestoringOlderStudentState() = runTest {
        withStore { service ->
            val student = service.identity.resolve("telegram", "8001")
            val input = service.history.accept(student, "source", "I like tea", Instant.now(), false)
            val operator = service.identity.issue(student, "operator")
            service.op(operator, "memory.record", json("sourceId" to input.text("event_id"), "evidence" to "tea", "category" to "personal",
                "key" to "drink", "value" to "tea", "kind" to "user_report", "expectedVersion" to 0))
            service.op(operator, "memory.forget", json("category" to "personal", "key" to "drink", "expectedVersion" to 1))
            service.database.transaction { connection, _ ->
                // Restore the pre-deletion rows while retaining the separately recovered ledger.
                connection.update("UPDATE student SET data_epoch=0 WHERE id=?", student)
                connection.update("UPDATE memory_fact SET value='tea',forgotten_at=NULL,revision=1 WHERE student_id=?", student)
                connection.update("UPDATE conversation_event SET body='I like tea' WHERE id=?", input.text("event_id"))
            }
            val recovery = service.identity.issue(student, "operator")
            assertEquals(1L, service.op(recovery, "deletion.replay", json()).number("replayed"))
            val fresh = service.identity.issue(student, "operator")
            assertTrue(service.snapshot(fresh, "text")["facts"]!!.jsonArray.isEmpty())
            assertTrue(service.snapshot(fresh, "text")["events"]!!.jsonArray.isEmpty())
            assertEquals(0L, service.op(fresh, "deletion.replay", json()).number("replayed"))
            service.op(fresh, "student.delete", json())
            assertEquals("scope_expired", assertFailsWith<FoundationFailure> { service.snapshot(fresh, "text") }.code)
        }
    }

    @Test
    fun telegramIntakeIsDurableOrderedAndRecoverableAfterAnExpiredDispatch() = runTest {
        val clock = MutableClock()
        withStore(clock) { service ->
            fun envelope(update: Long, message: Long) = json("update_id" to update, "message" to json(
                "message_id" to message, "chat" to json("id" to 9001, "type" to "private"),
                "from" to json("id" to 9001), "text" to "Hello", "date" to clock.instant().epochSecond))
            val one = envelope(1, 1)
            assertTrue(service.telegramIntake.accept(one))
            assertTrue(service.telegramIntake.accept(one))
            service.telegramIntake.accept(envelope(2, 2))
            val recreated = TelegramTextIntake(service.database)
            assertEquals(1L, recreated.claim()!!.number("update_id"))
            assertEquals(null, recreated.claim())
            clock.current = clock.current.plusSeconds(181)
            assertEquals(1L, recreated.claim()!!.number("update_id"))
            recreated.complete(9001, 1)
            assertEquals(2L, recreated.claim()!!.number("update_id"))
            recreated.complete(9001, 2)
            assertEquals(null, recreated.claim())
        }
    }

    @Test
    fun uncertainTelegramDeliveryIsNotRetriedBlindly() = runTest {
        withStore { service ->
            val bridge = FoundationChat(service)
            val request = com.eliteteam.speakingcoach.ai.TutorRequest("tg-9002", "message:1", "Hello", Instant.now().epochSecond.toDouble())
            val scope = bridge.accept(request, false)
            service.op(scope, "run.claim", json())
            service.op(scope, "run.complete", json("reply" to "Hello", "degraded" to false))
            val delivery = com.eliteteam.speakingcoach.ai.TutorDelivery(request.sessionId, request.requestId)
            assertTrue(bridge.beginDelivery(delivery))
            assertEquals(false, FoundationChat(service).beginDelivery(delivery))
            bridge.delivered(delivery.copy(replyMessageId = 42))
            assertEquals(false, bridge.beginDelivery(delivery))
        }
    }

    @Test
    fun recoveredRunReturnsCommittedBookingInsteadOfRepeatingTheInstruction() = runTest {
        val clock = MutableClock()
        withStore(clock) { service ->
            val student = service.identity.resolve("telegram", "9101")
            val input = service.history.accept(student, "request", "Book tomorrow", clock.instant(), false)
            val first = service.identity.issue(student, "text", "request")
            service.op(first, "run.claim", json())
            val booking = service.op(first, "lesson.create", json("kind" to "trial", "launchMode" to "placeholder", "durationMinutes" to 15,
                "startsAt" to clock.instant().plusSeconds(86400).toString(), "timezone" to "UTC", "sourceId" to input.text("event_id"), "evidence" to "Book tomorrow"))
            clock.current = clock.current.plusSeconds(91)
            val recovered = service.identity.issue(student, "text", "request")
            val claim = service.op(recovered, "run.claim", json())
            assertEquals(booking.text("id"), claim["committed"]!!.jsonArray.single().jsonObject.obj("result").text("id"))
            assertEquals("stale_run", assertFailsWith<FoundationFailure> { service.op(first, "run.complete", json("reply" to "old", "degraded" to false)) }.code)
            service.op(recovered, "run.complete", json("reply" to "Booked", "bookingId" to booking.text("id"), "degraded" to true))
            assertEquals(booking.text("id"), service.snapshot(recovered, "text").obj("booking").text("id"))
        }
    }

}

private class MutableClock(var current: Instant = Instant.now()) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = current
}
