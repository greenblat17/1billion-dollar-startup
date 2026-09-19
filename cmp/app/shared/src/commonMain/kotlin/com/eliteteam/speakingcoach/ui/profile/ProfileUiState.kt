package com.eliteteam.speakingcoach.ui.profile

data class ProfileUiState(
    val displayName: String,
    val email: String,
    val language: String,
    val tutorVoice: String,
    val captionsByDefault: Boolean,
    val avatarLetter: String,
    val goalMinutes: Int,
)
