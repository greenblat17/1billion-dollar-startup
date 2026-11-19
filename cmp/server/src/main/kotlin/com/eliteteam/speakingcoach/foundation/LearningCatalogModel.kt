package com.eliteteam.speakingcoach.foundation

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
internal data class CatalogSkill(val id: String, val title: String, val description: String, val mvp: Boolean)

@Serializable
internal data class CatalogProblem(val id: String, val skillId: String, val title: String, val description: String)

@Serializable
internal data class CatalogSource(val id: String, val title: String, val url: String, val section: String, val usage: String)

@Serializable
internal data class CatalogTopic(
    val id: String, val skillId: String, val cefr: String, val title: String,
    val objective: String, val scope: String, val coverageArea: String,
    val sourceIds: List<String>, val reviewNote: String,
)

@Serializable
internal data class CatalogManifest(
    val schemaVersion: Int, val version: String, val editorialStatus: String,
    val skills: List<CatalogSkill>, val problems: List<CatalogProblem>,
    val sources: List<CatalogSource>, val topics: List<CatalogTopic>,
)

/** One strict parser/validator for offline checks, import and publication. No student state. */
internal object LearningCatalogModel {
    val levels = listOf("A1", "A2", "B1", "B2", "C1")
    val mvpSkills = setOf("speaking", "listening", "grammar", "vocabulary")
    val problemSkills = mapOf(
        "speaking_barrier" to "speaking", "listening_comprehension" to "listening",
        "grammar_unknown" to "grammar", "grammar_application" to "grammar",
        "vocabulary_passive" to "vocabulary", "vocabulary_active" to "vocabulary",
        "reading_comprehension" to "reading", "writing_sentences" to "writing", "writing_text" to "writing",
    )
    private val identifier = Regex("[a-z][a-z0-9_-]{0,119}")
    private val codec = Json { encodeDefaults = true }

    fun parse(raw: String): CatalogManifest = codec.decodeFromString<CatalogManifest>(raw).also { validate(it) }
    fun document(manifest: CatalogManifest): JsonObject = codec.parseToJsonElement(codec.encodeToString(manifest)).jsonObject

    fun validate(manifest: CatalogManifest) {
        require(manifest.schemaVersion == 2) { "unsupported_catalog_schema" }
        require(identifier.matches(manifest.version)) { "invalid_catalog_version" }
        require(manifest.editorialStatus == "pending_review") { "approval_is_separate_from_content" }
        unique(manifest.skills.map { it.id }); unique(manifest.problems.map { it.id })
        unique(manifest.sources.map { it.id }); unique(manifest.topics.map { it.id })
        require(manifest.skills.map { it.id }.toSet() == mvpSkills + setOf("reading", "writing")) { "invalid_skill_map" }
        require(manifest.skills.filter { it.mvp }.map { it.id }.toSet() == mvpSkills) { "invalid_mvp_skills" }
        manifest.skills.forEach { text(it.title, it.description) }
        require(manifest.problems.associate { it.id to it.skillId } == problemSkills) { "invalid_problem_map" }
        manifest.problems.forEach { text(it.title, it.description) }
        manifest.sources.forEach { text(it.title, it.section, it.usage); require(it.url.startsWith("https://") || it.url.startsWith("repo:")) }
        val sourceIds = manifest.sources.map { it.id }.toSet()
        manifest.topics.forEach { topic ->
            require(topic.skillId in mvpSkills && topic.cefr in levels) { "invalid_topic_axes:${topic.id}" }
            text(topic.title, topic.objective, topic.scope, topic.coverageArea, topic.reviewNote)
            require(topic.sourceIds.isNotEmpty() && topic.sourceIds.toSet().size == topic.sourceIds.size &&
                sourceIds.containsAll(topic.sourceIds)) { "invalid_topic_sources:${topic.id}" }

        }
        mvpSkills.forEach { skill -> levels.forEach { level ->
            require(manifest.topics.any { it.skillId == skill && it.cefr == level }) { "missing_coverage:$skill:$level" }
        } }
    }

    private fun unique(ids: List<String>) {
        require(ids.all { identifier.matches(it) } && ids.toSet().size == ids.size) { "invalid_or_duplicate_catalog_id" }
    }
    private fun text(vararg values: String) { require(values.all { it.isNotBlank() }) { "empty_catalog_text" } }
}
