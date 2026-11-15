package org.skepsun.kototoro.explore.ui.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.skepsun.kototoro.core.ui.compose.EmptyStatePanel
import org.skepsun.kototoro.core.extensions.SourceEmptyHintKind
import org.skepsun.kototoro.list.ui.model.EmptyHint

/**
 * Renders the reason the Explore source section is empty, with the next step for that reason.
 *
 * [EmptyHint] used to be produced by the view model and dropped by this page, so the tab showed a
 * skeleton and then a blank area; the two kinds share the copy but not the action, which is why the
 * button is routed from [EmptyHint.kind] rather than from the string it displays.
 */
@Composable
internal fun ExploreSourcesEmptyHint(
    hint: EmptyHint,
    onCreateShortcut: () -> Unit,
    onResetFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (icon, onAction) = when (hint.kind) {
        SourceEmptyHintKind.HIDDEN_BY_FILTERS -> Icons.Outlined.SearchOff to onResetFilters
        else -> Icons.Outlined.Widgets to onCreateShortcut
    }
    EmptyStatePanel(
        icon = icon,
        title = stringResource(hint.textPrimary),
        message = stringResource(hint.textSecondary),
        actionLabel = hint.actionStringRes.takeIf { it != 0 }?.let { stringResource(it) },
        onAction = onAction,
        modifier = modifier,
    )
}
