package org.skepsun.kototoro.list.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuText
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle

enum class SelectionAction {
    SELECT_ALL,
    SHARE,
    FAVOURITE,
    SAVE,
    EDIT_OVERRIDE,
    FIX,
    REMOVE,
    PIN,
    MARK_AS_COMPLETED
}

@OptIn(ExperimentalMaterial3Api::class)
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
    onClearSelection: () -> Unit,
    onActionClick: (SelectionAction) -> Unit,
    modifier: Modifier = Modifier
) {
    var showOverflowMenu by remember { mutableStateOf(false) }
    var overflowAnchorBounds by remember { mutableStateOf<Rect?>(null) }

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
    if (isAllNonLocal && SelectionAction.FIX !in inlineActions && SelectionAction.FIX !in overflowActions) {
        overflowActions += SelectionAction.FIX
    }
    if (isSingleSelection &&
        SelectionAction.EDIT_OVERRIDE !in inlineActions &&
        SelectionAction.EDIT_OVERRIDE !in overflowActions
    ) {
        overflowActions += SelectionAction.EDIT_OVERRIDE
    }

    TopAppBar(
        title = { Text(text = selectedCount.toString()) },
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(Icons.Default.Close, contentDescription = "Clear Selection")
            }
        },
        actions = {
            inlineActions.forEach { action ->
                if (action != SelectionAction.SAVE || isAllNonLocal) {
                    SelectionActionIconButton(
                        action = action,
                        allPinned = allPinned,
                        removeActionIconRes = removeActionIconRes,
                        removeActionTitleRes = removeActionTitleRes,
                        onClick = { onActionClick(action) },
                    )
                }
            }

            // Overflow menu - shows actions beyond the first 4 inline, plus FIX/EDIT_OVERRIDE/FAVOURITE
            val hasOverflow = overflowActions.isNotEmpty()
            if (hasOverflow) {
                Box(
                    modifier = Modifier.onGloballyPositioned { overflowAnchorBounds = it.boundsInRoot() },
                ) {
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    GlassDropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false },
                        useRootOverlay = LocalInterfaceStyle.current == InterfaceStyle.IOS,
                        anchorBounds = overflowAnchorBounds,
                    ) {
                        overflowActions.forEach { action ->
                            CompactDropdownMenuItem(
                                text = {
                                    CompactDropdownMenuText(
                                        text = selectionActionTitle(
                                            action = action,
                                            allPinned = allPinned,
                                            removeActionTitleRes = removeActionTitleRes,
                                            fixActionTitleRes = fixActionTitleRes,
                                        ),
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    onActionClick(action)
                                },
                            )
                        }
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            titleContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun SelectionActionIconButton(
    action: SelectionAction,
    allPinned: Boolean,
    removeActionIconRes: Int?,
    removeActionTitleRes: Int?,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        when (action) {
            SelectionAction.SELECT_ALL -> {
                Icon(
                    painter = painterResource(id = R.drawable.ic_select_all),
                    contentDescription = stringResource(R.string.select_all),
                )
            }
            SelectionAction.PIN -> {
                Icon(
                    painter = painterResource(id = if (allPinned) R.drawable.ic_unpin else R.drawable.ic_pin),
                    contentDescription = if (allPinned) stringResource(R.string.unpin) else stringResource(R.string.pin),
                )
            }
            SelectionAction.REMOVE -> {
                if (removeActionIconRes != null) {
                    Icon(
                        painter = painterResource(removeActionIconRes),
                        contentDescription = selectionActionTitle(action, allPinned, removeActionTitleRes),
                    )
                } else {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = selectionActionTitle(
                            action,
                            allPinned,
                            removeActionTitleRes,
                            fixActionTitleRes = null,
                        ),
                    )
                }
            }
            SelectionAction.SAVE -> {
                Icon(painter = painterResource(id = R.drawable.ic_download), contentDescription = stringResource(R.string.download))
            }
            SelectionAction.FAVOURITE -> {
                Icon(
                    painter = painterResource(id = R.drawable.ic_heart_outline),
                    contentDescription = stringResource(R.string.categories),
                )
            }
            SelectionAction.SHARE -> {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share))
            }
            SelectionAction.MARK_AS_COMPLETED -> {
                Icon(
                    painter = painterResource(id = R.drawable.ic_eye_check),
                    contentDescription = stringResource(R.string.mark_as_completed),
                )
            }
            SelectionAction.EDIT_OVERRIDE,
            SelectionAction.FIX -> Unit
        }
    }
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
        SelectionAction.FIX -> stringResource(fixActionTitleRes ?: R.string.fix)
        SelectionAction.REMOVE -> stringResource(removeActionTitleRes ?: R.string.remove)
        SelectionAction.PIN -> if (allPinned) stringResource(R.string.unpin) else stringResource(R.string.pin)
        SelectionAction.MARK_AS_COMPLETED -> stringResource(R.string.mark_as_completed)
    }
}
