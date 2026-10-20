package com.eliteteam.speakingcoach.telegram

internal const val FOLLOWUP_CATALOG_VERSION = "v1"

internal data class FollowupCopy(val id: String, val text: String)

internal object FollowupCatalog {
    private val byStage: Map<String, List<FollowupCopy>> = checkNotNull(
        javaClass.getResourceAsStream("/onboarding-followup-copy.tsv"),
    ) { "Missing onboarding follow-up copy" }.bufferedReader().use { reader ->
        reader.lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
            val fields = line.split('\t', limit = 3)
            require(fields.size == 3 && fields[2].isNotBlank())
            fields[0] to FollowupCopy("${fields[0]}:${fields[1]}", fields[2])
        }.groupBy({ it.first }, { it.second })
    }

    fun variants(stage: String): List<FollowupCopy> = byStage[stage].orEmpty()

    fun finalMessage(): FollowupCopy = requireNotNull(byStage["final"]?.singleOrNull())

    fun select(stage: String, sessionId: String, consumedIds: Set<String>): FollowupCopy? {
        val variants = variants(stage)
        if (variants.isEmpty()) return null
        val shift = Math.floorMod("$sessionId:$stage".hashCode(), variants.size)
        return (variants.indices).asSequence().map { variants[(it + shift) % variants.size] }
            .firstOrNull { it.id !in consumedIds }
    }
}
