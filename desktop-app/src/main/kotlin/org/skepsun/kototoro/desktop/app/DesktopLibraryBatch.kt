package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.list.ui.compose.ContentSelectionToolbar
import org.skepsun.kototoro.list.ui.compose.SelectionAction
import org.skepsun.kototoro.list.ui.compose.SelectionToolbarItem
import org.skepsun.kototoro.list.ui.compose.ContentActionConfirmationDialog
import org.skepsun.kototoro.list.ui.compose.CategoryChoice
import org.skepsun.kototoro.list.ui.compose.CategoryChoicesContent
import androidx.compose.ui.state.ToggleableState
import org.skepsun.kototoro.core.library.SourceLibraryCategory

/**
 * Android's selection toolbar, which favourites and history gain on long press: a bar that replaces the normal one
 * while a selection exists, holding the count, select-all and the batch actions of that shelf. Windows had per-work
 * actions only, so clearing a category or a history page meant repeating the same click per card.
 *
 * The bar never mutates anything itself — the panel owns the selection and shows the dialogs, so a batch action is
 * always one confirmation away from a mutation.
 */
@Composable
internal fun DesktopLibrarySelectionBar(
    count: Int,
    history: Boolean,
    allPinned: Boolean,
    enabled: Boolean,
    onSelectAll: () -> Unit,
    onAssignCategories: (() -> Unit)?,
    onMarkCompleted: () -> Unit,
    onTogglePin: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onMigrate: (() -> Unit)?,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    val operations = buildMap<SelectionAction, () -> Unit> {
        put(SelectionAction.SELECT_ALL, onSelectAll)
        if (onAssignCategories != null) put(SelectionAction.FAVOURITE, onAssignCategories)
        put(SelectionAction.MARK_AS_COMPLETED, onMarkCompleted)
        if (onTogglePin != null) put(SelectionAction.PIN, onTogglePin)
        if (onShare != null) put(SelectionAction.SHARE, onShare)
        put(SelectionAction.SAVE, onDownload)
        if (onMigrate != null) put(SelectionAction.MIGRATE, onMigrate)
        put(SelectionAction.REMOVE, onRemove)
    }
    ContentSelectionToolbar(
        countLabel = desktopText("library.selectionCount", count),
        items = operations.keys.map { action ->
            val (title, tag) = when (action) {
                SelectionAction.SELECT_ALL -> desktopText("library.selectionAll") to "all"
                SelectionAction.FAVOURITE -> desktopText("library.selectionCategories") to "categories"
                SelectionAction.MARK_AS_COMPLETED -> desktopText("library.selectionComplete") to "complete"
                SelectionAction.PIN -> (if (allPinned) desktopText("library.selectionUnpin")
                    else desktopText("library.selectionPin")) to "pin"
                SelectionAction.SHARE -> desktopText("library.selectionShare") to "share"
                SelectionAction.SAVE -> desktopText("library.selectionDownload") to "download"
                SelectionAction.MIGRATE -> desktopText("library.migrate") to "migrate"
                SelectionAction.REMOVE -> (if (history) desktopText("library.selectionRemoveHistory")
                    else desktopText("library.selectionRemove")) to "remove"
                SelectionAction.EDIT_OVERRIDE -> error("Not offered by the desktop library")
            }
            val icon = when (action) {
                SelectionAction.PIN -> androidx.compose.ui.res.painterResource(
                    if (allPinned) "icons/ic_unpin.svg" else "icons/ic_pin.svg")
                SelectionAction.SAVE -> androidx.compose.ui.res.painterResource("icons/ic_download.svg")
                SelectionAction.REMOVE -> if (history) androidx.compose.ui.graphics.vector.rememberVectorPainter(
                    org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons.Delete)
                    else androidx.compose.ui.res.painterResource("icons/ic_heart_outline.svg")
                else -> null
            }
            SelectionToolbarItem(action, title, icon, enabled = action != SelectionAction.MIGRATE || count == 1,
                tag = "library-selection-$tag")
        },
        preferredInline = listOf(SelectionAction.PIN, SelectionAction.REMOVE, SelectionAction.SAVE),
        onClear = onCancel,
        onAction = { operations[it]?.invoke() },
        enabled = enabled,
        tagPrefix = "library-selection",
        surface = { _, body -> DesktopControlSurface(Modifier,
            androidx.compose.foundation.shape.RoundedCornerShape(50), body) },
    )
}

/**
 * The fallback for a clipboard that refuses the write: the same share text, selectable, so the links are never
 * lost just because Windows would not hand the clipboard over.
 */
@Composable
internal fun DesktopShareLinksDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(desktopText("library.selectionShare")) },
        text = {
            SelectionContainer(Modifier.testTag("library-share-dialog")) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton({ onDismiss() }, modifier = Modifier.testTag("library-share-close")) {
                Text(desktopText("common.close"))
            }
        },
    )
}

/**
 * Android's batch "categories" dialog. The checked set becomes the membership of every selected work — ticking adds,
 * unticking removes — which is why it commits on confirm instead of on each box.
 */
@Composable
internal fun DesktopCategoryPickerDialog(
    count: Int,
    categories: List<SourceLibraryCategory>,
    initial: Set<Long>,
    enabled: Boolean,
    onConfirm: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(desktopText("library.categoriesPickTitle", count)) },
        text = {
            CategoryChoicesContent(
                choices = categories.map { CategoryChoice(it.id, it.title,
                    if (it.id in picked) ToggleableState.On else ToggleableState.Off) },
                onToggle = { id, checked -> picked = if (checked) picked + id else picked - id },
                enabled = enabled,
                emptyMessage = desktopText("library.categoriesEmpty"),
                tagPrefix = "library-category-pick",
            )
        },
        confirmButton = {
            Button({ onConfirm(picked) }, enabled = enabled,
                modifier = Modifier.testTag("library-category-pick-confirm")) {
                Text(desktopText("common.save"))
            }
        },
        dismissButton = {
            TextButton({ onDismiss() }, modifier = Modifier.testTag("library-selection-dismiss")) {
                Text(desktopText("common.cancel"))
            }
        },
    )
}

/** The batch removal confirmation; favourites keep their progress, a removed history record does not. */
@Composable
internal fun DesktopRemoveSelectionDialog(count: Int, history: Boolean, enabled: Boolean,
    onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val title = if (history) desktopText("library.selectionRemoveHistory") else desktopText("library.selectionRemove")
    ContentActionConfirmationDialog(title = title,
        message = if (history) desktopText("library.selectionRemoveHistoryDetail") else desktopText("library.selectionRemoveDetail"),
        confirmLabel = title, selectionLabel = desktopText("library.selectionCount", count),
        cancelLabel = desktopText("common.cancel"), enabled = enabled,
        onConfirm = onConfirm, onDismiss = onDismiss, tagPrefix = "library-selection")
}

/**
 * Android's "mark as completed" prompt. The action replaces the stored reading position with the last chapter, which is
 * why Android warns that the current progress is lost; the desktop dialog says the same before it commits.
 */
@Composable
internal fun DesktopMarkCompletedDialog(count: Int, enabled: Boolean,
    onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ContentActionConfirmationDialog(title = desktopText("library.selectionComplete"),
        message = desktopText("library.completePrompt"), confirmLabel = desktopText("library.selectionComplete"),
        selectionLabel = desktopText("library.selectionCount", count), cancelLabel = desktopText("common.cancel"),
        enabled = enabled, onConfirm = onConfirm, onDismiss = onDismiss, tagPrefix = "library-complete")
}

/**
 * Android's batch SAVE sheet: the details menu's chapter scopes offered once for the whole selection, each work
 * keeping its own progress and its own saved chapters. The dialog only names the scopes — the counts would differ
 * per work, and the queued total is reported in the message afterwards instead of being guessed here.
 */
@Composable
internal fun DesktopLibraryDownloadDialog(
    count: Int,
    options: List<DesktopDownloadOption>,
    enabled: Boolean,
    onDownload: (DesktopDownloadOption) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(desktopText("details.downloadChapters")) },
        text = {
            Column(Modifier.testTag("library-download-dialog"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(desktopText("library.selectionCount", count))
                Text(desktopText("library.downloadHint"), style = MaterialTheme.typography.bodyMedium)
                options.forEach { option ->
                    TextButton({ onDownload(option) }, enabled = enabled,
                        modifier = Modifier.testTag(desktopLibraryDownloadTag(option))) {
                        Text(desktopLibraryDownloadLabel(option))
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ onDismiss() }, modifier = Modifier.testTag("library-download-dismiss")) {
                Text(desktopText("common.cancel"))
            }
        },
    )
}
