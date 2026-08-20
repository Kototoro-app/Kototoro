package org.skepsun.kototoro.search.ui.compose


import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import com.kyant.shapes.RoundedRectangle
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.CompactTopBarItemSpacing
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu
import org.skepsun.kototoro.main.ui.compose.TopBarControlSurface
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens

import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.ui.SpaceSwitcherIcon
import org.skepsun.kototoro.parsers.model.ContentTag

internal fun buildSourcePinnedTags(
    contentItems: List<ContentListModel>,
    selectedTags: Set<ContentTag>,
    availableTags: List<ContentTag>,
    limit: Int = 16,
): List<ContentTag> {
    val counts = LinkedHashMap<ContentTag, Int>()
    contentItems.forEach { item ->
        item.manga.tags.forEach { tag ->
            counts[tag] = (counts[tag] ?: 0) + 1
        }
    }
    val frequencyOrdered = counts.entries
        .sortedWith(compareByDescending<Map.Entry<ContentTag, Int>> { it.value }.thenBy { it.key.title })
        .map { it.key }
    val fallbackTags = availableTags
        .asSequence()
        .distinct()
        .filterNot { counts.containsKey(it) }
        .sortedBy { it.title }
        .toList()
    return buildList(limit) {
        selectedTags
            .sortedBy { it.title }
            .forEach { tag ->
                if (tag !in this) {
                    add(tag)
                }
            }
        frequencyOrdered.forEach { tag ->
            if (size >= limit) return@forEach
            if (tag !in this) {
                add(tag)
            }
        }
        fallbackTags.forEach { tag ->
            if (size >= limit) return@forEach
            if (tag !in this) {
                add(tag)
            }
        }
    }
}

@Composable
internal fun SearchInputRow(
    value: String,
    onValueChange: (String) -> Unit,
    onClose: () -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
) {
    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.close),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .padding(horizontal = 6.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.merge(
                TextStyle(color = MaterialTheme.colorScheme.onSurface),
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.search),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = stringResource(R.string.clear),
                )
            }
        }
    }
}

@Composable
internal fun SourceListTopActionsRow(
    sourceTitle: String,
    currentSortLabel: String,
    topBarAlpha: Float,
    listMode: ListMode,
    gridSize: Int,
    isFilterApplied: Boolean,
    isRandomLoading: Boolean,
    activeSpaceId: SpaceId?,
    onBackClick: () -> Unit,
    onSpaceSwitcherClick: () -> Unit,
    onSearchClick: () -> Unit,
    onRandomClick: () -> Unit,
    onFilterClick: () -> Unit,
    onResetFilterClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onListModeChange: (ListMode) -> Unit,
    onGridSizeChange: (Int) -> Unit,
    onShowDisplayOptionsSheet: () -> Unit,
) {
    val tokens = LocalInterfaceStyleTokens.current
    val controlSize = tokens.topBarButtonSize
    val iconSize = tokens.topBarIconSize
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
    ) {
        val maxWidthDp = maxWidth.value
        val showRandomDirect = maxWidthDp >= 420f
        val showDisplayDirect = maxWidthDp >= 476f
        val showSettingsDirect = maxWidthDp >= 532f
        val shouldShowOverflow = !showRandomDirect || !showDisplayDirect || !showSettingsDirect || isFilterApplied

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(tokens.mainTopBarHeight)
                .padding(horizontal = CompactTopBarHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CompactTopBarItemSpacing),
        ) {
            TopBarControlSurface(
                modifier = Modifier.wrapContentWidth(),
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.size(controlSize),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
            Text(
                text = sourceTitle,
                modifier = Modifier
                    .weight(1f)
                    .alpha(topBarAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            TopBarControlSurface(
                modifier = Modifier.wrapContentWidth(),
            ) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides controlSize) {
                    Row(
                        modifier = Modifier
                            .height(controlSize)
                            .padding(horizontal = 2.dp)
                            .alpha(topBarAlpha),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        activeSpaceId?.let { spaceId ->
                            IconButton(
                                onClick = onSpaceSwitcherClick,
                                modifier = Modifier.size(controlSize),
                            ) {
                                SpaceSwitcherIcon(
                                    activeSpaceId = spaceId,
                                    modifier = Modifier.size(iconSize),
                                )
                            }
                        }
                        BadgedBox(
                            badge = {
                                if (isFilterApplied) {
                                    Badge()
                                }
                            },
                        ) {
                            IconButton(onClick = onFilterClick, modifier = Modifier.size(controlSize)) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_filter_menu),
                                    contentDescription = currentSortLabel,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(iconSize),
                                )
                            }
                        }

                        IconButton(onClick = onSearchClick, modifier = Modifier.size(controlSize)) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.search),
                                modifier = Modifier.size(iconSize),
                            )
                        }

                        if (showRandomDirect) {
                            IconButton(
                                onClick = onRandomClick,
                                enabled = !isRandomLoading,
                                modifier = Modifier.size(controlSize),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_dice),
                                    contentDescription = stringResource(R.string.random),
                                    modifier = Modifier.size(iconSize),
                                )
                            }
                        }

                        if (showDisplayDirect) {
                            IconButton(
                                onClick = onShowDisplayOptionsSheet,
                                modifier = Modifier.size(controlSize),
                            ) {
                                Icon(
                                    painter = painterResource(listMode.iconRes()),
                                    contentDescription = stringResource(R.string.list_options),
                                    modifier = Modifier.size(iconSize),
                                )
                            }
                        }

                        if (showSettingsDirect) {
                            IconButton(onClick = onSettingsClick, modifier = Modifier.size(controlSize)) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_settings),
                                    contentDescription = stringResource(R.string.settings),
                                    modifier = Modifier.size(iconSize),
                                )
                            }
                        }

                        if (shouldShowOverflow) {
                            MoreActionsButton(
                                showRandomAction = !showRandomDirect,
                                showDisplayActions = !showDisplayDirect,
                                showSettingsAction = !showSettingsDirect,
                                listMode = listMode,
                                gridSize = gridSize,
                                isFilterApplied = isFilterApplied,
                                isRandomLoading = isRandomLoading,
                                onRandomClick = onRandomClick,
                                onResetFilterClick = onResetFilterClick,
                                onSettingsClick = onSettingsClick,
                                onShowDisplayOptionsSheet = onShowDisplayOptionsSheet,
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun MoreActionsButton(
    showRandomAction: Boolean,
    showDisplayActions: Boolean,
    showSettingsAction: Boolean,
    listMode: ListMode,
    gridSize: Int,
    isFilterApplied: Boolean,
    isRandomLoading: Boolean,
    onRandomClick: () -> Unit,
    onResetFilterClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onShowDisplayOptionsSheet: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }
    val tokens = LocalInterfaceStyleTokens.current

    Box(
        modifier = Modifier.onGloballyPositioned { anchorBounds = it.boundsInRoot() },
    ) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(tokens.topBarButtonSize)) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.more),
                modifier = Modifier.size(tokens.topBarIconSize),
            )
        }
        GlassDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = 4.dp),
            shape = RoundedRectangle(28.dp),
            style = GlassDefaults.subtleStyle(),
            useRootOverlay = LocalInterfaceStyle.current == InterfaceStyle.IOS,
            anchorBounds = anchorBounds,
        ) {
            if (showRandomAction) {
                CompactDropdownMenuItem(
                    text = { Text(stringResource(R.string.random)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_dice),
                            contentDescription = null,
                        )
                    },
                    enabled = !isRandomLoading,
                    onClick = {
                        expanded = false
                        onRandomClick()
                    },
                )
            }

            if (showSettingsAction) {
                CompactDropdownMenuItem(
                    text = { Text(stringResource(R.string.settings)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSettingsClick()
                    },
                )
            }

            if (showDisplayActions) {
                HorizontalDivider()
                CompactDropdownMenuItem(
                    text = { Text(stringResource(R.string.display_options)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_grid),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        expanded = false
                        onShowDisplayOptionsSheet()
                    },
                )
            }

            if (isFilterApplied) {
                HorizontalDivider()
                CompactDropdownMenuItem(
                    text = { Text(stringResource(R.string.reset_filter)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        expanded = false
                        onResetFilterClick()
                    },
                )
            }
        }
    }
}

@Composable
internal fun CollapsingBarSlot(
    visibleHeight: Dp,
    fullHeight: Dp,
    content: @Composable () -> Unit,
) {
    if (visibleHeight <= 0.dp) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(visibleHeight)
            .then(
                if (visibleHeight < fullHeight) {
                    Modifier.clipToBounds()
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.BottomStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(fullHeight),
        ) {
            content()
        }
    }
}

@Composable
internal fun QuickFilterPinnedRow(
    quickFilter: QuickFilter,
    activeQuery: String?,
    onClearActiveQuery: () -> Unit,
    onQuickFilterOptionClick: (ListFilterOption) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = CompactTopBarHorizontalPadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(CompactTopBarItemSpacing),
        modifier = Modifier.fillMaxWidth(),
    ) {
        activeQuery?.takeIf { it.isNotBlank() }?.let { query ->
            item(key = "active_query") {
                ActiveQueryChip(query = query, onClear = onClearActiveQuery)
            }
        }
        items(quickFilter.items) { chip ->
            val isSelected = chip.isChecked
            val option = chip.data as? ListFilterOption
            PinnedRowPill(
                selected = isSelected,
                enabled = option != null,
                onClick = {
                    if (option != null) {
                        onQuickFilterOptionClick(option)
                    }
                },
                leading = if (chip.icon != 0) {
                    {
                        Icon(
                            painter = painterResource(chip.icon),
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                } else {
                    null
                },
            ) {
                Text(
                    text = when {
                        chip.titleResId != 0 -> stringResource(chip.titleResId)
                        chip.title != null -> chip.title.toString()
                        else -> ""
                    }.let { title ->
                        if (chip.counter > 0) "$title ${chip.counter}" else title
                    },
                    maxLines = 1,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
internal fun SourceTagsPinnedRow(
    tags: List<ContentTag>,
    selectedTags: Set<ContentTag>,
    activeQuery: String?,
    onClearActiveQuery: () -> Unit,
    onToggleTag: (ContentTag, Boolean) -> Unit,
) {
    if (tags.isEmpty() && activeQuery.isNullOrBlank()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = CompactTopBarHorizontalPadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(CompactTopBarItemSpacing),
        modifier = Modifier.fillMaxWidth(),
    ) {
        activeQuery?.takeIf { it.isNotBlank() }?.let { query ->
            item(key = "active_query") {
                ActiveQueryChip(query = query, onClear = onClearActiveQuery)
            }
        }
        itemsIndexed(
            items = tags,
            key = { index, tag -> sourceTagChipKey(tag, index) },
        ) { _, tag ->
            val isSelected = tag in selectedTags
            PinnedRowPill(
                selected = isSelected,
                onClick = { onToggleTag(tag, !isSelected) },
            ) {
                Text(
                    text = tag.title,
                    maxLines = 1,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun ActiveQueryChip(
    query: String,
    onClear: () -> Unit,
) {
    PinnedRowPill(
        selected = true,
        onClick = onClear,
        leading = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
            )
        },
        trailing = {
            Icon(
                imageVector = Icons.Default.Clear,
                contentDescription = stringResource(R.string.clear),
                modifier = Modifier.size(13.dp),
            )
        },
    ) {
        Text(
            text = query,
            maxLines = 1,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun PinnedRowPill(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val contentColor = (
        if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    ).copy(alpha = if (enabled) 1f else 0.56f)
    val selectedOverlayColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.56f)
    } else {
        Color.Transparent
    }

    TopBarControlSurface(
        modifier = modifier
            .height(SearchPinnedChipHeight)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .height(SearchPinnedChipHeight)
                .background(selectedOverlayColor, CircleShape)
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides contentColor,
                ) {
                    leading?.invoke()
                    content()
                    trailing?.invoke()
                }
            }
        }
    }
}

private fun sourceTagChipKey(
    tag: ContentTag,
    index: Int,
): String = "${tag.source.name}:${tag.key}:${tag.title}:$index"

