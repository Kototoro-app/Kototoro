package org.skepsun.kototoro.list.ui.compose

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.CompactTopBarPillShape
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu
import org.skepsun.kototoro.main.ui.compose.TopBarControlSurface

/**
 * Long-press selection top bar shared by every content list page. It mirrors the main
 * top bar chrome: an independent pill container for the close button on the left and a
 * combined pill container holding the operation buttons on the right.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KototoroSelectionTopBar(
    selectedCount: Int,
    isAllNonLocal: Boolean,
    isSingleSelection: Boolean,
    showRemoveOption: Boolean = false,
    supportedActions: Set<SelectionAction>? = null,
    allPinned: Boolean = false,
    preferredInlineActions: List<SelectionAction>? = null,
    removeActionIconRes: Int? = null,
    removeActionTitleRes: Int? = null,
    fixActionTitleRes: Int? = null,
    includeContextualActions: Boolean = true,
    includeStatusBarPadding: Boolean = true,
    onClearSelection: () -> Unit,
    onActionClick: (SelectionAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val allActions = supportedActions?.toList() ?: defaultSelectionActions(showRemoveOption)
    val inlineActions = preferredInlineActions
        ?.filter { action -> action in allActions }
        ?: defaultInlineSelectionActions(
            allActions = allActions,
            supportedActions = supportedActions,
            showRemoveOption = showRemoveOption,
        )
    val overflowActions = if (supportedActions == null) {
        mutableListOf()
    } else {
        allActions.filterTo(mutableListOf()) { it !in inlineActions }
    }
    if (includeContextualActions) {
        if (isAllNonLocal && SelectionAction.MIGRATE !in inlineActions && SelectionAction.MIGRATE !in overflowActions) {
            overflowActions += SelectionAction.MIGRATE
        }
        if (isSingleSelection &&
            SelectionAction.EDIT_OVERRIDE !in inlineActions &&
            SelectionAction.EDIT_OVERRIDE !in overflowActions
        ) {
            overflowActions += SelectionAction.EDIT_OVERRIDE
        }
    }

    val statusBarPadding = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues()
    val availableActions = (inlineActions.filter { it != SelectionAction.SAVE || isAllNonLocal } + overflowActions).distinct()
    ContentSelectionToolbar(
        countLabel = selectedCount.toString(),
        items = availableActions.map { action ->
            SelectionToolbarItem(action,
                selectionActionTitle(action, allPinned, removeActionTitleRes, fixActionTitleRes),
                selectionActionPainter(action, allPinned, removeActionIconRes))
        },
        preferredInline = inlineActions,
        onClear = onClearSelection,
        onAction = onActionClick,
        modifier = modifier.then(if (includeStatusBarPadding) {
            Modifier.padding(top = statusBarPadding.calculateTopPadding())
        } else Modifier).padding(horizontal = CompactTopBarHorizontalPadding),
        surface = { feedback, body -> TopBarControlSurface(pressFeedbackEnabled = feedback, content = body) },
        renderOverflow = { menu ->
            GlassDropdownMenu(expanded = menu.expanded, onDismissRequest = menu.onDismiss,
                offset = DpOffset(x = 0.dp, y = 4.dp), alignToAnchorEnd = true,
                useRootOverlay = LocalInterfaceStyle.current == InterfaceStyle.IOS, anchorTapThrough = true,
                anchorBounds = menu.anchorBounds, anchorFocusRequester = menu.anchorFocus,
                shape = CompactTopBarPillShape, style = GlassDefaults.subtleStyle(), content = menu.content)
        },
    )
}

@Composable
private fun selectionActionPainter(action: SelectionAction, allPinned: Boolean,
    removeActionIconRes: Int?): androidx.compose.ui.graphics.painter.Painter? = when (action) {
    SelectionAction.SELECT_ALL -> painterResource(R.drawable.ic_select_all)
    SelectionAction.PIN -> painterResource(if (allPinned) R.drawable.ic_unpin else R.drawable.ic_pin)
    SelectionAction.REMOVE -> if (removeActionIconRes != null) painterResource(removeActionIconRes)
        else androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Default.Delete)
    SelectionAction.SAVE -> painterResource(R.drawable.ic_download)
    SelectionAction.FAVOURITE -> painterResource(R.drawable.ic_heart_outline)
    SelectionAction.SHARE -> androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Default.Share)
    SelectionAction.MARK_AS_COMPLETED -> painterResource(R.drawable.ic_eye_check)
    SelectionAction.EDIT_OVERRIDE, SelectionAction.MIGRATE -> null
}

private fun defaultSelectionActions(showRemoveOption: Boolean): List<SelectionAction> = buildList {
    add(SelectionAction.SELECT_ALL)
    add(SelectionAction.PIN)
    if (showRemoveOption) {
        add(SelectionAction.REMOVE)
    }
    add(SelectionAction.SAVE)
    add(SelectionAction.FAVOURITE)
    add(SelectionAction.SHARE)
    add(SelectionAction.MARK_AS_COMPLETED)
}

private fun defaultInlineSelectionActions(
    allActions: List<SelectionAction>,
    supportedActions: Set<SelectionAction>?,
    showRemoveOption: Boolean,
): List<SelectionAction> {
    if (supportedActions == null) {
        return allActions
    }
    return buildList {
        addAll(allActions.take(4))
        if (showRemoveOption && SelectionAction.REMOVE in allActions && SelectionAction.REMOVE !in this) {
            add(SelectionAction.REMOVE)
        }
        if (SelectionAction.FAVOURITE in allActions && SelectionAction.FAVOURITE !in this) {
            add(SelectionAction.FAVOURITE)
        }
    }
}

@Composable
private fun selectionActionTitle(
    action: SelectionAction,
    allPinned: Boolean,
    removeActionTitleRes: Int?,
    fixActionTitleRes: Int? = null,
): String {
    return when (action) {
        SelectionAction.SELECT_ALL -> stringResource(R.string.select_all)
        SelectionAction.SHARE -> stringResource(R.string.share)
        SelectionAction.FAVOURITE -> stringResource(R.string.categories)
        SelectionAction.SAVE -> stringResource(R.string.download)
        SelectionAction.EDIT_OVERRIDE -> stringResource(R.string.edit)
        SelectionAction.MIGRATE -> stringResource(fixActionTitleRes ?: R.string.migrate)
        SelectionAction.REMOVE -> stringResource(removeActionTitleRes ?: R.string.remove)
        SelectionAction.PIN -> if (allPinned) stringResource(R.string.unpin) else stringResource(R.string.pin)
        SelectionAction.MARK_AS_COMPLETED -> stringResource(R.string.mark_as_completed)
    }
}
