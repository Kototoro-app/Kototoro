package org.skepsun.kototoro.core.ui.adaptive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Expanded Android details: equally sized metadata and independently scrolling chapter panes. */
@Composable
fun TabletDetailsPanes(
    modifier: Modifier = Modifier,
    summary: @Composable (Modifier) -> Unit,
    chapters: (@Composable (Modifier) -> Unit)? = null,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        summary(Modifier.weight(1f).fillMaxHeight())
        chapters?.invoke(Modifier.weight(1f).fillMaxHeight())
    }
}
