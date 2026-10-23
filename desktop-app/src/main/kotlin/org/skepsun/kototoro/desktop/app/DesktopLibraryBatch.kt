package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    enabled: Boolean,
    onSelectAll: () -> Unit,
    onAssignCategories: (() -> Unit)?,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.testTag("library-selection-bar")) {
        Text(
            text = desktopText("library.selectionCount", count),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("library-selection-count"),
        )
        TextButton({ onSelectAll() }, enabled = enabled, modifier = Modifier.testTag("library-selection-all")) {
            Text(desktopText("library.selectionAll"))
        }
        if (onAssignCategories != null) {
            TextButton({ onAssignCategories() }, enabled = enabled,
                modifier = Modifier.testTag("library-selection-categories")) {
                Text(desktopText("library.selectionCategories"))
            }
        }
        TextButton({ onRemove() }, enabled = enabled, modifier = Modifier.testTag("library-selection-remove")) {
            Text(if (history) desktopText("library.selectionRemoveHistory") else desktopText("library.selectionRemove"))
        }
        TextButton({ onCancel() }, modifier = Modifier.testTag("library-selection-cancel")) {
            Text(desktopText("common.cancel"))
        }
    }
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
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                .testTag("library-category-pick-dialog"),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (categories.isEmpty()) Text(desktopText("library.categoriesEmpty"))
                categories.forEach { category ->
                    val checked = category.id in picked
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) {
                            picked = if (checked) picked - category.id else picked + category.id
                        }.testTag("library-category-pick:${category.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                        Text(category.title, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (history) desktopText("library.selectionRemoveHistory") else desktopText("library.selectionRemove"))
        },
        text = {
            Column(Modifier.testTag("library-selection-dialog"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(desktopText("library.selectionCount", count))
                Text(if (history) desktopText("library.selectionRemoveHistoryDetail")
                else desktopText("library.selectionRemoveDetail"))
            }
        },
        confirmButton = {
            Button({ onConfirm() }, enabled = enabled, modifier = Modifier.testTag("library-selection-confirm")) {
                Text(if (history) desktopText("library.selectionRemoveHistory") else desktopText("library.selectionRemove"))
            }
        },
        dismissButton = {
            TextButton({ onDismiss() }, modifier = Modifier.testTag("library-selection-dismiss")) {
                Text(desktopText("common.cancel"))
            }
        },
    )
}
