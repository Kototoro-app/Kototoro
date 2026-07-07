package org.skepsun.kototoro.explore.ui.compose

import org.skepsun.kototoro.main.ui.compose.TopBarOverrideState

data class ExploreSourceSelectionTopBarState(
    val selectedCount: Int,
    val isSingleSelection: Boolean,
    val canPin: Boolean,
    val canUnpin: Boolean,
    val canDisable: Boolean,
    val canDelete: Boolean,
    val markEmptyTitleRes: Int,
    val onClearSelection: () -> Unit,
    val onSettings: () -> Unit,
    val onDisable: () -> Unit,
    val onDelete: () -> Unit,
    val onShortcut: () -> Unit,
    val onPin: () -> Unit,
    val onUnpin: () -> Unit,
    val onToggleEmptyAvailability: () -> Unit,
) : TopBarOverrideState
