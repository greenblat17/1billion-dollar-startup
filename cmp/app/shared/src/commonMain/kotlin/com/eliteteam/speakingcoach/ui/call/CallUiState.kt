package com.eliteteam.speakingcoach.ui.call

data class CallUiState(
    val elapsed: String,
    val caption: String,
    val micMuted: Boolean,
    val captionsOn: Boolean,
    val connecting: Boolean = false,
    val error: CallError? = null,
)
