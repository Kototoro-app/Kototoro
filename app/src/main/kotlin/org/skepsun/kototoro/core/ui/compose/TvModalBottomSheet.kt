package org.skepsun.kototoro.core.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig

/**
 * [ModalBottomSheet] whose Material drag handle is not a focus stop on a television.
 *
 * The stock handle carries touch drag semantics, and Compose puts it on the focus path as well:
 * a remote stops on it and CENTER does nothing, because dragging a sheet with a remote is not a
 * thing. On TV the handle is replaced by a 12dp spacer; everywhere else it stays untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        dragHandle = if (LocalUiPresentationConfig.current.isTv) {
            { Box(modifier = Modifier.fillMaxWidth().height(12.dp)) }
        } else {
            { BottomSheetDefaults.DragHandle() }
        },
        content = content,
    )
}
