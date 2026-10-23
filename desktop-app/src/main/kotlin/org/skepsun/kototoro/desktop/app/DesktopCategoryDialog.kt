package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.desktop.runtime.DesktopLibraryCategory

/**
 * Android edits favourites categories on their own screen (create, rename, reorder, delete). The desktop keeps
 * that in a dialog over the grid so the tab rail and the cards stay visible while the list changes.
 */
@Composable
internal fun DesktopCategoryDialog(controller: DesktopController, state: DesktopAppState, onDismiss: () -> Unit) {
    val categories = state.library.categories
    val enabled = !state.busy
    var newTitle by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var editingTitle by remember { mutableStateOf("") }
    val submitNew = {
        val title = newTitle.trim()
        if (title.isNotEmpty()) {
            controller.createCategory(title)
            newTitle = ""
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(desktopText("library.categoriesTitle")) },
        text = {
            Column(Modifier.width(460.dp).testTag("category-dialog"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f, fill = false).heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (categories.isEmpty()) {
                        Text(desktopText("library.categoriesEmpty"), color = Muted, fontSize = 13.sp)
                    }
                    for ((index, category) in categories.withIndex()) {
                        CategoryRow(controller, category, index, categories.size, enabled,
                            editing = editingId == category.id,
                            editingTitle = editingTitle,
                            onEditingTitle = { editingTitle = it },
                            onStartEditing = { editingId = category.id; editingTitle = category.title },
                            onFinishEditing = { editingId = null })
                    }
                }
                Divider()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(newTitle, { newTitle = it }, singleLine = true,
                        label = { Text(desktopText("library.categoryName")) },
                        enabled = enabled, modifier = Modifier.weight(1f).testTag("category-new-field"))
                    Button(onClick = submitNew, enabled = enabled && newTitle.isNotBlank(),
                        modifier = Modifier.testTag("category-new-add")) {
                        Text(desktopText("library.categoryAdd"))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onDismiss, modifier = Modifier.testTag("category-dialog-close")) {
                Text(desktopText("common.close"))
            }
        },
    )
}

@Composable
private fun CategoryRow(controller: DesktopController, category: DesktopLibraryCategory, index: Int, count: Int,
    enabled: Boolean, editing: Boolean, editingTitle: String, onEditingTitle: (String) -> Unit,
    onStartEditing: () -> Unit, onFinishEditing: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().testTag("category-row:${category.id}")) {
        if (editing) {
            OutlinedTextField(editingTitle, onEditingTitle, singleLine = true, enabled = enabled,
                modifier = Modifier.weight(1f).testTag("category-edit-field:${category.id}"))
            TextButton({
                controller.renameCategory(category.id, editingTitle)
                onFinishEditing()
            }, enabled = enabled && editingTitle.isNotBlank(),
                modifier = Modifier.testTag("category-edit-save:${category.id}")) {
                Text(desktopText("common.save"))
            }
            TextButton(onFinishEditing, modifier = Modifier.testTag("category-edit-cancel:${category.id}")) {
                Text(desktopText("common.cancel"))
            }
        } else {
            Text(category.title, Modifier.weight(1f).testTag("category-name:${category.id}"), maxLines = 1,
                overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            TextButton({ controller.moveCategory(category.id, up = true) },
                enabled = enabled && index > 0, modifier = Modifier.testTag("category-up:${category.id}")) {
                Text(desktopText("library.categoryUp"))
            }
            TextButton({ controller.moveCategory(category.id, up = false) },
                enabled = enabled && index < count - 1, modifier = Modifier.testTag("category-down:${category.id}")) {
                Text(desktopText("library.categoryDown"))
            }
            TextButton(onStartEditing, enabled = enabled,
                modifier = Modifier.testTag("category-rename:${category.id}")) {
                Text(desktopText("library.categoryRename"))
            }
            TextButton({ controller.deleteCategory(category.id) }, enabled = enabled,
                modifier = Modifier.testTag("category-delete:${category.id}")) {
                Text(desktopText("library.categoryDelete"), color = MaterialTheme.colors.error)
            }
        }
    }
}
