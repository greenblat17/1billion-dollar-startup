package com.eliteteam.speakingcoach.foundation

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.sql.DriverManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LearningCatalogIntegrationTest {
    @Test
    fun importIsAtomicIdempotentAndDraftsAreNotReadable() = runTest {
        withCatalogDatabase { service, _ ->
            val catalog = LearningCatalog(service.database)
            val manifest = catalogFixture()
            val raw = LearningCatalogModel.document(manifest).toString()
            coroutineScope {
                val a = async { catalog.import(raw) }
                val b = async { catalog.import(raw) }
                assertEquals(a.await(), b.await())
            }
            assertEquals("catalog_not_published", assertFailsWith<FoundationFailure> {
                catalog.read(manifest.version, null, null, null)
            }.code)
            assertEquals("catalog_version_conflict", assertFailsWith<FoundationFailure> {
                catalog.import(LearningCatalogModel.document(manifest.copy(topics = listOf(
                    manifest.topics.first().copy(title = "Changed")) + manifest.topics.drop(1))).toString())
            }.code)
            service.database.transaction(readOnly = true) { c, _ ->
                assertEquals(1L, c.rows("SELECT count(*) FROM learning_catalog_release") { it.getLong(1) }.single())
                assertEquals(manifest.topics.size.toLong(), c.rows("SELECT count(*) FROM learning_catalog_topic") { it.getLong(1) }.single())
                assertEquals(0L, c.rows("SELECT count(*) FROM student") { it.getLong(1) }.single())
            }
            val revisedDraft = manifest.copy(version = "test-revised-draft", topics = listOf(
                manifest.topics.first().copy(scope = "Corrected before publication")) + manifest.topics.drop(1))
            assertEquals(revisedDraft.version, catalog.import(LearningCatalogModel.document(revisedDraft).toString()))
            assertFailsWith<IllegalArgumentException> { catalog.publish(manifest.version, "", "test acceptance") }
        }
    }

    @Test
    fun versionPinningAndIdentitySurvivePublicationAndRestart() = runTest {
        withCatalogDatabase { service, scopedUrl ->
            val manifest = catalogFixture()
            val catalog = LearningCatalog(service.database)
            catalog.import(LearningCatalogModel.document(manifest).toString())
            catalog.publish(manifest.version, "synthetic-test-reviewer", "test-only, not human acceptance")
            val next = manifest.copy(version = "test-v2", topics = listOf(manifest.topics.first().copy(title = "Clarified label")) + manifest.topics.drop(1))
            catalog.import(LearningCatalogModel.document(next).toString())
            catalog.publish(next.version, "synthetic-test-reviewer", "test-only")
            assertEquals(next.version, catalog.read(null, null, null, null).text("version"))
            val pinned = catalog.read(manifest.version, "grammar", "A1", manifest.topics.first().id)
            assertEquals(manifest.topics.first().title, pinned["topics"]!!.jsonArray.single().jsonObject.text("title"))
            assertEquals("catalog_identity_conflict", assertFailsWith<FoundationFailure> {
                catalog.import(LearningCatalogModel.document(next.copy(version = "test-invalid", topics = listOf(
                    next.topics.first().copy(objective = "Different learning outcome")) + next.topics.drop(1))).toString())
            }.code)
            val incompatible = listOf(
                next.copy(skills = listOf(next.skills.first().copy(description = "Different skill")) + next.skills.drop(1)),
                next.copy(problems = listOf(next.problems.first().copy(description = "Different difficulty")) + next.problems.drop(1)),
                next.copy(topics = listOf(next.topics.first().copy(scope = "Different boundaries")) + next.topics.drop(1)),
                next.copy(topics = listOf(next.topics.first().copy(coverageArea = "different-area")) + next.topics.drop(1)),
                next.copy(topics = next.topics.drop(1)),
            )
            incompatible.forEachIndexed { index, changed ->
                assertEquals("catalog_identity_conflict", assertFailsWith<FoundationFailure> {
                    catalog.import(LearningCatalogModel.document(changed.copy(version = "test-incompatible-$index")).toString())
                }.code)
            }
            assertEquals(next.version, catalog.read(null, null, null, null).text("version"))
            FoundationDatabase(scopedUrl).use { restarted ->
                assertEquals(next.version, LearningCatalog(restarted).read(null, null, null, null).text("version"))
            }
            catalog.publish(manifest.version, "synthetic-test-reviewer", "test-only rollback")
            assertEquals(manifest.version, catalog.read(null, null, null, null).text("version"))
        }
    }

    @Test
    fun authenticatedHttpReadsPublishedCatalogAndTargetsWithoutLearnerScope() = runTest {
        withCatalogDatabase { service, _ ->
            val manifest = catalogFixture()
            val catalog = LearningCatalog(service.database)
            catalog.import(LearningCatalogModel.document(manifest).toString())
            testApplication {
                application { routing { foundationRoutes(service, "catalog-test-token") } }
                assertEquals(401, client.get("/internal/foundation/v1/catalog").status.value)
                assertEquals(400, client.get("/internal/foundation/v1/catalog") { header("X-Internal-Token", "catalog-test-token") }.status.value)
                catalog.publish(manifest.version, "synthetic-test-reviewer", "test-only")
                val response = client.get("/internal/foundation/v1/catalog?topicId=grammar-b1-second-conditional") {
                    header("X-Internal-Token", "catalog-test-token")
                }
                assertEquals(200, response.status.value)
                val body = foundationJson.parseToJsonElement(response.bodyAsText()).jsonObject.obj("result")
                val ids = body["topics"]!!.jsonArray.map { it.jsonObject.text("id") }
                assertEquals(listOf("grammar-b1-second-conditional"), ids)
                for (query in listOf("skill=fluency", "cefr=C2", "topicId=missing", "includePrerequisites=true", "studentId=ignored")) {
                    assertEquals(400, client.get("/internal/foundation/v1/catalog?$query") {
                        header("X-Internal-Token", "catalog-test-token")
                    }.status.value)
                }
                val filtered = client.get("/internal/foundation/v1/catalog?skill=vocabulary&cefr=B1") {
                    header("X-Internal-Token", "catalog-test-token")
                }
                val topics = foundationJson.parseToJsonElement(filtered.bodyAsText()).jsonObject.obj("result")["topics"]!!.jsonArray
                assertTrue(topics.isNotEmpty() && topics.all { it.jsonObject.text("skillId") == "vocabulary" && it.jsonObject.text("cefr") == "B1" })
            }
        }
    }

    private suspend fun withCatalogDatabase(block: suspend (FoundationService, String) -> Unit) {
        val url = requireNotNull(System.getenv("TEST_POSTGRES_URL")) { "An isolated TEST_POSTGRES_URL is required" }
        val schema = "catalog_${newId().replace("-", "") }"
        DriverManager.getConnection(url).use { it.createStatement().use { sql -> sql.execute("CREATE SCHEMA $schema") } }
        val scoped = url + (if ('?' in url) "&" else "?") + "currentSchema=$schema"
        try {
            FoundationService(FoundationDatabase(scoped)).use { block(it, scoped) }
        } finally {
            DriverManager.getConnection(url).use { it.createStatement().use { sql -> sql.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }
}
