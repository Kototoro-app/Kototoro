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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.iconResForUi
import org.skepsun.kototoro.core.ui.compose.performSelectionHapticFeedback
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.ui.util.ReversibleActionObserver
import org.skepsun.kototoro.explore.ui.ExploreViewModel
import org.skepsun.kototoro.explore.ui.model.ContentSourceItem
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListHeader

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun KototoroExploreSourcesScreen(
    viewModel: ExploreViewModel,
    contentPadding: PaddingValues,
    appRouter: AppRouter,
) {
    val items by viewModel.content.collectAsStateWithLifecycle()
    var composeSelectionIds: LongSet by remember { mutableStateOf(longSetOf()) }
    val hapticFeedback = LocalHapticFeedback.current
    val isGrid by viewModel.isGrid.collectAsStateWithLifecycle()

    val activity = LocalContext.current as? androidx.activity.ComponentActivity
    
    LaunchedEffect(viewModel.onError) {
        val host = activity?.window?.decorView?.rootView ?: return@LaunchedEffect
        val resolver = (activity as? org.skepsun.kototoro.core.ui.BaseActivity<*>)?.exceptionResolver
        val observer = SnackbarErrorObserver(host, null, resolver) { resolved ->
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

@OptIn(ExperimentalMaterial3Api::class)
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
    TopAppBar(
        title = { Text("$selectedCount selected", style = MaterialTheme.typography.titleMedium) },
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(Icons.Default.Close, contentDescription = "Clear Selection")
            }
        },
        actions = {
            if (isSingleSelection) {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
                IconButton(onClick = onShortcut) {
                    Icon(painterResource(id = R.drawable.ic_shortcut), contentDescription = "Shortcut")
                }
            }
            if (canPin) {
                IconButton(onClick = onPin) {
                    Icon(painterResource(id = R.drawable.ic_pin), contentDescription = "Pin")
                }
            }
            if (canUnpin) {
                IconButton(onClick = onUnpin) {
                    Icon(painterResource(id = R.drawable.ic_unpin), contentDescription = "Unpin")
                }
            }
            if (canDisable) {
                IconButton(onClick = onDisable) {
                    Icon(painterResource(id = R.drawable.ic_eye_off), contentDescription = "Disable")
                }
            }
            if (canDelete) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
            IconButton(onClick = onToggleEmptyAvailability) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_source_empty),
                    contentDescription = stringResource(markEmptyTitleRes),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            titleContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        modifier = modifier,
    )
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
