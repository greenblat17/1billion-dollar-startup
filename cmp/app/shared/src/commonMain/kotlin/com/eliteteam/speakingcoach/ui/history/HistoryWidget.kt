package com.eliteteam.speakingcoach.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cmp.app.shared.generated.resources.Res
import cmp.app.shared.generated.resources.cd_open_conversation
import cmp.app.shared.generated.resources.conversation_meta
import cmp.app.shared.generated.resources.duration_minutes
import cmp.app.shared.generated.resources.history_subtitle
import cmp.app.shared.generated.resources.history_title
import cmp.app.shared.generated.resources.ic_chevron_right
import cmp.app.shared.generated.resources.score_grammar
import cmp.app.shared.generated.resources.score_pronunciation
import cmp.app.shared.generated.resources.score_vocabulary
import com.eliteteam.speakingcoach.ui.mock.MockSpeakingData
import com.eliteteam.speakingcoach.ui.theme.AppTheme
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val CardShape = RoundedCornerShape(24.dp)

@Composable
fun HistoryWidget(
    state: HistoryUiState,
    onItemClick: (HistoryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.history_title),
            style = MaterialTheme.typography.headlineLarge,
            color = scheme.onBackground,
        )
        Text(
            text = stringResource(Res.string.history_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.items, key = { it.id }) { item ->
                HistoryCard(item, onClick = { onItemClick(item) })
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun HistoryCard(
    item: HistoryItem,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val duration = pluralStringResource(
        Res.plurals.duration_minutes,
        item.durationMinutes,
        item.durationMinutes,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(scheme.surfaceContainerLowest)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onBackground,
                )
                Text(
                    text = stringResource(Res.string.conversation_meta, duration, item.whenLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Icon(
                painter = painterResource(Res.drawable.ic_chevron_right),
                contentDescription = stringResource(Res.string.cd_open_conversation),
                tint = scheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = scheme.outline,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Score(stringResource(Res.string.score_grammar), item.scores.grammar)
            Score(stringResource(Res.string.score_vocabulary), item.scores.vocabulary)
            Score(stringResource(Res.string.score_pronunciation), item.scores.pronunciation)
        }
    }
}

@Composable
private fun Score(label: String, value: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Preview
@Composable
private fun HistoryWidgetPreview() {
    AppTheme {
        HistoryWidget(state = MockSpeakingData.history, onItemClick = {})
    }
}
