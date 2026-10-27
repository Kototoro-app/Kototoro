package org.skepsun.kototoro.core.ui.compose

import androidx.compose.runtime.Composable

/** Android keeps its existing entry point; the editing session and presentation are shared with desktop. */
@Composable
fun ReorderDialog(
    items: List<ReorderItem>,
    onDismissRequest: () -> Unit,
    onSave: suspend (List<String>) -> Unit,
) = ReorderEditorDialog(items, onDismissRequest, onSave)
