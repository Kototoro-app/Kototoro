package org.skepsun.kototoro.settings.compose

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.NavItem
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.theme.ArtworkSurfaceRole
import org.skepsun.kototoro.core.ui.theme.LocalBackgroundStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.core.ui.theme.artworkAwareContainerColor
import org.skepsun.kototoro.settings.nav.model.NavItemConfigModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun NavConfigScreen(
    configuredItems: List<NavItemConfigModel>,
    availableItems: List<NavItem>,
    canShowAddAction: Boolean,
    canAddAction: Boolean,
    onAddItem: (NavItem) -> Unit,
    onRemoveItem: (NavItem) -> Unit,
    onMoveItem: (item: NavItem, direction: Int) -> Unit,
    onReorder: (fromPos: Int, toPos: Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    var isAddDialogVisible by remember { mutableStateOf(false) }
    val localItems = remember { mutableStateListOf<NavItemConfigModel>() }

    LaunchedEffect(configuredItems) {
        localItems.clear()
        localItems.addAll(configuredItems)
    }

    val listState = rememberLazyListState()
    var dragInitialIndex by remember { mutableIntStateOf(-1) }
    var dragCurrentIndex by remember { mutableIntStateOf(-1) }

    val reorderableLazyListState = rememberReorderableLazyListState(listState) { from, to ->
        // Note: index 0 in LazyColumn is the section header, so offset by 1
        val fromIndex = from.index - 1
        val toIndex = to.index - 1
        if (fromIndex !in localItems.indices || toIndex !in localItems.indices || fromIndex == toIndex) {
            return@rememberReorderableLazyListState
        }
        if (dragInitialIndex < 0) {
            dragInitialIndex = fromIndex
        }
        localItems.add(toIndex, localItems.removeAt(fromIndex))
        dragCurrentIndex = toIndex
    }

    LaunchedEffect(reorderableLazyListState.isAnyItemDragging) {
        if (!reorderableLazyListState.isAnyItemDragging) {
            if (dragInitialIndex >= 0 && dragCurrentIndex >= 0 && dragInitialIndex != dragCurrentIndex) {
                onReorder(dragInitialIndex, dragCurrentIndex)
            }
            dragInitialIndex = -1
            dragCurrentIndex = -1
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = SettingsContentHorizontalPadding,
                end = SettingsContentHorizontalPadding,
                top = settingsContentTopInset(20.dp),
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "nav_section_header") {
                Text(
                    text = stringResource(R.string.main_screen_sections),
                    style = MaterialTheme.typography.labelLarge,
                    color = settingsSectionLabelColor(),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }

            items(
                items = localItems,
                key = { it.item.name },
            ) { config ->
                ReorderableItem(
                    state = reorderableLazyListState,
                    key = config.item.name,
                ) { isDragging ->
                    val elevation by animateDpAsState(
                        targetValue = if (isDragging) 8.dp else 0.dp,
                        label = "nav_elevation",
                    )
                    val tokens = LocalInterfaceStyleTokens.current
                    val shape = RoundedCornerShape(tokens.settingsGroupOuterCornerRadius)
                    val isArtworkBackground = LocalBackgroundStyle.current.usesArtworkBackdrop
                    val containerColor = when {
                        isDragging -> MaterialTheme.colorScheme.secondaryContainer
                        isArtworkBackground -> MaterialTheme.colorScheme.surfaceContainerLow.artworkAwareContainerColor(ArtworkSurfaceRole.Card)
                        else -> MaterialTheme.colorScheme.surfaceContainer
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .longPressDraggableHandle(),
                        shape = shape,
                        color = containerColor,
                        tonalElevation = elevation,
                        shadowElevation = elevation,
                    ) {
                        NavConfigPreferenceRow(
                            item = config,
                            canMoveUp = localItems.indexOf(config) > 0,
                            canMoveDown = localItems.indexOf(config) < localItems.lastIndex,
                            isDragging = isDragging,
                            handleModifier = Modifier.draggableHandle(),
                            onMove = { direction ->
                                val idx = localItems.indexOf(config)
                                val target = (idx + direction).coerceIn(localItems.indices)
                                if (idx != target) {
                                    localItems.add(target, localItems.removeAt(idx))
                                    onReorder(idx, target)
                                }
                            },
                            onRemove = { onRemoveItem(config.item) },
                        )
                    }
                }
            }

            if (canShowAddAction) {
                item(key = "nav_add_action") {
                    val tokens = LocalInterfaceStyleTokens.current
                    val isArtworkBackground = LocalBackgroundStyle.current.usesArtworkBackdrop
                    val containerColor = if (isArtworkBackground) {
                        MaterialTheme.colorScheme.surfaceContainerLow.artworkAwareContainerColor(ArtworkSurfaceRole.Card)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(tokens.settingsGroupOuterCornerRadius),
                        color = containerColor,
                    ) {
                        SettingsActionPreference(
                            title = stringResource(
                                if (canAddAction) R.string.add else R.string.items_limit_exceeded,
                            ),
                            iconRes = R.drawable.ic_add,
                            enabled = canAddAction,
                            showChevron = false,
                            onClick = { isAddDialogVisible = true },
                        )
                    }
                }
            }
        }
    }

    if (isAddDialogVisible) {
        SettingsAlertDialog(
            onDismissRequest = { isAddDialogVisible = false },
            title = stringResource(R.string.add),
            text = {
                val addListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
                LazyColumn(
                    state = addListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    item {
                        SettingsItemGroup(itemCount = availableItems.size) { index ->
                            val item = availableItems[index]
                            SettingsActionPreference(
                                title = stringResource(item.title),
                                iconRes = item.icon,
                                showChevron = false,
                                onClick = {
                                    onAddItem(item)
                                    isAddDialogVisible = false
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                SettingsDialogActionButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = { isAddDialogVisible = false },
                )
            },
        )
    }
}

@Composable
private fun NavConfigPreferenceRow(
    item: NavItemConfigModel,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    isDragging: Boolean,
    handleModifier: Modifier = Modifier,
    onMove: (direction: Int) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnMove by rememberUpdatedState(onMove)
    val reorderLabel = stringResource(R.string.reorder)
    val moveUpLabel = stringResource(R.string.move_up)
    val moveDownLabel = stringResource(R.string.move_down)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = rememberSafePainter(item.item.icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(item.item.title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.disabledHintResId != 0) {
                Text(
                    text = stringResource(item.disabledHintResId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .semantics {
                    contentDescription = reorderLabel
                    customActions = buildList {
                        if (canMoveUp) {
                            add(CustomAccessibilityAction(moveUpLabel) {
                                currentOnMove(-1)
                                true
                            })
                        }
                        if (canMoveDown) {
                            add(CustomAccessibilityAction(moveDownLabel) {
                                currentOnMove(1)
                                true
                            })
                        }
                    }
                }
                .then(handleModifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_reorder_handle),
                contentDescription = null,
                tint = if (isDragging) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.remove),
            )
        }
    }
}
