package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.DiagnosticOutput
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.sql.DriverManager
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InitialRoadmapDeliveryIntegrationTest {
    @Test fun completedUnpaidTrialSendsSavedRoadmapOnceAndKeepsUnknownReceipt() = runTest {
        val url = requireNotNull(System.getenv("TEST_POSTGRES_URL"))
        val schema = "roadmap_delivery_${newId().replace("-", "")}"
        DriverManager.getConnection(url).use { it.createStatement().execute("CREATE SCHEMA $schema") }
        val scoped = url + (if ('?' in url) "&" else "?") + "currentSchema=$schema"
        try {
            FoundationService(FoundationDatabase(scoped)).use { service ->
                val manifest = catalogFixture()
                LearningCatalog(service.database).import(LearningCatalogModel.document(manifest).toString())
                LearningCatalog(service.database).publish(manifest.version, "synthetic-test", "fixture only")
                suspend fun eligible(subject: String, legacySource: Boolean = true, provider: String = "telegram"): String {
                    val student = service.identity.resolve(provider, subject)
                    val lesson = newId()
                    service.database.transaction { c, now ->
                        c.update("INSERT INTO learning_goal(student_id,revision,content,created_at) VALUES (?,1,?::jsonb,?)",
                            student, json("value" to "Speak at work"), now)
                        c.update("""INSERT INTO lesson(id,student_id,kind,status,starts_at,duration_minutes,timezone,
                            launch_mode,trial_version) VALUES (?,?,'trial','completed',?,30,'UTC','executable','v5.5')""",
                            lesson, student, now.minusSeconds(3600))
                        c.update("""INSERT INTO learning_roadmap(student_id,revision,goal_revision,learning_revision,
                            knowledge_digest,catalog_version,document,created_at,legacy_generation_source)
                            VALUES (?,1,1,1,'fixture',?,?::jsonb,?,?)""",
                            student, manifest.version, json("goal" to "Speak at work", "targetLevel" to "B2"), now, legacySource)
                        c.update("""INSERT INTO learning_roadmap_priority(student_id,revision,roadmap_revision,
                            document,updated_at) VALUES (?,1,1,?::jsonb,?)""", student, json("trigger" to "initial"), now)
                        c.update("""INSERT INTO foundation_job(id,student_id,data_epoch,kind,dedup_key,target_id,
                            input_refs,status,due_at,deadline) SELECT ?,id,data_epoch,'roadmap.generate','fixture',?,
                            '{}'::jsonb,'succeeded',?,? FROM student WHERE id=?""",
                            newId(), lesson, now, now.plusSeconds(3600), student)
                    }
                    return student
                }
                val syntheticStudent = eligible(newId(), provider = "agent_test")
                val oldDelivery = newId()
                service.database.transaction { c, now ->
                    c.update("""INSERT INTO initial_roadmap_delivery(id,student_id,lesson_id,roadmap_revision,data_epoch,
                        status,body,created_at) SELECT ?,student_id,id,1,1,'ready','Fixture without Telegram',?
                        FROM lesson WHERE student_id=?""", oldDelivery, now.minusSeconds(3600), syntheticStudent)
                }
                val sentStudent = eligible("10001")
                var telegramCalls = 0
                val http = HttpClient(MockEngine { request ->
                    if (request.url.host == "ai.test") respond("{\"reply\":\"Цель и весь маршрут после пробного.\",\"roadmapRevision\":1}", HttpStatusCode.OK)
                    else { telegramCalls++; respond("{\"ok\":true}", HttpStatusCode.OK) }
                })
                http.use {
                    val worker = InitialRoadmapDelivery(service, it, "https://ai.test", "internal", "123:test", "https://mini.test")
                    repeat(3) { worker.tick() }
                }
                assertEquals(1, telegramCalls)
                service.database.transaction(readOnly = true) { c, _ ->
                    val row = c.document("SELECT to_jsonb(d) FROM initial_roadmap_delivery d WHERE student_id=?", sentStudent)!!
                    assertEquals("sent", row.text("status"))
                    assertEquals(1L, row.number("next_part"))
                    assertTrue(c.document("SELECT to_jsonb(s) FROM subscription s WHERE student_id=?", sentStudent) == null)
                }
                val unknownStudent = eligible("10002")
                var failedCalls = 0
                val failing = HttpClient(MockEngine { request ->
                    if (request.url.host == "ai.test") respond("{\"reply\":\"Маршрут\",\"roadmapRevision\":1}", HttpStatusCode.OK)
                    else { failedCalls++; respond("upstream disconnected", HttpStatusCode.BadGateway) }
                })
                DiagnosticOutput().use { logs ->
                    failing.use {
                        val worker = InitialRoadmapDelivery(service, it, "https://ai.test", "internal", "123:test", "https://mini.test")
                        repeat(3) { worker.tick() }
                    }
                    val events = logs.events().filter { it.optionalText("operation") == "roadmap.deliver" }
                    val unknown = events.single { it.text("outcome") == "unknown" }
                    assertEquals("telegram_response_unconfirmed", unknown.text("reason_code"))
                    assertEquals("telegram_response", unknown.text("stage"))
                    assertEquals(502, unknown.number("http_status").toInt())
                    assertEquals(1L, unknown.number("roadmap_revision"))
                    assertTrue(events.none { it.text("stage") == "delivery_persist" })
                    assertTrue(logs.lines().none { "upstream disconnected" in it })
                }
                assertEquals(1, failedCalls)
                service.database.transaction(readOnly = true) { c, _ ->
                    assertEquals("unknown", c.document("SELECT to_jsonb(d) FROM initial_roadmap_delivery d WHERE student_id=?",
                        unknownStudent)!!.text("status"))
                }
                val rejectedStudent = eligible("10003")
                var rejectedCalls = 0
                DiagnosticOutput().use { logs ->
                    HttpClient(MockEngine { request ->
                        if (request.url.host == "ai.test") respond("""{"reply":"Маршрут","roadmapRevision":1}""", HttpStatusCode.OK)
                        else {
                            rejectedCalls++
                            respond("""{"ok":false,"error_code":400,"description":"control-secret"}""", HttpStatusCode.BadRequest)
                        }
                    }).use {
                        val worker = InitialRoadmapDelivery(service, it, "https://ai.test", "internal", "123:test", "https://mini.test")
                        repeat(3) { worker.tick() }
                    }
                    val events = logs.events().filter { it.optionalText("operation") == "roadmap.deliver" }
                    val rejected = events.single { it.text("outcome") == "rejected" }
                    assertEquals("telegram_request_rejected", rejected.text("reason_code"))
                    assertEquals(400L, rejected.number("http_status"))
                    assertTrue(events.none { it.text("stage") == "delivery_persist" })
                    assertTrue(logs.lines().none { "control-secret" in it })
                }
                assertEquals(1, rejectedCalls)
                service.database.transaction(readOnly = true) { c, _ ->
                    val delivery = c.document("SELECT to_jsonb(d) FROM initial_roadmap_delivery d WHERE student_id=?", rejectedStudent)!!
                    assertEquals("unknown", delivery.text("status"))
                    assertEquals(null, delivery.optionalText("source_job_id"))
                }
                val timedOutStudent = eligible("10006")
                var timedOutCalls = 0
                DiagnosticOutput().use { logs ->
                    HttpClient(MockEngine { request ->
                        if (request.url.host == "ai.test") respond("""{"reply":"Маршрут","roadmapRevision":1}""", HttpStatusCode.OK)
                        else {
                            timedOutCalls++
                            throw java.net.SocketTimeoutException("PRIVATE_TELEGRAM_TIMEOUT")
                        }
                    }).use {
                        val worker = InitialRoadmapDelivery(service, it, "https://ai.test", "internal", "123:test", "https://mini.test")
                        repeat(4) { worker.tick() }
                    }
                    val entries = logs.events().filter { it.optionalText("operation") == "roadmap.deliver" }
                    val timeout = entries.single { it.text("outcome") == "unknown" }
                    assertEquals("telegram_request_timeout", timeout.text("reason_code"))
                    assertEquals("telegram_request", timeout.text("stage"))
                    assertEquals(0L, timeout.number("part"))
                    assertTrue(entries.none { it.text("stage") == "delivery_persist" })
                    assertTrue(logs.lines().none { "PRIVATE_TELEGRAM_TIMEOUT" in it })
                }
                assertEquals(1, timedOutCalls)
                service.database.transaction(readOnly = true) { c, _ ->
                    assertEquals("unknown", c.document("SELECT to_jsonb(d) FROM initial_roadmap_delivery d WHERE student_id=?",
                        timedOutStudent)!!.text("status"))
                }
                val partialStudent = eligible("10007")
                val partialMessage = "PRIVATE_ROADMAP_EVIDENCE ".repeat(240)
                var partialCalls = 0
                DiagnosticOutput().use { logs ->
                    HttpClient(MockEngine { request ->
                        if (request.url.host == "ai.test") {
                            respond(json("reply" to partialMessage, "roadmapRevision" to 1).toString(), HttpStatusCode.OK)
                        } else if (++partialCalls == 1) {
                            respond("""{"ok":true}""", HttpStatusCode.OK)
                        } else {
                            respond("""{"ok":false,"error_code":429}""", HttpStatusCode.TooManyRequests)
                        }
                    }).use {
                        val worker = InitialRoadmapDelivery(service, it, "https://ai.test", "internal", "123:test", "https://mini.test")
                        repeat(5) { worker.tick() }
                    }
                    val entries = logs.events().filter { it.optionalText("operation") == "roadmap.deliver" }
                    val accepted = entries.single { it.text("stage") == "delivery_persist" }
                    val rejected = entries.single { it.text("outcome") == "rejected" }
                    assertEquals(0L, accepted.number("part"))
                    assertEquals("ready", accepted.text("job_status"))
                    assertEquals(1L, rejected.number("part"))
                    assertEquals(429L, rejected.number("http_status"))
                    assertEquals(accepted.text("trace_id"), rejected.text("trace_id"))
                    assertEquals(accepted.text("delivery_id"), rejected.text("delivery_id"))
                    assertTrue(logs.lines().none { "PRIVATE_ROADMAP_EVIDENCE" in it })
                }
                assertEquals(2, partialCalls)
                service.database.transaction(readOnly = true) { c, _ ->
                    val delivery = c.document("SELECT to_jsonb(d) FROM initial_roadmap_delivery d WHERE student_id=?",
                        partialStudent)!!
                    assertEquals("unknown", delivery.text("status"))
                    assertEquals(1L, delivery.number("next_part"))
                }
                val manualStudent = eligible("10004", legacySource = false)
                HttpClient(MockEngine { error("A prior generator cannot make a manual Roadmap eligible") }).use {
                    InitialRoadmapDelivery(service, it, "https://ai.test", "internal", "123:test", "https://mini.test").tick()
                }
                service.database.transaction(readOnly = true) { c, _ ->
                    val deliveries = c.rows("SELECT count(*) FROM initial_roadmap_delivery WHERE student_id=?", manualStudent) { it.getLong(1) }
                    assertEquals(0L, deliveries.single())
                }
                val racedStudent = eligible("10005")
                val entered = List(2) { CompletableDeferred<Unit>() }
                val reply = List(2) { CompletableDeferred<Unit>() }
                val request = java.util.concurrent.atomic.AtomicInteger()
                DiagnosticOutput().use { logs ->
                    HttpClient(MockEngine {
                        val index = request.getAndIncrement()
                        entered[index].complete(Unit)
                        reply[index].await()
                        respond("""{"reply":"Attempt ${index + 1}","roadmapRevision":1}""", HttpStatusCode.OK)
                    }).use { client ->
                        val worker = InitialRoadmapDelivery(service, client, "https://ai.test", "internal", "123:test", "https://mini.test")
                        coroutineScope {
                            val replaced = async { worker.tick() }
                            entered[0].await()
                            service.database.transaction { c, now ->
                                c.update("UPDATE initial_roadmap_delivery SET lease_until=? WHERE student_id=?", now.minusSeconds(1), racedStudent)
                            }
                            val current = async { worker.tick() }
                            entered[1].await()
                            reply[0].complete(Unit)
                            replaced.await()
                            reply[1].complete(Unit)
                            current.await()
                        }
                    }
                    val persisted = logs.events().filter { it.text("stage") == "prepare_persist" }
                    assertEquals(listOf("superseded", "completed"), persisted.map { it.text("outcome") })
                    assertEquals(listOf(1L, 2L), persisted.map { it.number("attempt") })
                }
                service.database.transaction(readOnly = true) { c, _ ->
                    val delivery = c.document("SELECT to_jsonb(d) FROM initial_roadmap_delivery d WHERE student_id=?", racedStudent)!!
                    assertEquals("ready", delivery.text("status"))
                    assertEquals("Attempt 2", delivery.text("body"))
                }
            }
        } finally {
            DriverManager.getConnection(url).use { it.createStatement().execute("DROP SCHEMA $schema CASCADE") }
        }
    }
}
