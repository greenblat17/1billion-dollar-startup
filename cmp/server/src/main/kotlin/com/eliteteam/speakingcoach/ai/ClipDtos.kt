package com.eliteteam.speakingcoach.ai

import kotlinx.serialization.Serializable

@Serializable
data class ClipAcceptedResponse(
    val jobId: String,
)

@Serializable
data class ClipStatusResponse(
    val jobId: String,
    val status: String,
    val error: ClipErrorResponse? = null,
)

@Serializable
data class ClipErrorResponse(
    val code: String,
    val message: String,
)
