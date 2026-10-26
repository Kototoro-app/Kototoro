package org.skepsun.kototoro.settings.sources.unified

import org.skepsun.kototoro.core.ui.extensions.ExtensionManagementTab

import org.skepsun.kototoro.core.ui.extensions.ExtensionManagementTabs


import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.draw.clip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import org.skepsun.kototoro.core.ui.adaptive.tvIconButtonRing
import org.skepsun.kototoro.core.ui.compose.TvDropdownMenuItem
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.CompactTopBarItemSpacing
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.settings.compose.SettingsAlertDialog
import org.skepsun.kototoro.settings.compose.SettingsCompactSearchField
import org.skepsun.kototoro.settings.compose.SettingsDialogActionButton
import org.skepsun.kototoro.settings.compose.SettingsTopBarIconButton
import org.skepsun.kototoro.settings.compose.SettingsTopBarSurface
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import androidx.compose.ui.tooling.preview.Preview
import org.skepsun.kototoro.core.ui.theme.KototoroTheme

@Composable
private fun ToolbarSearchIconButton(
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(contentAlignment = Alignment.TopEnd) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(40.dp).tvIconButtonRing(),
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = stringResource(R.string.search),
                modifier = Modifier.size(20.dp),
                tint = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (active) {
            Surface(
                modifier = Modifier.size(8.dp),
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {}
        }
    }
}

@Composable
private fun ToolbarFilterIconButton(
    iconRes: Int,
    activeCount: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(contentAlignment = Alignment.TopEnd) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(40.dp).tvIconButtonRing(),
        ) {
            Icon(
                painter = rememberSafePainter(iconRes),
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
                tint = if (activeCount > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (activeCount > 0) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Text(
                    text = activeCount.coerceAtMost(9).toString(),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
fun UnifiedSourcesSearchTopBar(
    readyState: UnifiedSourcesUiState.Ready?,
    onNavigateUp: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onFilterClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onNavigateUp)
    val tokens = LocalInterfaceStyleTokens.current
    val filterActiveCount = (readyState?.filters?.languages?.size ?: 0) + (readyState?.filters?.otherFilterCount() ?: 0)
    SettingsTopBarSurface {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(tokens.secondaryTopBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsTopBarIconButton(onClick = onNavigateUp) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(android.R.string.cancel),
                    modifier = Modifier.size(tokens.topBarIconSize),
                )
            }
            Spacer(modifier = Modifier.width(CompactTopBarItemSpacing))
            SettingsCompactSearchField(
                query = readyState?.filters?.query.orEmpty(),
                onQueryChange = onSearchQueryChange,
                modifier = Modifier.weight(1f),
                autofocus = true,
            )
            Spacer(modifier = Modifier.width(CompactTopBarItemSpacing))
            ToolbarFilterIconButton(
                iconRes = R.drawable.ic_filter_menu,
                activeCount = filterActiveCount,
                contentDescription = stringResource(R.string.filter),
                onClick = onFilterClick,
            )
        }
    }
}

@Composable
fun UnifiedSourcesSearchTopBar(
    readyState: UnifiedSourcesUiState.Ready?,
    onNavigateUp: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onLanguageFilterClick: () -> Unit,
    onMoreFiltersClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    UnifiedSourcesSearchTopBar(
        readyState = readyState,
        onNavigateUp = onNavigateUp,
        onSearchQueryChange = onSearchQueryChange,
        onFilterClick = onMoreFiltersClick,
        modifier = modifier,
    )
}

@Composable
fun UnifiedSourcesActionCapsule(
    readyState: UnifiedSourcesUiState.Ready?,
    onSearchClick: () -> Unit,
    onFilterClick: () -> Unit,
    onReorderClick: (() -> Unit)? = null,
    reorderEnabled: Boolean = true,
    onGroupsClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalInterfaceStyleTokens.current
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val filterActiveCount = (readyState?.filters?.languages?.size ?: 0) + (readyState?.filters?.otherFilterCount() ?: 0)
    val isSearchActive = readyState?.filters?.query?.isNotBlank() == true

    Surface(
        modifier = modifier
            .height(tokens.topBarButtonSize)
            .wrapContentWidth(),
        shape = RoundedCornerShape(tokens.topBarButtonSize / 2),
        color = if (isIosStyle) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f)
        },
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            // Reorder and groups share one overflow menu so the capsule stays narrow and the
            // tab capsule next to it keeps enough room.
            var menuExpanded by remember { mutableStateOf(false) }
            val hasMenu = onReorderClick != null || onGroupsClick != null
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    enabled = hasMenu,
                    modifier = Modifier.size(tokens.topBarButtonSize - 4.dp).tvIconButtonRing(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.more),
                        modifier = Modifier.size(tokens.topBarIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    TvDropdownMenuItem(
                        text = { Text(stringResource(R.string.reorder)) },
                        enabled = onReorderClick != null && reorderEnabled,
                        leadingIcon = {
                            Icon(
                                painter = rememberSafePainter(R.drawable.ic_reorder_handle),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onReorderClick?.invoke()
                        },
                    )
                    TvDropdownMenuItem(
                        text = { Text(stringResource(R.string.source_groups_title)) },
                        enabled = onGroupsClick != null,
                        leadingIcon = {
                            Icon(imageVector = Icons.Outlined.Folder, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onGroupsClick?.invoke()
                        },
                    )
                }
            }
            // Search button
            Box(
                modifier = Modifier
                    .size(tokens.topBarButtonSize - 4.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onSearchClick),
                contentAlignment = Alignment.Center,
            ) {
                Box(contentAlignment = Alignment.TopEnd) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.search),
                        modifier = Modifier.size(tokens.topBarIconSize),
                        tint = if (isSearchActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (isSearchActive) {
                        Surface(
                            modifier = Modifier.size(7.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                        ) {}
                    }
                }
            }

            // Merged Filter button
            Box(
                modifier = Modifier
                    .size(tokens.topBarButtonSize - 4.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onFilterClick),
                contentAlignment = Alignment.Center,
            ) {
                Box(contentAlignment = Alignment.TopEnd) {
                    Icon(
                        painter = rememberSafePainter(R.drawable.ic_filter_menu),
                        contentDescription = stringResource(R.string.filter),
                        modifier = Modifier.size(tokens.topBarIconSize),
                        tint = if (filterActiveCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (filterActiveCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(percent = 50),
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.offset(x = 6.dp, y = (-4).dp),
                        ) {
                            Text(
                                text = filterActiveCount.coerceAtMost(99).toString(),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 3.5.dp, vertical = 0.5.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UnifiedSourcesToolbarActions(
    readyState: UnifiedSourcesUiState.Ready?,
    onSearchClick: () -> Unit,
    onFilterClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    UnifiedSourcesActionCapsule(
        readyState = readyState,
        onSearchClick = onSearchClick,
        onFilterClick = onFilterClick,
        modifier = modifier,
    )
}

@Composable
fun UnifiedSourcesToolbarActions(
    readyState: UnifiedSourcesUiState.Ready?,
    onSearchClick: () -> Unit,
    onLanguageFilterClick: () -> Unit,
    onMoreFiltersClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    UnifiedSourcesActionCapsule(
        readyState = readyState,
        onSearchClick = onSearchClick,
        onFilterClick = onMoreFiltersClick,
        modifier = modifier,
    )
}

@Composable
internal fun UnifiedFilterGroupDialog(
    title: String,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    content: @Composable () -> Unit,
) {
    SettingsAlertDialog(
        title = title,
        onDismissRequest = onDismiss,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
            }
        },
        confirmButton = {
                SettingsDialogActionButton(
                    text = stringResource(R.string.done),
                    onClick = onDismiss,
                )
            },
            dismissButton = {
                SettingsDialogActionButton(
                    text = stringResource(R.string.clear),
                    onClick = onClear,
                )
            },
    )
}

@Composable
fun UnifiedSourcesTopBarTabs(
    pagerState: PagerState,
    onTabClick: (Int) -> Unit,
    readyState: UnifiedSourcesUiState.Ready?,
    modifier: Modifier = Modifier,
) {
    UnifiedSourcesTopBarTabs(pagerState.currentPage.coerceIn(0, UNIFIED_SOURCES_TAB_COUNT - 1),
        onTabClick, readyState, modifier)
}

@Composable
fun UnifiedSourcesTopBarTabs(
    selectedTab: Int,
    onTabClick: (Int) -> Unit,
    readyState: UnifiedSourcesUiState.Ready?,
    modifier: Modifier = Modifier,
) {
    val sources = readyState?.sources?.size ?: 0
    val repositories = readyState?.repositories?.size ?: 0
    val packages = readyState?.packages?.size ?: 0
    ExtensionManagementTabs(
        tabs = listOf(
            ExtensionManagementTab(
                stringResource(R.string.sources_tab_title, sources), sources),
            ExtensionManagementTab(
                stringResource(R.string.repositories_tab_title, repositories), repositories),
            ExtensionManagementTab(
                stringResource(R.string.packages_tab_title, packages), packages, readyState?.packageUpdateCount ?: 0),
        ),
        selectedTab = selectedTab,
        onTabClick = onTabClick,
        modifier = modifier,
        height = LocalInterfaceStyleTokens.current.topBarButtonSize,
        isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS,
    )
}

@Preview(showBackground = true)
@Composable
private fun UnifiedSourcesSearchTopBarPreview() {
    KototoroTheme {
        UnifiedSourcesSearchTopBar(
            readyState = null,
            onNavigateUp = {},
            onSearchQueryChange = {},
            onLanguageFilterClick = {},
            onMoreFiltersClick = {},
        )
    }
}
