package com.eliteteam.speakingcoach.ui.home

data class HomeUiState(
    val userName: String,
    val selectedTopic: TopicKind,
)

enum class TopicKind {
    Everyday,
    Work,
    Travel,
    Random,
}
