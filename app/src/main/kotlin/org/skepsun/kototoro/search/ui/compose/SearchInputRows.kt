package org.skepsun.kototoro.search.ui.compose


import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.skepsun.kototoro.core.ui.topbar.SourceResultsTopBar
import org.skepsun.kototoro.core.ui.topbar.TopBarActionItem
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
import org.skepsun.kototoro.core.ui.adaptive.tvIconButtonRing
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.CompactTopBarItemSpacing
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu
import org.skepsun.kototoro.main.ui.compose.TopBarControlSurface
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.adaptive.tvTextInputFocusEscape
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens

import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.ui.SpaceSwitcherIcon
import org.skepsun.kototoro.parsers.model.ContentTag
import androidx.compose.ui.tooling.preview.Preview
import org.skepsun.kototoro.core.ui.theme.KototoroTheme

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
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier.tvFocusable(shape = CircleShape, addFocusTarget = false),
        ) {
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
                .tvTextInputFocusEscape()
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
            IconButton(onClick = { onValueChange("") }, modifier = Modifier.tvIconButtonRing()) {
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
    val isTv = LocalUiPresentationConfig.current.isTv
    SourceResultsTopBar(
        title = sourceTitle,
        onBack = onBackClick,
        backIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
        backLabel = stringResource(R.string.back),
        actions = listOf(
            TopBarActionItem(
                key = "filter",
                icon = painterResource(R.drawable.ic_filter_menu),
                label = currentSortLabel,
                onClick = onFilterClick,
                badge = isFilterApplied,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            TopBarActionItem(
                key = "search",
                icon = rememberVectorPainter(Icons.Default.Search),
                label = stringResource(R.string.search),
                onClick = onSearchClick,
            ),
            TopBarActionItem(
                key = "random",
                icon = painterResource(R.drawable.ic_dice),
                label = stringResource(R.string.random),
                onClick = onRandomClick,
                enabled = !isRandomLoading,
                minWidth = 420.dp,
            ),
            TopBarActionItem(
                key = "display",
                icon = painterResource(listMode.iconRes()),
                label = stringResource(R.string.display_options),
                onClick = onShowDisplayOptionsSheet,
                minWidth = 476.dp,
                dividerBefore = true,
            ),
            TopBarActionItem(
                key = "settings",
                icon = painterResource(R.drawable.ic_settings),
                label = stringResource(R.string.settings),
                onClick = onSettingsClick,
                minWidth = 532.dp,
            ),
        ) + listOfNotNull(
            TopBarActionItem(
                key = "reset",
                icon = rememberVectorPainter(Icons.Default.Clear),
                label = stringResource(R.string.reset_filter),
                onClick = onResetFilterClick,
                overflowOnly = true,
                dividerBefore = true,
            ).takeIf { isFilterApplied },
        ),
        moreIcon = rememberVectorPainter(Icons.Default.MoreVert),
        moreLabel = stringResource(R.string.more),
        surface = { modifier, content -> TopBarControlSurface(modifier = modifier, content = content) },
        menuHost = { expanded, onDismiss, anchor, content ->
            GlassDropdownMenu(
                expanded = expanded,
                onDismissRequest = onDismiss,
                offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = 4.dp),
                shape = RoundedRectangle(28.dp),
                style = GlassDefaults.subtleStyle(),
                useRootOverlay = LocalInterfaceStyle.current == InterfaceStyle.IOS,
                anchorBounds = anchor.bounds,
                anchorFocusRequester = anchor.focusRequester,
                content = content,
            )
        },
        modifier = Modifier
            .padding(horizontal = CompactTopBarHorizontalPadding)
            .then(if (isTv) Modifier.focusGroup() else Modifier),
        height = tokens.mainTopBarHeight,
        controlSize = controlSize,
        iconSize = iconSize,
        itemSpacing = CompactTopBarItemSpacing,
        chromeAlpha = topBarAlpha,
        capsuleLeading = activeSpaceId?.let { spaceId ->
            {
                IconButton(
                    onClick = onSpaceSwitcherClick,
                    modifier = Modifier
                        .size(controlSize)
                        .tvFocusable(shape = CircleShape, addFocusTarget = false),
                ) {
                    SpaceSwitcherIcon(
                        activeSpaceId = spaceId,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        },
    )
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

@Preview(showBackground = true)
@Composable
private fun PinnedRowPillPreview() {
    KototoroTheme {
        PinnedRowPill(
            selected = true,
            onClick = {},
            content = { Text("Filter") },
        )
    }
}
