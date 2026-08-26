package org.skepsun.kototoro.explore.ui.compose

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.collection.LongSet
import androidx.collection.longSetOf
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver
import org.skepsun.kototoro.core.model.ContentSourceAvailability
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.model.getSummary
import org.skepsun.kototoro.core.model.unwrap
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.parser.external.ExternalContentSource
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.CompactTopBarItemSpacing
import org.skepsun.kototoro.core.ui.compose.CompactTopBarPillShape
import org.skepsun.kototoro.core.ui.compose.iconResForUi
import org.skepsun.kototoro.core.ui.compose.performSelectionHapticFeedback
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.ui.util.ReversibleActionObserver
import org.skepsun.kototoro.explore.ui.ExploreViewModel
import org.skepsun.kototoro.explore.ui.model.ContentSourceItem
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuText
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu
import org.skepsun.kototoro.main.ui.compose.TopBarControlSurface

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun KototoroExploreSourcesScreen(
    viewModel: ExploreViewModel,
    contentPadding: PaddingValues,
    appRouter: AppRouter,
    modifier: Modifier = Modifier,
) {
    val items by viewModel.content.collectAsStateWithLifecycle()
    var composeSelectionIds: LongSet by remember { mutableStateOf(longSetOf()) }
    val hapticFeedback = LocalHapticFeedback.current
    val isGrid by viewModel.isGrid.collectAsStateWithLifecycle()

    val activity = LocalContext.current as? androidx.activity.ComponentActivity

    LaunchedEffect(viewModel.onError) {
        val host = activity?.window?.decorView?.rootView ?: return@LaunchedEffect
        val resolver = (activity as? org.skepsun.kototoro.core.ui.BaseComposeActivity)?.exceptionResolver
        val observer = SnackbarErrorObserver(host, resolver) { resolved ->
            if (resolved) { }
        }
        viewModel.onError.collect { event ->
            event?.consume(observer)
        }
    }

    LaunchedEffect(viewModel.onActionDone) {
        val host = activity?.window?.decorView?.rootView ?: return@LaunchedEffect
        val observer = ReversibleActionObserver(host)
        viewModel.onActionDone.collect { event ->
            event?.consume(observer)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = if (isGrid) GridCells.Fixed(4) else GridCells.Adaptive(minSize = 100.dp),
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize()
        ) {
            items(
                items = items,
                key = { model ->
                    when (model) {
                        is ContentSourceItem -> model.id
                        else -> model.hashCode()
                    }
                },
                contentType = { model ->
                    when (model) {
                        is ContentSourceItem -> "source_card"
                        is ListHeader -> "header"
                        is EmptyState -> "empty"
                        else -> "unknown"
                    }
                },
                span = { item ->
                    if (item is ListHeader || item is EmptyState) {
                        GridItemSpan(maxLineSpan)
                    } else {
                        GridItemSpan(1)
                    }
                }
            ) { listModel ->
                when (listModel) {
                    is ListHeader -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (listModel.payload == R.id.nav_suggestions) {
                                            // Handle suggestions click if needed
                                        } else if (viewModel.isAllSourcesEnabled.value) {
                                            appRouter.openManageSources()
                                        } else {
                                            appRouter.openSourcesCatalog()
                                        }
                                    }
                                )
                                .padding(horizontal = CompactTopBarHorizontalPadding, vertical = 24.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = listModel.getText(LocalContext.current)?.toString() ?: "",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (listModel.buttonTextRes != 0) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "More",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    is ContentSourceItem -> {
                        val isSelected = listModel.id in composeSelectionIds
                        KototoroSourceCard(
                            item = listModel,
                            isSelected = isSelected,
                            isGrid = isGrid,
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {
                                        if (composeSelectionIds.isNotEmpty()) {
                                            hapticFeedback.performSelectionHapticFeedback()
                                            val newSet = androidx.collection.MutableLongSet(composeSelectionIds.size + 1)
                                            newSet.addAll(composeSelectionIds)
                                            if (isSelected) newSet.remove(listModel.id) else newSet.add(listModel.id)
                                            composeSelectionIds = newSet
                                        } else {
                                            appRouter.openList(listModel.source, null, null)
                                        }
                                    },
                                    onLongClick = {
                                        val newSet = androidx.collection.MutableLongSet(composeSelectionIds.size + 1)
                                        newSet.addAll(composeSelectionIds)
                                        if (isSelected) newSet.remove(listModel.id) else newSet.add(listModel.id)
                                        composeSelectionIds = newSet
                                    }
                                )
                        )
                    }
                    is EmptyState -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 64.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                painter = painterResource(id = listModel.icon),
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(id = listModel.textPrimary),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(id = listModel.textSecondary),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (listModel.actionStringRes != 0) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(onClick = { appRouter.openSourcesCatalog() }) {
                                    Text(stringResource(id = listModel.actionStringRes))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (composeSelectionIds.isNotEmpty()) {
            val selectedSources = viewModel.sourcesSnapshot(composeSelectionIds)
            val isSingleSelection = selectedSources.size == 1
            val canPin = selectedSources.all { !it.isPinned }
            val canUnpin = selectedSources.all { it.isPinned }
            val canDisable = !viewModel.isAllSourcesEnabled.value && selectedSources.all {
                val unwrapped = it.mangaSource.unwrap()
                !unwrapped.isLocal && unwrapped !is ExternalContentSource
            }
            val canDelete = selectedSources.all { it.mangaSource is ExternalContentSource }
            val markEmptyTitleRes = if (selectedSources.all { it.availability == ContentSourceAvailability.EMPTY }) {
                R.string.source_mark_available
            } else {
                R.string.source_mark_empty
            }

            ExploreSelectionTopBar(
                selectedCount = composeSelectionIds.size,
                isSingleSelection = isSingleSelection,
                canPin = canPin,
                canUnpin = canUnpin,
                canDisable = canDisable,
                canDelete = canDelete,
                markEmptyTitleRes = markEmptyTitleRes,
                onClearSelection = { composeSelectionIds = longSetOf() },
                onSettings = {
                    selectedSources.singleOrNull()?.let { appRouter.openSourceSettings(it) }
                    composeSelectionIds = longSetOf()
                },
                onDisable = {
                    viewModel.disableSources(selectedSources)
                    composeSelectionIds = longSetOf()
                },
                onDelete = {
                    selectedSources.forEach { item ->
                        (item.mangaSource as? ExternalContentSource)?.let { source ->
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_DELETE,
                                android.net.Uri.parse("package:${source.packageName}")
                            )
                            activity?.startActivity(intent)
                        }
                    }
                    composeSelectionIds = longSetOf()
                },
                onShortcut = {
                    selectedSources.singleOrNull()?.let { viewModel.requestPinShortcut(it) }
                    composeSelectionIds = longSetOf()
                },
                onPin = {
                    viewModel.setSourcesPinned(selectedSources, isPinned = true)
                    composeSelectionIds = longSetOf()
                },
                onUnpin = {
                    viewModel.setSourcesPinned(selectedSources, isPinned = false)
                    composeSelectionIds = longSetOf()
                },
                onToggleEmptyAvailability = {
                    viewModel.toggleEmptySourceAvailability(selectedSources)
                    composeSelectionIds = longSetOf()
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExploreSelectionTopBar(
    selectedCount: Int,
    isSingleSelection: Boolean,
    canPin: Boolean,
    canUnpin: Boolean,
    canDisable: Boolean,
    canDelete: Boolean,
    markEmptyTitleRes: Int,
    onClearSelection: () -> Unit,
    onSettings: () -> Unit,
    onDisable: () -> Unit,
    onDelete: () -> Unit,
    onShortcut: () -> Unit,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onToggleEmptyAvailability: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalInterfaceStyleTokens.current
    val topBarControlHeight = tokens.topBarButtonSize
    val topBarIconSize = tokens.topBarIconSize
    val statusBarPadding = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = statusBarPadding.calculateTopPadding())
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
            text = "$selectedCount selected",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Weighted so the right action pill keeps its full width on narrow screens
            // (the title simply ellipsizes if space runs out).
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(start = 2.dp, end = 4.dp),
        )

        // Right: operation buttons combination container (a pill capsule like the
        // main top bar's action group). Primary actions stay inline; rarer
        // single-source actions (settings/shortcut) and destructive delete live in
        // the overflow menu so the inline row never overflows on narrow screens.
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
                    if (canPin) {
                        IconButton(
                            onClick = onPin,
                            modifier = Modifier.size(topBarControlHeight),
                        ) {
                            Icon(
                                painterResource(id = R.drawable.ic_pin),
                                contentDescription = stringResource(R.string.pin),
                                modifier = Modifier.size(topBarIconSize),
                            )
                        }
                    }
                    if (canUnpin) {
                        IconButton(
                            onClick = onUnpin,
                            modifier = Modifier.size(topBarControlHeight),
                        ) {
                            Icon(
                                painterResource(id = R.drawable.ic_unpin),
                                contentDescription = stringResource(R.string.unpin),
                                modifier = Modifier.size(topBarIconSize),
                            )
                        }
                    }
                    if (canDisable) {
                        IconButton(
                            onClick = onDisable,
                            modifier = Modifier.size(topBarControlHeight),
                        ) {
                            Icon(
                                painterResource(id = R.drawable.ic_eye_off),
                                contentDescription = stringResource(R.string.disable),
                                modifier = Modifier.size(topBarIconSize),
                            )
                        }
                    }
                    IconButton(
                        onClick = onToggleEmptyAvailability,
                        modifier = Modifier.size(topBarControlHeight),
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_source_empty),
                            contentDescription = stringResource(markEmptyTitleRes),
                            modifier = Modifier.size(topBarIconSize),
                        )
                    }

                    // Overflow menu - single-source actions + delete.
                    var overflowAnchorBounds by remember { mutableStateOf(Rect.Zero) }
                    var showOverflowMenu by remember { mutableStateOf(false) }
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
                            if (isSingleSelection) {
                                CompactDropdownMenuItem(
                                    text = {
                                        CompactDropdownMenuText(text = stringResource(R.string.settings))
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onSettings()
                                    },
                                )
                                CompactDropdownMenuItem(
                                    text = {
                                        CompactDropdownMenuText(text = "Shortcut")
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onShortcut()
                                    },
                                )
                            }
                            if (canDelete) {
                                CompactDropdownMenuItem(
                                    text = {
                                        CompactDropdownMenuText(text = stringResource(R.string.delete))
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onDelete()
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

@Composable
fun KototoroSourceCard(
    item: ContentSourceItem,
    isSelected: Boolean,
    isGrid: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val title = item.source.getTitle(context)
    val summary = item.source.getSummary(context)
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val cardShape = RoundedCornerShape(if (expressive) 20.dp else 12.dp)
    val listShape = RoundedCornerShape(if (expressive) 22.dp else 0.dp)
    val iconShape = RoundedCornerShape(if (expressive) 14.dp else 8.dp)
    val containerColor = when {
        isSelected -> MaterialTheme.colorScheme.secondaryContainer
        expressive -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.surface
    }

    if (isGrid) {
        Card(
            modifier = modifier.padding(if (expressive) 6.dp else 4.dp),
            shape = cardShape,
            colors = CardDefaults.cardColors(
                containerColor = containerColor
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = if (expressive) 0.dp else 2.dp),
            border = if (expressive) {
                CardDefaults.outlinedCardBorder()
            } else {
                null
            },
        ) {
            Column(
                modifier = Modifier.padding(if (expressive) 14.dp else 12.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = iconShape,
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    ContentSourceIconWithAvailabilityBadge(
                        source = item,
                        icon = {
                            Icon(
                                painter = painterResource(id = item.source.iconResForUi()),
                                contentDescription = title,
                                modifier = Modifier.size(30.dp),
                            )
                        },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = if (expressive) 8.dp else 0.dp, vertical = if (expressive) 3.dp else 0.dp)
                .background(containerColor, listShape)
                .padding(vertical = if (expressive) 10.dp else 12.dp, horizontal = if (expressive) 14.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = iconShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                ContentSourceIcon(
                    source = item.source,
                    modifier = Modifier.size(28.dp),
                    contentDescription = title,
                )
                SourceAvailabilityBadge(
                    availability = item.source.availability,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!summary.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ContentSourceIconWithAvailabilityBadge(
    source: ContentSourceItem,
    icon: @Composable BoxScope.() -> Unit,
) {
    Box(contentAlignment = Alignment.Center) {
        icon()
        SourceAvailabilityBadge(
            availability = source.source.availability,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@Composable
private fun SourceAvailabilityBadge(
    availability: ContentSourceAvailability,
    modifier: Modifier = Modifier,
) {
    if (availability != ContentSourceAvailability.EMPTY) {
        return
    }
    Surface(
        modifier = modifier.padding(2.dp),
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.error,
        tonalElevation = 1.dp,
    ) {
        Text(
            text = stringResource(R.string.source_empty_badge_short),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onError,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}
