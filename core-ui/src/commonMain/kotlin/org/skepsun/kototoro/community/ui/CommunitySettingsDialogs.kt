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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.community.nostr.CommunityProfiles
import org.skepsun.kototoro.core.ui.text.uiString

/** Which community dialog the services page shows. */
sealed interface CommunityDialog {
    data object Onboarding : CommunityDialog
    data class Restore(val invalid: Boolean = false) : CommunityDialog
    data class RecoveryKey(val key: String) : CommunityDialog
    data class Nickname(val saved: String = "", val busy: Boolean = false, val status: NicknameStatus? = null) : CommunityDialog
}

enum class NicknameStatus { PUBLISHED, PENDING, FAILED }

/** What the services page's community dialogs ask the host to do. */
interface CommunityDialogActions {
    fun dismiss()
    fun createIdentity()
    fun startRestore()
    fun restore(recoveryKey: String)
    fun saveNickname(nickname: String)

    /** Puts the recovery key on the clipboard; the host confirms it in its own way. */
    fun copyRecoveryKey(key: String)
}

/**
 * Turning the community on (plan §8): the first time, the reader sees what becomes public and chooses a new identity or
 * an existing recovery key; then the recovery key to keep, and the public nickname.
 */
@Composable
fun CommunitySettingsDialogs(dialog: CommunityDialog?, actions: CommunityDialogActions) {
    when (dialog) {
        null -> Unit
        is CommunityDialog.Nickname -> {
            var name by rememberSaveable(dialog.saved) { mutableStateOf(dialog.saved) }
            val trimmed = name.trim()
            AlertDialog(
                onDismissRequest = { if (!dialog.busy) actions.dismiss() },
                title = { Text(uiString("community_nickname")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(uiString("community_nickname")) },
                            enabled = !dialog.busy,
                            singleLine = true,
                            isError = trimmed.length > CommunityProfiles.MAX_NAME_LENGTH,
                            modifier = Modifier.fillMaxWidth().testTag("community-nickname-input"),
                        )
                        val status = when {
                            dialog.busy -> "community_nickname_working"
                            dialog.status == NicknameStatus.PUBLISHED -> "community_nickname_published"
                            dialog.status == NicknameStatus.PENDING -> "community_nickname_pending"
                            dialog.status == NicknameStatus.FAILED -> "community_nickname_error"
                            else -> "community_nickname_summary"
                        }
                        Text(uiString(status), modifier = Modifier.testTag("community-nickname-status"))
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { actions.saveNickname(trimmed) },
                        enabled = !dialog.busy && trimmed != dialog.saved &&
                            trimmed.length in 1..CommunityProfiles.MAX_NAME_LENGTH,
                        modifier = Modifier.testTag("community-nickname-save"),
                    ) { Text(uiString("community_nickname_save")) }
                },
                dismissButton = {
                    TextButton(onClick = actions::dismiss, enabled = !dialog.busy) { Text(uiString("cancel")) }
                },
            )
        }
        CommunityDialog.Onboarding -> AlertDialog(
            onDismissRequest = actions::dismiss,
            title = { Text(uiString("community_onboarding_title")) },
            text = { Text(uiString("community_onboarding_message")) },
            confirmButton = {
                TextButton(onClick = actions::createIdentity, modifier = Modifier.testTag("community-onboarding-create")) {
                    Text(uiString("community_onboarding_create"))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = actions::startRestore, modifier = Modifier.testTag("community-onboarding-restore")) {
                        Text(uiString("community_onboarding_restore"))
                    }
                    TextButton(onClick = actions::dismiss) { Text(uiString("cancel")) }
                }
            },
        )
        is CommunityDialog.Restore -> {
            var text by rememberSaveable { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = actions::dismiss,
                title = { Text(uiString("community_recovery_key")) },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(uiString("community_restore_hint")) },
                        isError = dialog.invalid,
                        supportingText = if (dialog.invalid) {
                            { Text(uiString("community_restore_invalid")) }
                        } else {
                            null
                        },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth().testTag("community-restore-input"),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { actions.restore(text) },
                        enabled = text.isNotBlank(),
                        modifier = Modifier.testTag("community-restore-confirm"),
                    ) { Text(uiString("ok")) }
                },
                dismissButton = { TextButton(onClick = actions::dismiss) { Text(uiString("cancel")) } },
            )
        }
        is CommunityDialog.RecoveryKey -> AlertDialog(
            onDismissRequest = actions::dismiss,
            title = { Text(uiString("community_recovery_key")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(uiString("community_recovery_key_message"))
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
                TextButton(onClick = actions::dismiss, modifier = Modifier.testTag("community-recovery-key-ok")) {
                    Text(uiString("ok"))
                }
            },
            dismissButton = {
                TextButton(onClick = { actions.copyRecoveryKey(dialog.key) }) { Text(uiString("copy")) }
            },
        )
    }
}
