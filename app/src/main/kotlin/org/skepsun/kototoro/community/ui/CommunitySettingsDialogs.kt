package org.skepsun.kototoro.community.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.community.nostr.CommunityProfiles

/** The services page's community dialogs; [onCopied] lets the page confirm the copy in its own snackbar. */
@Composable
fun CommunitySettingsDialogs(
    dialog: CommunityDialog?,
    viewModel: CommunitySettingsViewModel,
    onCopied: () -> Unit,
) {
    when (dialog) {
        null -> Unit
        is CommunityDialog.Nickname -> {
            var name by rememberSaveable(dialog.saved) { mutableStateOf(dialog.saved) }
            val trimmed = name.trim()
            AlertDialog(
                onDismissRequest = { if (!dialog.busy) viewModel.dismiss() },
                title = { Text(stringResource(R.string.community_nickname)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(stringResource(R.string.community_nickname)) },
                            enabled = !dialog.busy,
                            singleLine = true,
                            isError = trimmed.length > CommunityProfiles.MAX_NAME_LENGTH,
                            modifier = Modifier.fillMaxWidth().testTag("community-nickname-input"),
                        )
                        val status = when {
                            dialog.busy -> R.string.community_nickname_working
                            dialog.status == NicknameStatus.PUBLISHED -> R.string.community_nickname_published
                            dialog.status == NicknameStatus.PENDING -> R.string.community_nickname_pending
                            dialog.status == NicknameStatus.FAILED -> R.string.community_nickname_error
                            else -> R.string.community_nickname_summary
                        }
                        Text(stringResource(status))
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.saveNickname(trimmed) },
                        enabled = !dialog.busy && trimmed != dialog.saved &&
                            trimmed.length in 1..CommunityProfiles.MAX_NAME_LENGTH,
                    ) { Text(stringResource(R.string.community_nickname_save)) }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismiss, enabled = !dialog.busy) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }
        CommunityDialog.Onboarding -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(stringResource(R.string.community_onboarding_title)) },
            text = { Text(stringResource(R.string.community_onboarding_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::createIdentity, modifier = Modifier.testTag("community-onboarding-create")) {
                    Text(stringResource(R.string.community_onboarding_create))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::startRestore, modifier = Modifier.testTag("community-onboarding-restore")) {
                        Text(stringResource(R.string.community_onboarding_restore))
                    }
                    TextButton(onClick = viewModel::dismiss) { Text(stringResource(android.R.string.cancel)) }
                }
            },
        )
        is CommunityDialog.Restore -> {
            var text by rememberSaveable { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = viewModel::dismiss,
                title = { Text(stringResource(R.string.community_recovery_key)) },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(stringResource(R.string.community_restore_hint)) },
                        isError = dialog.invalid,
                        supportingText = if (dialog.invalid) {
                            { Text(stringResource(R.string.community_restore_invalid)) }
                        } else {
                            null
                        },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth().testTag("community-restore-input"),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.restore(text) }, enabled = text.isNotBlank()) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismiss) { Text(stringResource(android.R.string.cancel)) }
                },
            )
        }
        is CommunityDialog.RecoveryKey -> {
            val clipboard = LocalClipboardManager.current
            AlertDialog(
                onDismissRequest = viewModel::dismiss,
                title = { Text(stringResource(R.string.community_recovery_key)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.community_recovery_key_message))
                        SelectionContainer {
                            Text(
                                text = dialog.key,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.testTag("community-recovery-key"),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = viewModel::dismiss) { Text(stringResource(android.R.string.ok)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(dialog.key))
                        onCopied()
                    }) { Text(stringResource(R.string.copy)) }
                },
            )
        }
    }
}
