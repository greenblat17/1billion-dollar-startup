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
import kotlinx.coroutines.async
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
    private fun introEnvelope(chat: Long, message: Long, text: String, at: Instant) = json(
        "update_id" to (chat * 100 + message), "message" to json("message_id" to message,
            "chat" to json("id" to chat, "type" to "private"), "from" to json("id" to chat),
            "text" to text, "date" to at.epochSecond))

    private fun introRequest(chat: Long, message: Long, text: String, at: Instant) =
        com.eliteteam.speakingcoach.ai.TutorRequest("tg-$chat", "message:$message", text, at.epochSecond.toDouble())

    @Test
    fun firstStartDeliversFourSeparateMessagesAndOnlyConfirmedHistory() = runTest {
        val clock = MutableClock()
        withStore(clock) { service ->
            val request = introRequest(9200, 1, "/start", clock.instant())
            service.telegramIntake.accept(introEnvelope(9200, 1, "/start", clock.instant()))
            val bridge = FoundationChat(service)
            assertTrue(bridge.prepareIntroduction(request, 9200, 1))
            val sent = mutableListOf<String>()
            var typing = 0
            var paused = 0L
            com.eliteteam.speakingcoach.telegram.deliverIntroduction(bridge.introduction, 9200, request.requestId,
                typing = { typing++ }, send = { sent += it; sent.size.toLong() },
                pause = { paused += it; clock.current = clock.current.plusMillis(it) })
            assertEquals(listOf("Привет! 👋", "Меня зовут Саша. Я — твой личный ИИ-репетитор английского.",
                "Я помогаю людям учить английский, чтобы:\n💼 Найти работу\n🌍 Переехать за границу\n💬 Перестать бояться английского и наконец начать ГОВОРИТЬ",
                "Расскажи, пожалуйста, для чего ты изучаешь английский?"), sent)
            assertEquals(3, typing)
            assertEquals(2750L, paused)
            val student = service.identity.resolve("telegram", "9200")
            val scope = service.identity.issue(student, "operator")
            assertEquals(listOf("/start") + sent, service.snapshot(scope, "text")["events"]!!.jsonArray.map { it.jsonObject.text("body") })
            assertTrue(FoundationChat(service).prepareIntroduction(request, 9200, 1))
            assertEquals(null, TelegramIntroduction(service.database).next(9200, request.requestId))
            assertEquals("introduction", service.snapshot(scope, "text").text("textMode"))
            val reply = com.eliteteam.speakingcoach.ai.TutorResponse("test", "Ответ")
            assertEquals(false, bridge.withDelivery(request, reply).showProgressNavigation)
            val trial = service.op(scope, "lesson.create", json("kind" to "trial", "launchMode" to "executable",
                "startsAt" to clock.instant().plusSeconds(600).toString(), "durationMinutes" to 15, "timezone" to "UTC"))
            assertEquals("dialogue", service.snapshot(scope, "text").text("textMode"))
            service.op(scope, "lesson.cancel", json("lessonId" to trial.text("id"), "expectedVersion" to trial.number("version")))
            assertEquals("dialogue", service.snapshot(scope, "text").text("textMode"))
            assertEquals(true, bridge.withDelivery(request, reply).showProgressNavigation)
        }
    }

    @Test
    fun incomingReplyDuringAnyPauseCancelsRemainderAndProcessesExactlyOneModelResult() = runTest {
        val clock = MutableClock()
        withStore(clock) { service ->
            for (after in 1..3) {
                val chat = 9210L + after
                val firstAt = clock.instant()
                val request = introRequest(chat, 1, "/start", firstAt)
                val envelope = introEnvelope(chat, 1, "/start", firstAt)
                service.telegramIntake.accept(envelope)
                assertEquals(chat * 100 + 1, service.telegramIntake.claim()!!.number("update_id"))
                val bridge = FoundationChat(service)
                assertTrue(bridge.prepareIntroduction(request, chat, 1))
                var count = 0
                var interrupted = false
                val reply = introRequest(chat, 2, "Запиши меня на пробный сейчас", firstAt)
                com.eliteteam.speakingcoach.telegram.deliverIntroduction(bridge.introduction, chat, request.requestId,
                    typing = {}, send = { count++; count.toLong() }, pause = {
                        if (count == after && !interrupted) {
                            service.telegramIntake.accept(introEnvelope(chat, 2, reply.text, firstAt))
                            service.telegramIntake.accept(envelope) // replay of start must not undo cancellation
                            assertEquals(null, service.telegramIntake.claim()) // start still owns delivery order
                            interrupted = true
                        }
                        clock.current = clock.current.plusMillis(it)
                    })
                assertEquals(after, count)
                bridge.completeIntake(chat, 1)
                assertEquals(chat * 100 + 2, service.telegramIntake.claim()!!.number("update_id"))
                val scope = bridge.accept(reply, false)
                val claimed = service.op(scope, "run.claim", json())
                assertEquals(reply.text, claimed.obj("input").text("body"))
                val context = service.snapshot(scope, "text")["events"]!!.jsonArray.map { it.jsonObject.text("body") }
                assertEquals(listOf("/start") + TELEGRAM_INTRO_TEXTS.take(after) + reply.text, context)
                service.op(scope, "run.complete", json("reply" to "Давай начнём", "degraded" to false))
                val receipt = com.eliteteam.speakingcoach.ai.TutorDelivery(reply.sessionId, reply.requestId)
                assertTrue(bridge.beginDelivery(receipt))
                bridge.delivered(receipt.copy(replyMessageId = 99))
                val replay = FoundationChat(service).accept(reply, false)
                assertEquals("ready", service.op(replay, "run.claim", json()).text("status"))
                assertEquals(false, bridge.beginDelivery(receipt))
                bridge.completeIntake(chat, 2)
                assertEquals(null, service.telegramIntake.claim())
            }
        }
    }

    @Test
    fun replyAlreadyInDurableIntakeSuppressesIntroductionBeforeFirstSend() = runTest {
        withStore { service ->
            val at = Instant.now()
            service.telegramIntake.accept(introEnvelope(9220, 1, "/start", at))
            service.telegramIntake.accept(introEnvelope(9220, 2, "Мне нужен английский для работы", at))
            val bridge = FoundationChat(service)
            assertTrue(bridge.prepareIntroduction(introRequest(9220, 1, "/start", at), 9220, 1))
            com.eliteteam.speakingcoach.telegram.deliverIntroduction(bridge.introduction, 9220, "message:1",
                typing = { error("unexpected typing") }, send = { error("unexpected introduction") })
        }
    }

    @Test
    fun restartResumesUnclaimedPartsButNeverRepeatsAnUnknownSend() = runTest {
        val clock = MutableClock()
        withStore(clock) { service ->
            val request = introRequest(9230, 1, "/start", clock.instant())
            val bridge = FoundationChat(service)
            assertTrue(bridge.prepareIntroduction(request, 9230, 1))
            assertTrue(bridge.introduction.claim(9230, request.requestId, 0))
            bridge.introduction.sent(9230, request.requestId, 0, 41)
            bridge.introduction.sent(9230, request.requestId, 0, 41) // receipt replay
            val restarted = FoundationChat(service)
            assertTrue(restarted.prepareIntroduction(request, 9230, 1))
            clock.current = clock.current.plusSeconds(1)
            assertEquals(1, restarted.introduction.next(9230, request.requestId)!!.index)
            assertTrue(restarted.introduction.claim(9230, request.requestId, 1))
            assertEquals(false, bridge.introduction.claim(9230, request.requestId, 1))
            assertEquals("introduction_busy", assertFailsWith<FoundationFailure> {
                bridge.introduction.next(9230, request.requestId)
            }.code)
            // Process dies after the send claim (possibly after Telegram accepted the message).
            clock.current = clock.current.plusSeconds(181)
            com.eliteteam.speakingcoach.telegram.deliverIntroduction(TelegramIntroduction(service.database), 9230, request.requestId,
                typing = { error("unexpected typing") }, send = { error("must not resend") })
            val student = service.identity.resolve("telegram", "9230")
            val scope = service.identity.issue(student, "operator")
            assertEquals(listOf("/start", TELEGRAM_INTRO_TEXTS[0]),
                service.snapshot(scope, "text")["events"]!!.jsonArray.map { it.jsonObject.text("body") })
        }
    }

    @Test
    fun typingFailureDoesNotStopIntroductionAndSendFailureDoesNotRetryOrContinue() = runTest {
        val clock = MutableClock()
        withStore(clock) { service ->
            val bridge = FoundationChat(service)
            val request = introRequest(9240, 1, "/start", clock.instant())
            assertTrue(bridge.prepareIntroduction(request, 9240, 1))
            var sends = 0
            var typing = 0
            com.eliteteam.speakingcoach.telegram.deliverIntroduction(bridge.introduction, 9240, request.requestId,
                typing = { typing++; error("typing unavailable") },
                send = { sends++; if (sends == 3) error("send timed out") else sends.toLong() },
                pause = { clock.current = clock.current.plusMillis(it) })
            assertEquals(3, sends)
            assertEquals(2, typing)
            assertTrue(FoundationChat(service).prepareIntroduction(request, 9240, 1))
            com.eliteteam.speakingcoach.telegram.deliverIntroduction(TelegramIntroduction(service.database), 9240, request.requestId,
                typing = { error("unexpected typing") }, send = { error("must not resend") })
            val student = service.identity.resolve("telegram", "9240")
            val scope = service.identity.issue(student, "operator")
            assertEquals(listOf("/start") + TELEGRAM_INTRO_TEXTS.take(2),
                service.snapshot(scope, "text")["events"]!!.jsonArray.map { it.jsonObject.text("body") })
        }
    }

    @Test
    fun startResetsMemoryLessonsAndScopesButReplayKeepsTheNewRelationship() = runTest {
        withStore { service ->
            val old = service.identity.resolve("telegram", "9250")
            service.goal(old, "work meetings", 0)
            val token = service.identity.issue(old, "operator")
            service.op(token, "lesson.create", json("kind" to "trial", "launchMode" to "executable",
                "startsAt" to Instant.now().plusSeconds(600).toString(), "durationMinutes" to 15, "timezone" to "UTC"))
            val bridge = FoundationChat(service)
            val request = introRequest(9250, 2, "/start", Instant.now())
            service.telegramIntake.accept(introEnvelope(9250, 2, "/start", Instant.now()))
            service.telegramIntake.accept(introEnvelope(9250, 3, "Теперь для путешествий", Instant.now()))
            assertTrue(bridge.prepareIntroduction(request, 9250, 2))
            val fresh = service.identity.resolve("telegram", "9250")
            assertTrue(fresh != old)
            assertEquals("scope_expired", assertFailsWith<FoundationFailure> { service.snapshot(token, "text") }.code)
            val freshToken = service.identity.issue(fresh, "operator")
            val snapshot = service.snapshot(freshToken, "text")
            assertEquals("introduction", snapshot.text("textMode"))
            assertEquals(listOf("/start"), snapshot["events"]!!.jsonArray.map { it.jsonObject.text("body") })
            service.database.transaction(readOnly = true) { c, _ ->
                for (table in listOf("memory_fact", "learning_goal", "lesson", "trial_notice", "subscription", "tutor_inbox")) {
                    assertEquals(0L, c.rows("SELECT count(*) FROM $table WHERE student_id IN (?,?)", old, fresh) { it.getLong(1) }.single())
                }
                assertEquals(0L, c.rows("SELECT count(*) FROM foundation_job WHERE student_id=? AND status<>'cancelled'", old) { it.getLong(1) }.single())
                assertEquals("pending", c.document("SELECT to_jsonb(i) FROM telegram_text_intake i WHERE chat_id=9250 AND message_id=3")!!.text("status"))
            }
            // The early answer cancels the new greeting, and a restart never erases it again.
            assertEquals(null, bridge.introduction.next(9250, request.requestId))
            assertTrue(FoundationChat(service).prepareIntroduction(request, 9250, 2))
            assertEquals(fresh, service.identity.resolve("telegram", "9250"))
            service.goal(fresh, "travel", 0)
            assertTrue(bridge.prepareIntroduction(request.copy(requestId="message:1"), 9250, 1))
            assertEquals("travel", service.snapshot(freshToken, "text").obj("goal").obj("content").text("value"))
            service.telegramIntake.accept(introEnvelope(9250, 1, "old delayed text", Instant.now()))
            service.database.transaction(readOnly = true) { c, _ ->
                assertEquals("done", c.document("SELECT to_jsonb(i) FROM telegram_text_intake i WHERE chat_id=9250 AND message_id=1")!!.text("status"))
            }
            assertTrue(bridge.prepareIntroduction(request.copy(requestId="message:4"), 9250, 4))
            assertTrue(fresh != service.identity.resolve("telegram", "9250"))
            assertEquals(0, bridge.introduction.next(9250, "message:4")!!.index)
            // An old greeting receipt cannot recreate erased history.
            bridge.introduction.sent(9250, request.requestId, 0, 99)
        }
    }

    @Test
    fun migratedExistingStudentsAlsoResetOnStart() = runTest {
        withStore(beforeMigration = { url ->
            org.flywaydb.core.Flyway.configure().dataSource(url, null, null).target("44").load().migrate()
            DriverManager.getConnection(url).use { c ->
                c.update("INSERT INTO student(id) VALUES ('before-intro')")
                c.update("INSERT INTO student_identity(provider,subject,student_id) VALUES ('telegram','9260','before-intro')")
            }
        }) { service ->
            assertTrue(FoundationChat(service).prepareIntroduction(introRequest(9260, 1, "/start", Instant.now()), 9260, 1))
            assertTrue(service.identity.resolve("telegram", "9260") != "before-intro")
        }
    }

    @Test
    fun cancellationWinsBeforeClaimAndConcurrentDispatchersHaveOnlyOneSendClaim() = runTest {
        withStore { service ->
            val bridge = FoundationChat(service)
            val at = Instant.now()
            val first = introRequest(9270, 1, "/start", at)
            assertTrue(bridge.prepareIntroduction(first, 9270, 1))
            assertEquals(0, bridge.introduction.next(9270, first.requestId)!!.index)
            service.telegramIntake.accept(introEnvelope(9270, 2, "Хочу говорить увереннее", at))
            assertEquals(false, bridge.introduction.claim(9270, first.requestId, 0))

            val second = introRequest(9271, 1, "/start", at)
            assertTrue(bridge.prepareIntroduction(second, 9271, 1))
            val results = kotlinx.coroutines.coroutineScope {
                (1..8).map {
                    async { TelegramIntroduction(service.database).claim(9271, second.requestId, 0) }
                }.map { it.await() }
            }
            assertEquals(1, results.count { it })
            // An inbound reply cannot retract an already claimed external send; its receipt still belongs in history.
            service.telegramIntake.accept(introEnvelope(9271, 2, "Для работы", at))
            bridge.introduction.sent(9271, second.requestId, 0, 71)
            assertEquals(null, bridge.introduction.next(9271, second.requestId))
            val student = service.identity.resolve("telegram", "9271")
            val token = service.identity.issue(student, "operator")
            assertEquals(listOf("/start", TELEGRAM_INTRO_TEXTS[0]),
                service.snapshot(token, "text")["events"]!!.jsonArray.map { it.jsonObject.text("body") })
            service.op(token, "student.delete", json())
            bridge.introduction.sent(9271, second.requestId, 0, 71)
            assertEquals(null, bridge.introduction.next(9271, second.requestId))
        }
    }

    private suspend fun withStore(clock: Clock = Clock.systemUTC(), beforeMigration: (String) -> Unit = {}, block: suspend (FoundationService) -> Unit) {
        val raw = requireNotNull(System.getenv("TEST_POSTGRES_URL")) { "TEST_POSTGRES_URL is required; SP-00 integration tests cannot skip PostgreSQL" }
        val url = if (raw.startsWith("jdbc:")) raw else "jdbc:$raw"
        val schema = "sp00_${newId().replace("-", "")}"
        DriverManager.getConnection(url).use { it.createStatement().use { sql -> sql.execute("CREATE SCHEMA $schema") } }
        val scoped = url + (if ('?' in url) "&" else "?") + "currentSchema=$schema"
        try {
            beforeMigration(scoped)
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
    fun memoryPoliciesDeletionAndFreshReportsPreserveTheInterviewContract() = runTest {
        withStore { service ->
            val student = service.identity.resolve("telegram", "700701")
            val operator = service.identity.issue(student, "operator")
            val first = service.history.accept(student, "memory-first", "I am a designer", Instant.now(), false)
            val sourceId = first.text("event_id")
            service.op(operator, "memory.record", json("sourceId" to sourceId, "evidence" to "designer",
                "category" to "personal", "key" to "work", "value" to "designer", "kind" to "user_report", "expectedVersion" to 0))
            val restricted = service.history.accept(student, "memory-restrict", "Do not discuss my work", Instant.now(), false)
            service.op(operator, "memory.policy", json("sourceId" to restricted.text("event_id"), "evidence" to "Do not discuss my work",
                "category" to "personal", "key" to "work", "expectedVersion" to 1, "mentionPolicy" to "context_only", "validUntil" to null))
            val found = service.op(operator, "memory.search", json("query" to "design", "offset" to 0))["facts"]!!.jsonArray.single().jsonObject
            assertEquals("context_only", found.text("mention_policy"))
            assertEquals(2L, found.number("revision"))
            service.op(operator, "memory.propose", json("sourceId" to sourceId, "key" to "enjoys_design", "value" to "enjoys design"))
            assertEquals(1, service.op(operator, "memory.discuss", json("query" to "design", "offset" to 0))["candidates"]!!.jsonArray.size)
            assertEquals(1, service.snapshot(operator, "text")["facts"]!!.jsonArray.size)
            assertEquals("invalid_query", assertFailsWith<FoundationFailure> {
                service.op(operator, "memory.discuss", json("query" to "", "offset" to 0))
            }.code)
            val request = "memory-forget"
            val forgotten = service.history.accept(student, request, "Forget my work", Instant.now(), false)
            // Earlier synthetic inputs are operator fixtures, not actual queued chat turns.
            service.database.transaction { c, _ -> c.update("UPDATE tutor_inbox SET status='ready' WHERE student_id=? AND request_id<>?", student, request) }
            val text = service.identity.issue(student, "text", request)
            service.op(text, "run.claim", json())
            val result = service.op(text, "memory.forget_requested", json("sourceId" to forgotten.text("event_id"), "evidence" to "Forget my work",
                "category" to "personal", "key" to "work", "expectedVersion" to 2))
            assertEquals("true", result.text("contextRevoked"))
            assertFailsWith<FoundationFailure> { service.snapshot(operator, "text") }
            val fresh = service.identity.issue(student, "operator")
            assertTrue(service.snapshot(fresh, "text")["facts"]!!.jsonArray.isEmpty())
            service.database.transaction(readOnly = true) { c, _ ->
                assertEquals("ready", c.document("SELECT to_jsonb(i) FROM tutor_inbox i WHERE student_id=? AND request_id=?", student, request)!!.text("status"))
                assertEquals(0L, c.rows("SELECT count(*) FROM conversation_event WHERE student_id=? AND body IS NOT NULL", student) { it.getLong(1) }.single())
            }
            val newSource = service.history.accept(student, "new-report", "I am a teacher now", Instant.now(), false)
            service.op(fresh, "memory.record", json("sourceId" to newSource.text("event_id"), "evidence" to "teacher",
                "category" to "personal", "key" to "work", "value" to "teacher", "kind" to "user_report", "expectedVersion" to 3))
            assertEquals("teacher", service.snapshot(fresh, "text")["facts"]!!.jsonArray.single().jsonObject.text("value"))
        }
    }

    @Test
    fun delayedFactCannotOverwriteANewerSourceEvenWithCurrentVersion() = runTest {
        withStore { service ->
            val student = service.identity.resolve("telegram", "700702")
            val operator = service.identity.issue(student, "operator")
            val old = service.history.accept(student, "old", "designer", Instant.now(), false)
            val fresh = service.history.accept(student, "fresh", "teacher", Instant.now(), false)
            service.op(operator, "memory.record", json("sourceId" to fresh.text("event_id"), "evidence" to "teacher",
                "category" to "personal", "key" to "work", "value" to "teacher", "kind" to "user_report", "expectedVersion" to 0))
            assertEquals("stale_source", assertFailsWith<FoundationFailure> {
                service.op(operator, "memory.record", json("sourceId" to old.text("event_id"), "evidence" to "designer",
                    "category" to "personal", "key" to "work", "value" to "designer", "kind" to "user_report", "expectedVersion" to 1))
            }.code)
        }
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
            assertEquals(true, answer.showProgressNavigation)
            val student = service.identity.resolve("telegram", "7001")
            val operator = service.identity.issue(student, "operator")
            service.op(operator, "lesson.create", json("kind" to "trial", "launchMode" to "executable",
                "startsAt" to Instant.now().plusSeconds(600).toString(), "durationMinutes" to 15, "timezone" to "UTC"))
            assertEquals(true, bridge.withDelivery(request, answer).showProgressNavigation)
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
    fun widgetReceiptSurvivesRestartAndStaleBookingCannotOverwriteNewerAgreement() = runTest {
        withStore { service ->
            val bridge = FoundationChat(service)
            val request = introRequest(9300, 1, "Запиши сейчас", Instant.now())
            val scope = bridge.accept(request, false)
            service.op(scope, "run.claim", json())
            val student = service.identity.resolve("telegram", "9300")
            val operator = service.identity.issue(student, "operator")
            val lesson = service.op(operator, "lesson.create", json("kind" to "trial", "launchMode" to "executable",
                "startsAt" to Instant.now().plusSeconds(600).toString(), "durationMinutes" to 15, "timezone" to "UTC"))
            service.op(scope, "run.complete", json("reply" to "Договорились", "bookingId" to lesson.text("id"), "degraded" to false))
            val delivery = com.eliteteam.speakingcoach.ai.TutorDelivery(request.sessionId, request.requestId,
                bookingId = lesson.text("id"), version = 1, cardIsWidget = true)
            assertTrue(bridge.beginDelivery(delivery))
            assertEquals(false, FoundationChat(service).beginDelivery(delivery))
            val response = com.eliteteam.speakingcoach.ai.TutorResponse("test", "Договорились",
                booking = com.eliteteam.speakingcoach.ai.TrialBooking(lesson.text("id"), student, "scheduled",
                    lesson.text("starts_at"), "UTC", 1))
            val uncertain = FoundationChat(service).withDelivery(request, response)
            assertEquals(false, uncertain.cardDelivered)
            assertEquals(false, uncertain.showProgressNavigation)
            bridge.delivered(delivery.copy(cardMessageId = 81))
            val restored = FoundationChat(service).withDelivery(request, response)
            assertEquals(true, restored.cardIsWidget)
            assertEquals(true, restored.cardDelivered)
            assertEquals(false, restored.showProgressNavigation)
            assertEquals(true, bridge.withDelivery(request, response.copy(booking = null)).showProgressNavigation)
            assertEquals(81L, restored.cardMessageId)
            val second = request.copy(requestId = "message:2")
            val nextScope = bridge.accept(second, false)
            service.op(nextScope, "run.claim", json())
            service.op(nextScope, "run.complete", json("reply" to "Договорились", "bookingId" to lesson.text("id"), "degraded" to false))
            service.op(operator, "lesson.cancel", json("lessonId" to lesson.text("id"), "expectedVersion" to 1))
            assertEquals(false, bridge.beginDelivery(delivery.copy(requestId = second.requestId)))
            assertEquals(false, bridge.withDelivery(second, response).cardDelivered)
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
