package com.eliteteam.speakingcoach.ui.review

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cmp.app.shared.generated.resources.Res
import cmp.app.shared.generated.resources.cd_back
import cmp.app.shared.generated.resources.ic_arrow_back
import cmp.app.shared.generated.resources.ic_lightbulb
import cmp.app.shared.generated.resources.metric_fluency
import cmp.app.shared.generated.resources.metric_grammar
import cmp.app.shared.generated.resources.metric_pronunciation
import cmp.app.shared.generated.resources.metric_speed
import cmp.app.shared.generated.resources.metric_vocabulary
import cmp.app.shared.generated.resources.review_as_spoken
import cmp.app.shared.generated.resources.review_attention
import cmp.app.shared.generated.resources.review_better
import cmp.app.shared.generated.resources.review_examples
import cmp.app.shared.generated.resources.review_finish
import cmp.app.shared.generated.resources.review_improve
import cmp.app.shared.generated.resources.review_next
import cmp.app.shared.generated.resources.review_score_total
import cmp.app.shared.generated.resources.review_smoother
import cmp.app.shared.generated.resources.review_step
import cmp.app.shared.generated.resources.review_tip
import cmp.app.shared.generated.resources.review_title
import cmp.app.shared.generated.resources.review_try
import cmp.app.shared.generated.resources.review_try_this
import cmp.app.shared.generated.resources.review_why
import cmp.app.shared.generated.resources.review_word
import cmp.app.shared.generated.resources.review_words
import cmp.app.shared.generated.resources.review_you_said
import com.eliteteam.speakingcoach.ui.components.PrimaryButton
import com.eliteteam.speakingcoach.ui.mock.MockSpeakingData
import com.eliteteam.speakingcoach.ui.theme.AppTheme
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val CardShape = RoundedCornerShape(24.dp)

@Composable
fun ReviewWidget(
    step: ReviewStep,
    stepIndex: Int,
    stepCount: Int,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val last = stepIndex == stepCount - 1
    val scrollState = remember(stepIndex) { ScrollState(initial = 0) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(Res.drawable.ic_arrow_back),
                    contentDescription = stringResource(Res.string.cd_back),
                    tint = scheme.onBackground,
                )
            }
            Text(
                text = stringResource(Res.string.review_title),
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(Res.string.review_step, stepIndex + 1),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 16.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = stringResource(step.metric.titleRes()),
                style = MaterialTheme.typography.headlineLarge,
                color = scheme.onBackground,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = step.score.toString(),
                    style = MaterialTheme.typography.displaySmall,
                    color = scheme.primary,
                )
                Text(
                    text = stringResource(Res.string.review_score_total),
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = step.lead,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            CardBlock {
                Text(
                    text = stringResource(step.bulletsTitle.stringRes()),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onBackground,
                )
                Spacer(Modifier.height(12.dp))
                step.bullets.forEach { bullet ->
                    Row(
                        modifier = Modifier.padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 7.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(scheme.primary),
                        )
                        Text(
                            text = bullet,
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onBackground,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            CardBlock {
                Text(
                    text = stringResource(step.examplesTitle.stringRes()),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onBackground,
                )
                Spacer(Modifier.height(12.dp))
                step.examples.items.forEachIndexed { index, pair ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = scheme.outline,
                        )
                    }
                    Text(
                        text = stringResource(pair.originalLabel.stringRes()),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    Text(
                        text = pair.original,
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onBackground,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(pair.improvedLabel.stringRes()),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.primary,
                    )
                    Text(
                        text = pair.improved,
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CardShape)
                    .background(scheme.secondaryContainer)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_lightbulb),
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Column {
                    Text(
                        text = stringResource(Res.string.review_tip),
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.onBackground,
                    )
                    Text(
                        text = step.tip,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        PrimaryButton(
            text = stringResource(if (last) Res.string.review_finish else Res.string.review_next),
            onClick = onContinue,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun CardBlock(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(16.dp),
        content = { content() },
    )
}

private fun ReviewMetric.titleRes() = when (this) {
    ReviewMetric.Grammar -> Res.string.metric_grammar
    ReviewMetric.Vocabulary -> Res.string.metric_vocabulary
    ReviewMetric.Pronunciation -> Res.string.metric_pronunciation
    ReviewMetric.Fluency -> Res.string.metric_fluency
    ReviewMetric.SpeedOfSpeech -> Res.string.metric_speed
}

private fun ReviewStep.BulletsTitle.stringRes() = when (this) {
    ReviewStep.BulletsTitle.Improve -> Res.string.review_improve
    ReviewStep.BulletsTitle.Attention -> Res.string.review_attention
}

private fun ReviewStep.ExamplesTitle.stringRes() = when (this) {
    ReviewStep.ExamplesTitle.Quotes -> Res.string.review_examples
    ReviewStep.ExamplesTitle.Words -> Res.string.review_words
}

private fun ExampleLabel.stringRes() = when (this) {
    ExampleLabel.YouSaid -> Res.string.review_you_said
    ExampleLabel.Better -> Res.string.review_better
    ExampleLabel.Try -> Res.string.review_try
    ExampleLabel.Word -> Res.string.review_word
    ExampleLabel.AsSpoken -> Res.string.review_as_spoken
    ExampleLabel.Smoother -> Res.string.review_smoother
    ExampleLabel.TryThis -> Res.string.review_try_this
    ExampleLabel.Why -> Res.string.review_why
}

@Preview
@Composable
private fun ReviewWidgetPreview() {
    AppTheme {
        val state = MockSpeakingData.review
        ReviewWidget(
            step = state.steps.first(),
            stepIndex = 0,
            stepCount = state.steps.size,
            onBack = {},
            onContinue = {},
        )
    }
}
