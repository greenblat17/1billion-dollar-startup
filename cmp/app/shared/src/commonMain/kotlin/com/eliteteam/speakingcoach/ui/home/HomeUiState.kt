package com.eliteteam.speakingcoach.ui.home

data class HomeUiState(
    val userName: String,
    val selectedTopic: TopicKind,
    val lastConversation: ConversationSummary?,
    val spokenSeconds: Int,
    val streakDays: Int,
    val goalMinutes: Int,
)

data class ConversationSummary(
    val title: String,
    val durationMinutes: Int,
    val whenLabel: String?,
    val topic: TopicKind,
)

enum class TopicKind {
    Everyday,
    Work,
    Travel,
    Random,
}
