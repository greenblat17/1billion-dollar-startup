package com.eliteteam.speakingcoach.ai

import kotlinx.serialization.Serializable

@Serializable
data class HabitSnapshot(
    val version: String = "",
    val timezone: String = "Europe/Moscow",
    val asOf: String = "",
    val observedThrough: String = "",
    val historySince: String = "",
    val healthy: Boolean = false,
    val updatedAt: Double = 0.0,
    val days: List<HabitDay> = emptyList(),
    val cohorts: List<HabitCohort> = emptyList(),
    val periodRetention: List<AdminRetentionDay> = emptyList(),
)

@Serializable
data class HabitDay(
    val day: String,
    val dau: Long? = null,
    val wau: Long? = null,
    val ratio: Double? = null,
    val complete: Boolean = false,
    val provisional: Boolean = false,
    val distribution: List<HabitBucket> = emptyList(),
)

@Serializable
data class HabitBucket(val bucket: String, val users: Long? = null)

@Serializable
data class HabitCohort(
    val week: String,
    val size: Long = 0,
    val partialHistory: Boolean = false,
    val rates: List<AdminRetentionDay> = emptyList(),
)
