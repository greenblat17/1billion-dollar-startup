package com.eliteteam.speakingcoach.ui.history

data class HistoryUiState(
    val items: List<HistoryItem>,
)

data class HistoryItem(
    val id: String,
    val title: String,
    val durationMinutes: Int,
    val whenLabel: String,
    val scores: HistoryScores,
)

data class HistoryScores(
    val grammar: Int,
    val vocabulary: Int,
    val pronunciation: Int,
)
