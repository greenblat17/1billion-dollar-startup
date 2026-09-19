package com.eliteteam.speakingcoach.ui.profile

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cmp.app.shared.generated.resources.Res
import cmp.app.shared.generated.resources.duration_minutes
import cmp.app.shared.generated.resources.ic_chevron_right
import cmp.app.shared.generated.resources.ic_closed_caption
import cmp.app.shared.generated.resources.ic_description
import cmp.app.shared.generated.resources.ic_info
import cmp.app.shared.generated.resources.ic_language
import cmp.app.shared.generated.resources.ic_logout
import cmp.app.shared.generated.resources.ic_mic
import cmp.app.shared.generated.resources.ic_record_voice_over
import cmp.app.shared.generated.resources.ic_shield
import cmp.app.shared.generated.resources.ic_timer
import cmp.app.shared.generated.resources.profile_about
import cmp.app.shared.generated.resources.profile_captions
import cmp.app.shared.generated.resources.profile_captions_body
import cmp.app.shared.generated.resources.profile_daily_goal
import cmp.app.shared.generated.resources.profile_daily_goal_body
import cmp.app.shared.generated.resources.profile_help
import cmp.app.shared.generated.resources.profile_language
import cmp.app.shared.generated.resources.profile_mic
import cmp.app.shared.generated.resources.profile_mic_body
import cmp.app.shared.generated.resources.profile_privacy
import cmp.app.shared.generated.resources.profile_settings
import cmp.app.shared.generated.resources.profile_sign_out
import cmp.app.shared.generated.resources.profile_subtitle
import cmp.app.shared.generated.resources.profile_terms
import cmp.app.shared.generated.resources.profile_title
import cmp.app.shared.generated.resources.profile_voice
import com.eliteteam.speakingcoach.ui.mock.MockSpeakingData
import com.eliteteam.speakingcoach.ui.theme.AppTheme
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val CardShape = RoundedCornerShape(24.dp)

@Composable
fun ProfileWidget(
    state: ProfileUiState,
    onCaptionsToggled: (Boolean) -> Unit,
    onDailyGoalCycled: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.profile_title),
            style = MaterialTheme.typography.headlineLarge,
            color = scheme.onBackground,
        )
        Text(
            text = stringResource(Res.string.profile_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(scheme.surfaceContainerLowest)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(scheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.avatarLetter,
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.primary,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = state.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onBackground,
                )
                Text(
                    text = state.email,
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
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(Res.string.profile_settings),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            SettingsRow(
                icon = Res.drawable.ic_language,
                title = stringResource(Res.string.profile_language),
                subtitle = state.language,
            )
            SettingsDivider()
            SettingsRow(
                icon = Res.drawable.ic_record_voice_over,
                title = stringResource(Res.string.profile_voice),
                subtitle = state.tutorVoice,
            )
            SettingsDivider()
            SettingsRow(
                icon = Res.drawable.ic_timer,
                title = stringResource(Res.string.profile_daily_goal),
                subtitle = stringResource(Res.string.profile_daily_goal_body),
                trailing = {
                    Text(
                        text = pluralStringResource(
                            Res.plurals.duration_minutes,
                            state.goalMinutes,
                            state.goalMinutes,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.onBackground,
                    )
                },
                onClick = onDailyGoalCycled,
            )
            SettingsDivider()
            SettingsRow(
                icon = Res.drawable.ic_closed_caption,
                title = stringResource(Res.string.profile_captions),
                subtitle = stringResource(Res.string.profile_captions_body),
                trailing = {
                    Switch(
                        checked = state.captionsByDefault,
                        onCheckedChange = onCaptionsToggled,
                        thumbContent = {},
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = scheme.primary,
                        ),
                    )
                },
            )
            SettingsDivider()
            SettingsRow(
                icon = Res.drawable.ic_mic,
                title = stringResource(Res.string.profile_mic),
                subtitle = stringResource(Res.string.profile_mic_body),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(Res.string.profile_about),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            SettingsRow(
                icon = Res.drawable.ic_info,
                title = stringResource(Res.string.profile_help),
            )
            SettingsDivider()
            SettingsRow(
                icon = Res.drawable.ic_description,
                title = stringResource(Res.string.profile_terms),
            )
            SettingsDivider()
            SettingsRow(
                icon = Res.drawable.ic_shield,
                title = stringResource(Res.string.profile_privacy),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(scheme.surfaceContainerLowest)
                .clickable(onClick = onSignOut)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_logout),
                contentDescription = null,
                tint = scheme.error,
            )
            Text(
                text = stringResource(Res.string.profile_sign_out),
                style = MaterialTheme.typography.titleSmall,
                color = scheme.error,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(Res.drawable.ic_chevron_right),
                contentDescription = null,
                tint = scheme.error,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        content = { content() },
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp),
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun SettingsRow(
    icon: DrawableResource,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = {
        Icon(
            painter = painterResource(Res.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    },
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(scheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = scheme.onBackground,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = scheme.onBackground)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

@Preview
@Composable
private fun ProfileWidgetPreview() {
    AppTheme {
        ProfileWidget(
            state = MockSpeakingData.profile,
            onCaptionsToggled = {},
            onDailyGoalCycled = {},
            onSignOut = {},
        )
    }
}
