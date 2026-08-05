package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.skepsun.kototoro.core.ui.compose.StableAnchoredBottomSheet

@Composable
internal fun ReaderAnchoredBottomSheet(
	onDismissRequest: () -> Unit,
	modifier: Modifier = Modifier,
	content: @Composable (dragModifier: Modifier) -> Unit,
) {
	StableAnchoredBottomSheet(
		onDismissRequest = onDismissRequest,
		modifier = modifier,
		content = content,
	)
}
