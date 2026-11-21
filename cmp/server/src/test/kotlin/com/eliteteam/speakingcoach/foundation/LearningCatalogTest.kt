package com.eliteteam.speakingcoach.foundation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal fun catalogFixture(): CatalogManifest = LearningCatalogModel.parse(
    Files.readString(Path.of("../../learning-catalog/v1/catalog.json")))

class LearningCatalogTest {
    @Test
    fun completeManifestContainsTargetsBySkillAndLevel() {
        val manifest = catalogFixture()
        assertEquals(6, manifest.skills.size)
        assertEquals(9, manifest.problems.size)
        assertEquals(163, manifest.topics.size)
        val document = LearningCatalogModel.document(manifest).toString()
        assertTrue(!document.contains("prerequisites"))
        assertTrue(!document.contains("curriculumOrder"))
        LearningCatalogModel.mvpSkills.forEach { skill -> LearningCatalogModel.levels.forEach { cefr ->
            assertTrue(manifest.topics.any { it.skillId == skill && it.cefr == cefr })
        } }
    }

    @Test
    fun rejectsDuplicateIds() {
        val manifest = catalogFixture()
        val first = manifest.topics.first()
        assertFailsWith<IllegalArgumentException> { LearningCatalogModel.validate(manifest.copy(topics = manifest.topics + first)) }
    }

    @Test
    fun rejectsUnknownAxesSourcesMissingCoverageAndProblemSkillMismatch() {
        val manifest = catalogFixture()
        assertFailsWith<IllegalArgumentException> { LearningCatalogModel.validate(manifest.copy(
            topics = manifest.topics.filterNot { it.skillId == "vocabulary" && it.cefr == "C1" })) }
        assertFailsWith<IllegalArgumentException> { LearningCatalogModel.validate(manifest.copy(
            topics = listOf(manifest.topics.first().copy(cefr = "C2")) + manifest.topics.drop(1))) }
        assertFailsWith<IllegalArgumentException> { LearningCatalogModel.validate(manifest.copy(
            topics = listOf(manifest.topics.first().copy(sourceIds = listOf("fabricated"))) + manifest.topics.drop(1))) }
        assertFailsWith<IllegalArgumentException> { LearningCatalogModel.validate(manifest.copy(
            problems = listOf(manifest.problems.first().copy(skillId = "grammar")) + manifest.problems.drop(1))) }
        assertFailsWith<IllegalArgumentException> { LearningCatalogModel.parse(
            LearningCatalogModel.document(manifest).toString().replace("\"schemaVersion\":2", "\"schemaVersion\":1")) }
        assertFailsWith<IllegalArgumentException> { LearningCatalogModel.parse(
            LearningCatalogModel.document(manifest).toString().replace("\"topics\":[{", "\"topics\":[{\"prerequisites\":[],")) }
    }
}
