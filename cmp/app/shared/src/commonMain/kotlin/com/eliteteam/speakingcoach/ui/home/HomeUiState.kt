package com.eliteteam.speakingcoach.ui.home

data class HomeUiState(
    val userName: String,
    val selectedTopic: TopicKind,
    val lastConversation: ConversationSummary?,
)

data class ConversationSummary(
    val title: String,
    val durationMinutes: Int,
    val whenLabel: String?,
)

enum class TopicKind {
    Everyday,
    Work,
    Travel,
    Random,
}
