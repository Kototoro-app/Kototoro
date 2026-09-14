package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import org.skepsun.kototoro.core.ui.compose.StableAnchoredBottomSheet
import org.skepsun.kototoro.reader.ui.compose.design.ReaderControlTokens

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderAnchoredBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    scrimColor: Color = Color.Black.copy(alpha = 0.42f),
    dragHandle: (@Composable () -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable (dragModifier: Modifier) -> Unit,
) {
    StableAnchoredBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetMaxWidth = ReaderControlTokens.SheetMaxWidth,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        scrimColor = scrimColor,
        dragHandle = dragHandle,
        content = content,
    )
}
