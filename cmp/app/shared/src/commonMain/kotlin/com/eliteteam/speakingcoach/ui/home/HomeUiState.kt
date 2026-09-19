package com.eliteteam.speakingcoach.ui.home

data class HomeUiState(
    val userName: String,
    val selectedTopic: TopicKind,
    val lastConversation: ConversationSummary?,
    val spokenSeconds: Int,
    val streakDays: Int,
    val goalMinutes: Int,
    val startBusy: Boolean = false,
    val startFailed: Boolean = false,
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

fun TopicKind.toApiTopic(): String = when (this) {
    TopicKind.Random -> listOf(TopicKind.Everyday, TopicKind.Work, TopicKind.Travel).random().name
    else -> name
}
