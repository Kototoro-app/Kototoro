package org.skepsun.kototoro.list.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton
import org.skepsun.kototoro.core.ui.settings.PreferenceDialog
import org.skepsun.kototoro.core.ui.settings.PreferenceDialogContent
import org.skepsun.kototoro.core.ui.text.uiString

/** Android's destructive-action prompt; hosts own the pending selection and commit/dismiss operations. */
@Composable
fun ContentActionConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    selectionLabel: String? = null,
    cancelLabel: String = uiString("cancel"),
    enabled: Boolean = true,
    tagPrefix: String = "content-confirm",
    renderDialog: @Composable (PreferenceDialogContent) -> Unit = { PreferenceDialog(it) },
) {
    renderDialog(PreferenceDialogContent(title = title, onDismiss = onDismiss,
        text = {
            Column(Modifier.testTag("$tagPrefix-dialog"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selectionLabel != null) Text(selectionLabel)
                Text(message)
            }
        },
        confirmButton = {
            TvTextButton(onConfirm, enabled = enabled, modifier = Modifier.testTag("$tagPrefix-confirm")) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TvTextButton(onDismiss, modifier = Modifier.testTag("$tagPrefix-dismiss")) { Text(cancelLabel) }
        },
    ))
}
