package org.skepsun.kototoro.list.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.CompactTopBarItemSpacing
import org.skepsun.kototoro.core.ui.compose.CompactTopBarPillShape
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuText
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu
import org.skepsun.kototoro.main.ui.compose.TopBarControlSurface

enum class SelectionAction {
    SELECT_ALL,
    SHARE,
    FAVOURITE,
    SAVE,
    EDIT_OVERRIDE,
    FIX,
    REMOVE,
    PIN,
    MARK_AS_COMPLETED,
}

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
    if (includeContextualActions) {
        if (isAllNonLocal && SelectionAction.FIX !in inlineActions && SelectionAction.FIX !in overflowActions) {
            overflowActions += SelectionAction.FIX
        }
        if (isSingleSelection &&
            SelectionAction.EDIT_OVERRIDE !in inlineActions &&
            SelectionAction.EDIT_OVERRIDE !in overflowActions
        ) {
            overflowActions += SelectionAction.EDIT_OVERRIDE
        }
    }

    val tokens = LocalInterfaceStyleTokens.current
    val topBarControlHeight = tokens.topBarButtonSize
    val topBarIconSize = tokens.topBarIconSize
    val statusBarPadding = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (includeStatusBarPadding) {
                    Modifier.padding(top = statusBarPadding.calculateTopPadding())
                } else {
                    Modifier
                },
            )
            .height(tokens.mainTopBarHeight)
            .padding(horizontal = CompactTopBarHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(CompactTopBarItemSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: independent close button container.
        TopBarControlSurface {
            IconButton(
                onClick = onClearSelection,
                modifier = Modifier.size(topBarControlHeight),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close),
                    modifier = Modifier.size(topBarIconSize),
                )
            }
        }
        Text(
            text = selectedCount.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(start = 2.dp, end = 4.dp),
        )
        Spacer(modifier = Modifier.weight(1f))

        // Right: operation buttons combination container (a pill capsule like the
        // main top bar's action group).
        TopBarControlSurface(
            pressFeedbackEnabled = false,
        ) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides topBarControlHeight) {
                Row(
                    modifier = Modifier
                        .widthIn(min = topBarControlHeight)
                        .height(topBarControlHeight)
                        .padding(start = 2.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    inlineActions.forEach { action ->
                        if (action != SelectionAction.SAVE || isAllNonLocal) {
                            SelectionActionIconButton(
                                action = action,
                                allPinned = allPinned,
                                removeActionIconRes = removeActionIconRes,
                                removeActionTitleRes = removeActionTitleRes,
                                onClick = { onActionClick(action) },
                                buttonSize = topBarControlHeight,
                                iconSize = topBarIconSize,
                            )
                        }
                    }

                    // Overflow menu - shows actions beyond the inline set, plus FIX/EDIT_OVERRIDE.
                    if (overflowActions.isNotEmpty()) {
                        Box(
                            modifier = Modifier.onGloballyPositioned { overflowAnchorBounds = it.boundsInRoot() },
                        ) {
                            IconButton(
                                onClick = { showOverflowMenu = true },
                                modifier = Modifier.size(topBarControlHeight),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.more),
                                    modifier = Modifier.size(topBarIconSize),
                                )
                            }
                            GlassDropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                                offset = DpOffset(x = 0.dp, y = 4.dp),
                                alignToAnchorEnd = true,
                                useRootOverlay = LocalInterfaceStyle.current == InterfaceStyle.IOS,
                                anchorTapThrough = true,
                                anchorBounds = overflowAnchorBounds,
                                shape = CompactTopBarPillShape,
                                style = GlassDefaults.subtleStyle(),
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
                }
            }
        }
    }
}

@Composable
private fun SelectionActionIconButton(
    action: SelectionAction,
    allPinned: Boolean,
    removeActionIconRes: Int?,
    removeActionTitleRes: Int?,
    onClick: () -> Unit,
    buttonSize: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(buttonSize),
    ) {
        when (action) {
            SelectionAction.SELECT_ALL -> {
                Icon(
                    painter = painterResource(id = R.drawable.ic_select_all),
                    contentDescription = stringResource(R.string.select_all),
                    modifier = Modifier.size(iconSize),
                )
            }
            SelectionAction.PIN -> {
                Icon(
                    painter = painterResource(id = if (allPinned) R.drawable.ic_unpin else R.drawable.ic_pin),
                    contentDescription = if (allPinned) {
                        stringResource(R.string.unpin)
                    } else {
                        stringResource(R.string.pin)
                    },
                    modifier = Modifier.size(iconSize),
                )
            }
            SelectionAction.REMOVE -> {
                if (removeActionIconRes != null) {
                    Icon(
                        painter = painterResource(removeActionIconRes),
                        contentDescription = selectionActionTitle(action, allPinned, removeActionTitleRes),
                        modifier = Modifier.size(iconSize),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = selectionActionTitle(
                            action,
                            allPinned,
                            removeActionTitleRes,
                            fixActionTitleRes = null,
                        ),
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
            SelectionAction.SAVE -> {
                Icon(
                    painter = painterResource(id = R.drawable.ic_download),
                    contentDescription = stringResource(R.string.download),
                    modifier = Modifier.size(iconSize),
                )
            }
            SelectionAction.FAVOURITE -> {
                Icon(
                    painter = painterResource(id = R.drawable.ic_heart_outline),
                    contentDescription = stringResource(R.string.categories),
                    modifier = Modifier.size(iconSize),
                )
            }
            SelectionAction.SHARE -> {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = stringResource(R.string.share),
                    modifier = Modifier.size(iconSize),
                )
            }
            SelectionAction.MARK_AS_COMPLETED -> {
                Icon(
                    painter = painterResource(id = R.drawable.ic_eye_check),
                    contentDescription = stringResource(R.string.mark_as_completed),
                    modifier = Modifier.size(iconSize),
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
