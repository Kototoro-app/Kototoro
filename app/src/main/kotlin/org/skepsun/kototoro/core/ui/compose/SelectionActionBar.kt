package org.skepsun.kototoro.core.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable

/**
 * The floating "clear / N selected / actions" pill the grids show while a multi-selection is
 * active (detail chapter pages, detail bookmarks).
 *
 * It exists to fix two things that used to be copy-pasted at every call site. Material3's
 * `inverseSurface` is the right plate for a phone (it reads as a snackbar), but the TV chrome is
 * dark glass, where that plate lands as a blinding white card — so TV takes a colour from the
 * surface ramp instead. And the actions are bare `IconButton`s: a remote stops on them while
 * nothing draws an outline, which makes the whole bar invisible once focus leaves the grid, so
 * every action goes through [SelectionBarIconButton].
 *
 * Placement (alignment, insets, slide-in animation) stays at the call site.
 */
@Composable
fun SelectionActionBar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isTvPresentation) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.inverseSurface
        },
        contentColor = if (isTvPresentation) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.inverseOnSurface
        },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionBarIconButton(onClick = onClearSelection) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.clear),
                    )
                }
                Text(
                    text = selectedCount.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Row(content = actions)
        }
    }
}

/** One icon action inside [SelectionActionBar], carrying the TV focus outline. */
@Composable
fun SelectionBarIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        // A bare IconButton keeps the stop but draws no outline.
        modifier = modifier.tvFocusable(shape = RoundedCornerShape(12.dp), addFocusTarget = false),
        content = content,
    )
}
