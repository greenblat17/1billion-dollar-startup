package com.eliteteam.speakingcoach.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cmp.app.shared.generated.resources.Res
import cmp.app.shared.generated.resources.auth_email
import cmp.app.shared.generated.resources.auth_error_invalid
import cmp.app.shared.generated.resources.auth_error_network
import cmp.app.shared.generated.resources.auth_error_taken
import cmp.app.shared.generated.resources.auth_name
import cmp.app.shared.generated.resources.auth_password
import cmp.app.shared.generated.resources.auth_submit_login
import cmp.app.shared.generated.resources.auth_submit_register
import cmp.app.shared.generated.resources.cd_back
import cmp.app.shared.generated.resources.welcome_headline
import cmp.app.shared.generated.resources.welcome_headline_accent
import com.eliteteam.speakingcoach.ui.components.PrimaryButton
import com.eliteteam.speakingcoach.ui.components.RelevaLogo
import com.eliteteam.speakingcoach.ui.theme.AppTheme
import com.eliteteam.speakingcoach.ui.welcome.WelcomeAtmosphere
import org.jetbrains.compose.resources.stringResource

@Composable
fun AuthWidget(
    state: AuthUiState,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onDisplayNameChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    WelcomeAtmosphere(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.Start),
            ) {
                Text(
                    text = stringResource(Res.string.cd_back),
                    color = scheme.onSurfaceVariant,
                )
            }
            RelevaLogo()
            Spacer(Modifier.height(32.dp))
            Text(
                text = stringResource(Res.string.welcome_headline),
                style = MaterialTheme.typography.headlineLarge,
                color = scheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(Res.string.welcome_headline_accent),
                style = MaterialTheme.typography.headlineLarge,
                color = scheme.primary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.register) {
                    AuthField(
                        value = state.displayName,
                        onValueChange = onDisplayNameChanged,
                        label = stringResource(Res.string.auth_name),
                        keyboardType = KeyboardType.Text,
                        capitalization = KeyboardCapitalization.Words,
                    )
                }
                AuthField(
                    value = state.email,
                    onValueChange = onEmailChanged,
                    label = stringResource(Res.string.auth_email),
                    keyboardType = KeyboardType.Email,
                )
                AuthField(
                    value = state.password,
                    onValueChange = onPasswordChanged,
                    label = stringResource(Res.string.auth_password),
                    keyboardType = KeyboardType.Password,
                    password = true,
                )
            }
            val error = state.error
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(error.message()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1f))
            PrimaryButton(
                text = stringResource(
                    if (state.register) Res.string.auth_submit_register else Res.string.auth_submit_login,
                ),
                onClick = onSubmit,
                enabled = !state.busy,
                trailingArrow = true,
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    password: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            keyboardType = keyboardType,
        ),
        shape = RoundedCornerShape(20.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = scheme.surfaceContainerLowest,
            unfocusedContainerColor = scheme.surfaceContainerLowest,
            focusedBorderColor = scheme.primary,
            unfocusedBorderColor = scheme.outline,
        ),
    )
}

private fun AuthError.message() = when (this) {
    AuthError.Invalid -> Res.string.auth_error_invalid
    AuthError.EmailTaken -> Res.string.auth_error_taken
    AuthError.Network -> Res.string.auth_error_network
}

@Preview
@Composable
private fun AuthWidgetRegisterPreview() {
    AppTheme {
        AuthWidget(
            state = AuthUiState(register = true),
            onEmailChanged = {},
            onPasswordChanged = {},
            onDisplayNameChanged = {},
            onSubmit = {},
            onBack = {},
        )
    }
}
