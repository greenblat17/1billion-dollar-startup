package com.eliteteam.speakingcoach.foundation

import kotlinx.serialization.json.JsonObject

/** SP-19A mathematics. Inputs are typed fixture outcomes; SP-19B will supply verified Observations. */
internal object LearningScoreModel {
    const val START = 50.0
    const val UPPER = 85.0
    const val LOWER = 15.0
    val outcomes = setOf("independent_correct", "uncorrected_error", "self_corrected_error", "hinted_correct", "unresolved", "invalid")

    fun next(score: Double, outcome: String): Double {
        requireScore(score)
        if (outcome !in outcomes) reject("invalid_score_outcome")
        val result = when (outcome) {
            "independent_correct" -> score + (100.0 - score) * 0.12
            "uncorrected_error" -> score * 0.90
            "self_corrected_error" -> score * 0.96
            else -> score
        }
        return result.coerceIn(0.0, 100.0)
    }

    fun normalized(score: Double): Double {
        requireScore(score)
        return ((score - START) / (UPPER - START)).coerceIn(0.0, 1.0)
    }

    fun signal(score: Double): String = when {
        score >= UPPER -> "passed"
        score <= LOWER -> "review_approach"
        else -> "continue"
    }

    /** Completed numeric stages retain their contribution until an explicit new attempt. */
    fun progress(definition: JsonObject, scores: Map<String, Double>): Double {
        LearningPlanModel.definition(definition)
        val numeric = LearningPlanModel.stages(definition).filter { it.text("kind") == "numeric" }
        if (numeric.isEmpty()) return 0.0
        if (numeric.all { (scores[it.text("id")] ?: START) >= UPPER }) return 100.0
        return (100.0 * numeric.sumOf { stage ->
            val score = scores[stage.text("id")] ?: START
            stage["weight"]!!.toString().toDouble() * normalized(score)
        }).coerceIn(0.0, 100.0)
    }

    fun masteryReady(definition: JsonObject, scores: Map<String, Double>, metBinary: Set<String>): Boolean {
        LearningPlanModel.definition(definition)
        val stages = LearningPlanModel.stages(definition)
        // A binary-only Plan has no approved percentage/mastery policy.
        if (stages.none { it.text("kind") == "numeric" }) return false
        return stages.all { stage ->
            if (stage.text("kind") == "binary") stage.text("id") in metBinary
            else (scores[stage.text("id")] ?: START) >= UPPER
        } && progress(definition, scores) >= 100.0
    }

    private fun requireScore(score: Double) {
        if (!score.isFinite() || score !in 0.0..100.0) reject("invalid_stage_score")
    }
}
