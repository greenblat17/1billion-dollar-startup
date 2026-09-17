package com.eliteteam.speakingcoach.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import cmp.app.shared.generated.resources.cd_profile
import cmp.app.shared.generated.resources.conversation_meta
import cmp.app.shared.generated.resources.duration_minutes
import cmp.app.shared.generated.resources.home_greeting
import cmp.app.shared.generated.resources.home_last_conversation
import cmp.app.shared.generated.resources.home_pick_topic
import cmp.app.shared.generated.resources.home_start_body
import cmp.app.shared.generated.resources.home_start_cta
import cmp.app.shared.generated.resources.home_start_title
import cmp.app.shared.generated.resources.home_subtitle
import cmp.app.shared.generated.resources.ic_chevron_right
import cmp.app.shared.generated.resources.ic_deployed_code
import cmp.app.shared.generated.resources.ic_flight
import cmp.app.shared.generated.resources.ic_local_cafe
import cmp.app.shared.generated.resources.ic_person
import cmp.app.shared.generated.resources.ic_work
import cmp.app.shared.generated.resources.topic_everyday
import cmp.app.shared.generated.resources.topic_random
import cmp.app.shared.generated.resources.topic_travel
import cmp.app.shared.generated.resources.topic_work
import cmp.app.shared.generated.resources.when_today
import com.eliteteam.speakingcoach.ui.components.PrimaryButton
import com.eliteteam.speakingcoach.ui.components.RelevaLogo
import com.eliteteam.speakingcoach.ui.mock.MockSpeakingData
import com.eliteteam.speakingcoach.ui.theme.AppTheme
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val CardShape = RoundedCornerShape(24.dp)
private val ChipShape = RoundedCornerShape(20.dp)

@Composable
fun HomeWidget(
    state: HomeUiState,
    onStart: () -> Unit,
    onTopicSelected: (TopicKind) -> Unit,
    onProfile: () -> Unit,
    onLastConversation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RelevaLogo()
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .border(1.dp, scheme.outline, CircleShape)
                    .clickable(onClick = onProfile),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_person),
                    contentDescription = stringResource(Res.string.cd_profile),
                    tint = scheme.onBackground,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(Res.string.home_greeting, state.userName),
            style = MaterialTheme.typography.headlineLarge,
            color = scheme.onBackground,
        )
        Text(
            text = stringResource(Res.string.home_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(scheme.primaryContainer)
                .padding(20.dp),
        ) {
            Text(
                text = stringResource(Res.string.home_start_title),
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.home_start_body),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = stringResource(Res.string.home_start_cta),
                onClick = onStart,
                trailingArrow = true,
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(Res.string.home_pick_topic),
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        TopicKind.entries.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { topic ->
                    TopicChip(
                        topic = topic,
                        selected = topic == state.selectedTopic,
                        onClick = { onTopicSelected(topic) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        val last = state.lastConversation
        if (last != null) {
            Text(
                text = stringResource(Res.string.home_last_conversation),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            LastConversationCard(last, onLastConversation)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TopicChip(
    topic: TopicKind,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val spec = topic.spec()
    val border = if (selected) scheme.primary else scheme.outline
    Row(
        modifier = modifier
            .clip(ChipShape)
            .background(scheme.surfaceContainerLowest)
            .border(1.dp, border, ChipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(spec.icon),
            contentDescription = null,
            tint = if (selected) scheme.primary else scheme.onBackground,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(spec.label),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) scheme.primary else scheme.onBackground,
        )
    }
}

@Composable
private fun LastConversationCard(
    summary: ConversationSummary,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val duration = pluralStringResource(
        Res.plurals.duration_minutes,
        summary.durationMinutes,
        summary.durationMinutes,
    )
    val whenLabel = summary.whenLabel ?: stringResource(Res.string.when_today)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(scheme.surfaceContainerLowest)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_work),
                contentDescription = stringResource(Res.string.cd_open_conversation),
                tint = scheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = summary.title,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onBackground,
            )
            Text(
                text = stringResource(Res.string.conversation_meta, duration, whenLabel),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Icon(
            painter = painterResource(Res.drawable.ic_chevron_right),
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
        )
    }
}

private data class TopicSpec(
    val icon: DrawableResource,
    val label: StringResource,
)

private fun TopicKind.spec(): TopicSpec = when (this) {
    TopicKind.Everyday -> TopicSpec(Res.drawable.ic_local_cafe, Res.string.topic_everyday)
    TopicKind.Work -> TopicSpec(Res.drawable.ic_work, Res.string.topic_work)
    TopicKind.Travel -> TopicSpec(Res.drawable.ic_flight, Res.string.topic_travel)
    TopicKind.Random -> TopicSpec(Res.drawable.ic_deployed_code, Res.string.topic_random)
}

@Preview
@Composable
private fun HomeWidgetPreview() {
    AppTheme {
        HomeWidget(
            state = MockSpeakingData.home,
            onStart = {},
            onTopicSelected = {},
            onProfile = {},
            onLastConversation = {},
        )
    }
}
