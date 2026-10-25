package org.skepsun.kototoro.core.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig

/**
 * [ModalBottomSheet] whose Material drag handle is not a focus stop on a television.
 *
 * The stock handle carries touch drag semantics, and Compose puts it on the focus path as well:
 * a remote stops on it and CENTER does nothing, because dragging a sheet with a remote is not a
 * thing. On TV the handle is replaced by a 12dp spacer; everywhere else it stays untouched.
 *
 * Everything else is passed through unchanged, so a sheet only has to swap the call name to stop
 * offering the handle as a dead stop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        scrimColor = scrimColor,
        dragHandle = if (LocalUiPresentationConfig.current.isTv) {
            { Box(modifier = Modifier.fillMaxWidth().height(12.dp)) }
        } else {
            { BottomSheetDefaults.DragHandle() }
        },
        content = content,
    )
}
