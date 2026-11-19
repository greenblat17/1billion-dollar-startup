package com.eliteteam.speakingcoach.foundation

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.sql.DriverManager
import java.time.Instant
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
                suspend fun eligible(subject: String): String {
                    val student = service.identity.resolve("telegram", subject)
                    val lesson = newId()
                    service.database.transaction { c, now ->
                        c.update("INSERT INTO learning_goal(student_id,revision,content,created_at) VALUES (?,1,?::jsonb,?)",
                            student, json("value" to "Speak at work"), now)
                        c.update("""INSERT INTO lesson(id,student_id,kind,status,starts_at,duration_minutes,timezone,
                            launch_mode,trial_version) VALUES (?,?,'trial','completed',?,30,'UTC','executable','v5.5')""",
                            lesson, student, now.minusSeconds(3600))
                        c.update("""INSERT INTO learning_roadmap(student_id,revision,goal_revision,learning_revision,
                            knowledge_digest,catalog_version,document,created_at) VALUES (?,1,1,1,'fixture',?,?::jsonb,?)""",
                            student, manifest.version, json("goal" to "Speak at work", "targetLevel" to "B2"), now)
                        c.update("""INSERT INTO learning_roadmap_priority(student_id,revision,roadmap_revision,
                            document,updated_at) VALUES (?,1,1,?::jsonb,?)""", student, json("trigger" to "initial"), now)
                        c.update("""INSERT INTO foundation_job(id,student_id,data_epoch,kind,dedup_key,target_id,
                            input_refs,status,due_at,deadline) SELECT ?,id,data_epoch,'roadmap.generate','fixture',?,
                            '{}'::jsonb,'succeeded',?,? FROM student WHERE id=?""",
                            newId(), lesson, now, now.plusSeconds(3600), student)
                    }
                    return student
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
                failing.use {
                    val worker = InitialRoadmapDelivery(service, it, "https://ai.test", "internal", "123:test", "https://mini.test")
                    repeat(3) { worker.tick() }
                }
                assertEquals(1, failedCalls)
                service.database.transaction(readOnly = true) { c, _ ->
                    assertEquals("unknown", c.document("SELECT to_jsonb(d) FROM initial_roadmap_delivery d WHERE student_id=?",
                        unknownStudent)!!.text("status"))
                }
            }
        } finally {
            DriverManager.getConnection(url).use { it.createStatement().execute("DROP SCHEMA $schema CASCADE") }
        }
    }
}
