package org.skepsun.kototoro.settings.sources.unified


import android.graphics.drawable.Drawable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import org.skepsun.kototoro.R
import org.skepsun.kototoro.aniyomi.model.AniyomiAnimeSource
import org.skepsun.kototoro.core.model.getSummary
import org.skepsun.kototoro.core.model.ContentSourceAvailability
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.core.ui.compose.VerticalScrollbar
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.ireader.model.IReaderMangaSource
import org.skepsun.kototoro.mihon.model.MihonMangaSource
import org.skepsun.kototoro.settings.compose.SettingsContentHorizontalPadding

private object PackageIconMemoryCache {
    private val cache = ConcurrentHashMap<String, Drawable>()

    fun get(packageName: String): Drawable? = cache[packageName]

    fun put(packageName: String, drawable: Drawable) {
        cache[packageName] = drawable
    }
}

@Composable
internal fun UnifiedEmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(4.dp))
                FilledTonalButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
internal fun UnifiedSourceList(
    modifier: Modifier = Modifier,
    listState: LazyListState,
    sources: List<UnifiedSourceItem>,
    onBrowseSource: (UnifiedSourceItem) -> Unit,
    onOpenSourceSettings: (UnifiedSourceItem) -> Unit,
    onSourceEnabledChange: (String, Boolean) -> Unit,
    onEnableAllSources: () -> Unit,
    onDisableAllSources: () -> Unit,
    selectedSourceIds: Set<String>,
    onSourceSelectionChange: (Set<String>) -> Unit,
    onSourcePinnedChange: (String, Boolean) -> Unit,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val horizontalPadding = if (expressive) 8.dp else 0.dp
    // Multi-source packages are collapsed into a single expandable group by default.
    // Only the groups the user explicitly expands are kept open, so newly appearing
    // groups (e.g. after a filter change) start collapsed as well.
    val collapsiblePackageIds = remember(sources) {
        buildSet {
            sources.groupBy { it.packageId }.forEach { (packageId, members) ->
                if (packageId != null && members.size > 1) add(packageId)
            }
        }
    }
    var expandedPackageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val collapsedPackageIds = remember(collapsiblePackageIds, expandedPackageIds) {
        collapsiblePackageIds - expandedPackageIds
    }
    val displayRows = remember(sources, collapsedPackageIds) {
        buildGroupedUnifiedSourceRows(sources, collapsedPackageIds)
    }
    // Keep the first action chip aligned with the global content margin; the actions
    // row itself is still horizontally scrollable towards the screen edge.
    val actionsStartPadding = (SettingsContentHorizontalPadding - horizontalPadding).coerceAtLeast(0.dp)
    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                top = 4.dp,
                end = SettingsContentHorizontalPadding,
                bottom = 4.dp,
            ),
        ) {
            item(key = "source_actions") {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(start = actionsStartPadding),
                ) {
                    item(key = "enable_all_sources") {
                        CompactActionChip(
                            onClick = onEnableAllSources,
                            enabled = sources.isNotEmpty(),
                            label = { Text(stringResource(R.string.unified_sources_enable_all)) },
                        )
                    }
                    item(key = "disable_all_sources") {
                        CompactActionChip(
                            onClick = onDisableAllSources,
                            enabled = sources.isNotEmpty(),
                            label = { Text(stringResource(R.string.unified_sources_disable_all)) },
                        )
                    }
                    item(key = "expand_all_groups") {
                        CompactActionChip(
                            onClick = { expandedPackageIds = collapsiblePackageIds },
                            enabled = collapsiblePackageIds.isNotEmpty(),
                            label = { Text(stringResource(R.string.unified_sources_expand_all)) },
                        )
                    }
                    item(key = "collapse_all_groups") {
                        CompactActionChip(
                            onClick = { expandedPackageIds = emptySet() },
                            enabled = collapsiblePackageIds.isNotEmpty(),
                            label = { Text(stringResource(R.string.unified_sources_collapse_all)) },
                        )
                    }
                }
            }
            items(displayRows, key = { it.key }) { row ->
                when (row) {
                    is UnifiedSourceDisplayRow.SourceItem -> {
                        val item = row.item
                        val isSelected = item.id in selectedSourceIds
                        UnifiedSourceRow(
                            item = item,
                            isSelectionMode = selectedSourceIds.isNotEmpty(),
                            isSelected = isSelected,
                            groupColor = rememberPackageGroupColor(row.groupPackageId),
                            onSelectionToggle = {
                                onSourceSelectionChange(selectedSourceIds.toggle(item.id))
                            },
                            onBrowseSource = onBrowseSource,
                            onOpenSourceSettings = onOpenSourceSettings,
                            onSourceEnabledChange = onSourceEnabledChange,
                            onSourcePinnedChange = onSourcePinnedChange,
                        )
                        if (expressive) {
                            Spacer(modifier = Modifier.height(3.dp))
                        } else {
                            HorizontalDivider(modifier = Modifier.padding(start = 64.dp))
                        }
                    }
                    is UnifiedSourceDisplayRow.PackageHeader -> {
                        val memberIds = row.members.mapTo(HashSet()) { it.id }
                        val allMembersSelected = memberIds.isNotEmpty() && memberIds.all { it in selectedSourceIds }
                        UnifiedSourcePackageHeader(
                            packageName = row.packageName,
                            kind = row.kind,
                            sourceCount = row.sourceCount,
                            collapsed = row.collapsed,
                            isSelectionMode = selectedSourceIds.isNotEmpty(),
                            isChecked = allMembersSelected,
                            groupColor = rememberPackageGroupColor(row.packageId),
                            onClick = {
                                if (selectedSourceIds.isNotEmpty()) {
                                    val updated = selectedSourceIds.toMutableSet().apply {
                                        if (allMembersSelected) removeAll(memberIds) else addAll(memberIds)
                                    }
                                    onSourceSelectionChange(updated)
                                } else {
                                    expandedPackageIds = togglePackageExpanded(expandedPackageIds, row.packageId)
                                }
                            },
                            onLongClick = {
                                // Long-press selects every currently-visible member of this package group.
                                // `row.members` is built from the already-filtered `sources`, so this only
                                // picks the sources actually shown under the current filter.
                                onSourceSelectionChange(selectedSourceIds + memberIds)
                            },
                            onToggleCollapse = {
                                expandedPackageIds = togglePackageExpanded(expandedPackageIds, row.packageId)
                            },
                        )
                        if (expressive) {
                            Spacer(modifier = Modifier.height(3.dp))
                        } else {
                            HorizontalDivider(modifier = Modifier.padding(start = 64.dp))
                        }
                    }
                }
            }
            if (displayRows.isEmpty()) {
                item(key = "sources_empty_state") {
                    UnifiedEmptyState(
                        icon = Icons.Outlined.SearchOff,
                        title = stringResource(R.string.no_sources_found),
                        message = stringResource(R.string.no_sources_found_desc),
                    )
                }
            }
        }
        VerticalScrollbar(
            state = listState,
            alwaysVisible = true,
            endInset = 4.dp,
        )
    }
}

@Composable
private fun UnifiedSourceIcon(
    item: UnifiedSourceItem,
    modifier: Modifier = Modifier,
) {
    ContentSourceIcon(
        source = item.source,
        modifier = modifier,
        contentDescription = item.title,
    )
}

/**
 * A display-level row for the unified sources list.
 *
 * Packages that expose more than one source (e.g. one multilingual Mihon/Aniyomi APK
 * shipping the same site per language) are collapsed into a single [PackageHeader] so the
 * sources tab does not explode into one row per language. Everything else stays a plain
 * [SourceItem].
 */
internal sealed interface UnifiedSourceDisplayRow {
    val key: String

    data class PackageHeader(
        val packageId: String,
        val packageName: String,
        val kind: UnifiedSourceKind,
        val sourceCount: Int,
        val collapsed: Boolean,
        val members: List<UnifiedSourceItem>,
    ) : UnifiedSourceDisplayRow {
        override val key: String get() = "pkg:$packageId"
    }

    data class SourceItem(
        val item: UnifiedSourceItem,
        val groupPackageId: String? = null,
    ) : UnifiedSourceDisplayRow {
        override val key: String get() = item.id
    }
}

/**
 * Builds the display rows for the sources tab, grouping multi-source packages into a single
 * expandable header at the position of their first source while preserving the overall order.
 * Single-source packages and package-less sources keep their existing flat rows.
 */
internal fun buildGroupedUnifiedSourceRows(
    sources: List<UnifiedSourceItem>,
    collapsedPackageIds: Set<String>,
): List<UnifiedSourceDisplayRow> {
    val byPackage = LinkedHashMap<String, MutableList<UnifiedSourceItem>>()
    sources.forEach { source ->
        source.packageId?.let { packageId ->
            byPackage.getOrPut(packageId) { mutableListOf() }.add(source)
        }
    }
    val multiSourcePackages = byPackage.filterValues { it.size > 1 }
    if (multiSourcePackages.isEmpty()) {
        return sources.map(UnifiedSourceDisplayRow::SourceItem)
    }
    val groupable = multiSourcePackages.keys
    val placedPackages = HashSet<String>()
    return buildList {
        for (source in sources) {
            val packageId = source.packageId
            if (packageId != null && packageId in groupable) {
                if (placedPackages.add(packageId)) {
                    val members = multiSourcePackages.getValue(packageId)
                    add(
                        UnifiedSourceDisplayRow.PackageHeader(
                            packageId = packageId,
                            packageName = resolveUnifiedSourceGroupName(members, packageId),
                            kind = members.first().kind,
                            sourceCount = members.size,
                            collapsed = packageId in collapsedPackageIds,
                            members = members,
                        ),
                    )
                    if (packageId !in collapsedPackageIds) {
                        members.forEach { add(UnifiedSourceDisplayRow.SourceItem(it, packageId)) }
                    }
                }
            } else {
                add(UnifiedSourceDisplayRow.SourceItem(source))
            }
        }
    }
}

private fun resolveUnifiedSourceGroupName(
    members: List<UnifiedSourceItem>,
    fallback: String,
): String {
    val first = members.first()
    val preferredName = when (first.kind) {
        UnifiedSourceKind.JAR -> first.repositoryName?.takeIf { it.isNotBlank() }
        UnifiedSourceKind.MIHON -> (first.source as? MihonMangaSource)?.catalogueSource?.name
        UnifiedSourceKind.ANIYOMI -> (first.source as? AniyomiAnimeSource)?.animeCatalogueSource?.name
        UnifiedSourceKind.IREADER -> (first.source as? IReaderMangaSource)?.catalogueSource?.name
        else -> first.packageName?.takeIf { it.isNotBlank() }
    }
    val fallbackName = when (first.kind) {
        UnifiedSourceKind.MIHON,
        UnifiedSourceKind.ANIYOMI,
        UnifiedSourceKind.IREADER -> first.title
        else -> first.packageName
    }
    return preferredName?.takeIf { it.isNotBlank() }
        ?: fallbackName?.takeIf { it.isNotBlank() }
        ?: first.packageName?.takeIf { it.isNotBlank() }
        ?: fallback
}

private fun togglePackageExpanded(current: Set<String>, packageId: String): Set<String> =
    if (packageId in current) current - packageId else current + packageId

/**
 * Soft container palette for multi-source package groups. Each package is assigned a stable
 * hue derived from its id, so every group keeps one distinct color (light + dark variants).
 */
private val packageGroupPaletteLight = listOf(
    Color(0xFFE7F0FA), // soft blue
    Color(0xFFE4F2E4), // soft green
    Color(0xFFFAEEDD), // soft amber
    Color(0xFFEEE7F7), // soft violet
    Color(0xFFFAE8EE), // soft pink
    Color(0xFFE0F2EF), // soft teal
    Color(0xFFF7F1DC), // soft yellow
    Color(0xFFE8EDF2), // soft blue-grey
)

private val packageGroupPaletteDark = listOf(
    Color(0xFF223343), // deep blue
    Color(0xFF203124), // deep green
    Color(0xFF372C1F), // deep amber-brown
    Color(0xFF312740), // deep violet
    Color(0xFF3A2830), // deep pink-plum
    Color(0xFF1F3230), // deep teal
    Color(0xFF373320), // deep olive-yellow
    Color(0xFF2A303B), // deep blue-grey
)

internal fun packageGroupColorIndex(packageId: String): Int =
    (packageId.hashCode() and 0x7fffffff) % packageGroupPaletteLight.size

@Composable
internal fun rememberPackageGroupColor(packageId: String?): Color? {
    if (packageId == null) return null
    val palette = if (isSystemInDarkTheme()) packageGroupPaletteDark else packageGroupPaletteLight
    return palette[packageGroupColorIndex(packageId)]
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UnifiedSourcePackageHeader(
    packageName: String,
    kind: UnifiedSourceKind,
    sourceCount: Int,
    collapsed: Boolean,
    isSelectionMode: Boolean,
    isChecked: Boolean,
    groupColor: Color?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleCollapse: () -> Unit,
) {
    val style = rememberUnifiedSourcesVisualStyle()
    val containerColor = groupColor ?: if (collapsed) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val groupIconContainer = groupColor ?: MaterialTheme.colorScheme.tertiaryContainer
    val groupIconTint = if (groupColor != null) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = style.rowHorizontalPadding, vertical = 2.dp)
            .clip(style.rowShape)
            .background(containerColor, style.rowShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(groupIconContainer, style.iconShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = rememberSafePainter(kind.packageIconRes()),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = groupIconTint,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = packageName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CompactTag(text = kind.displayLabel())
                CompactTag(text = stringResource(R.string.unified_sources_package_group_count, sourceCount))
            }
        }
        if (isSelectionMode) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = { onClick() },
                modifier = Modifier.size(32.dp),
            )
        }
        // Collapse/expand arrow stays available in selection mode too, so the user can
        // still fold the group while batch-selecting. The IconButton consumes the tap,
        // so it does not double as a group toggle.
        IconButton(
            onClick = onToggleCollapse,
            modifier = Modifier.size(34.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_expand_more),
                contentDescription = stringResource(if (collapsed) R.string.expand else R.string.collapse),
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (collapsed) -90f else 0f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UnifiedSourceRow(
    item: UnifiedSourceItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    groupColor: Color?,
    onSelectionToggle: () -> Unit,
    onBrowseSource: (UnifiedSourceItem) -> Unit,
    onOpenSourceSettings: (UnifiedSourceItem) -> Unit,
    onSourceEnabledChange: (String, Boolean) -> Unit,
    onSourcePinnedChange: (String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var menuExpanded by rememberSaveable(item.id) { mutableStateOf(false) }
    // Optimistic toggle: flip the switch on tap immediately and reconcile when the
    // authoritative item state from the catalog lands (re-keys this remember once the DB
    // round trip re-emits), so the UI never waits seconds for feedback.
    var localEnabled by remember(item.id, item.isEnabled) { mutableStateOf(item.isEnabled) }
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val style = rememberUnifiedSourcesVisualStyle()
    val rowContainerColor = when {
        isSelected -> MaterialTheme.colorScheme.secondaryContainer
        groupColor != null -> groupColor
        expressive -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.background
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = style.rowHorizontalPadding, vertical = style.rowVerticalPadding)
            .background(rowContainerColor, style.rowShape)
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onSelectionToggle()
                    } else {
                        onBrowseSource(item)
                    }
                },
                onLongClick = onSelectionToggle,
            )
            .padding(start = if (expressive) 12.dp else 16.dp, top = 7.dp, end = 4.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onSelectionToggle() },
                modifier = Modifier.size(32.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(groupColor ?: MaterialTheme.colorScheme.surfaceContainerHigh, style.iconShape),
                contentAlignment = Alignment.Center,
            ) {
                UnifiedSourceIcon(
                    item = item,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = item.title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.isPinned) {
                    Icon(
                        painter = painterResource(R.drawable.ic_pin_small),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CompactTag(text = item.kind.displayLabel())
                if (!item.isAvailable || item.isBroken) {
                    CompactTag(text = stringResource(R.string.unavailable), tone = CompactTagTone.Warning)
                }
                when (item.testAvailability) {
                    ContentSourceAvailability.AVAILABLE -> CompactTag(
                        text = stringResource(R.string.source_test_available),
                        tone = CompactTagTone.TestedAvailable,
                    )
                    ContentSourceAvailability.EMPTY -> CompactTag(
                        text = stringResource(R.string.source_test_unavailable),
                        tone = CompactTagTone.TestedUnavailable,
                    )
                    ContentSourceAvailability.UNKNOWN -> Unit
                }
            }
            Text(
                text = item.source.getSummary(context, item.contentType) ?: buildSourceSubtitle(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(40.dp),
            ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.more_filters),
                        modifier = Modifier.size(18.dp),
                    )
                }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_available_extensions)) },
                    onClick = {
                        menuExpanded = false
                        onBrowseSource(item)
                    },
                )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (item.isPinned) R.string.unpin else R.string.pin)) },
                    onClick = {
                        menuExpanded = false
                        onSourcePinnedChange(item.id, !item.isPinned)
                    },
                )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings)) },
                    onClick = {
                        menuExpanded = false
                        onOpenSourceSettings(item)
                    },
                )
            }
        }
        Switch(
            checked = localEnabled,
            onCheckedChange = { newValue ->
                localEnabled = newValue
                onSourceEnabledChange(item.id, newValue)
            },
        )
    }
}

@Composable
internal fun UnifiedRepositoryList(
    modifier: Modifier = Modifier,
    listState: LazyListState,
    repositories: List<UnifiedSourceRepositoryItem>,
    onAddRepository: (UnifiedSourceRepositoryItem?) -> Unit,
    onRefreshRepository: (UnifiedSourceRepositoryItem) -> Unit,
    onDeleteRepository: (UnifiedSourceRepositoryItem) -> Unit,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val style = rememberUnifiedSourcesVisualStyle()
    val (configured, presets) = remember(repositories) {
        repositories.partition { it.isConfigured }
    }
    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = unifiedCardListPadding,
            verticalArrangement = Arrangement.spacedBy(unifiedCardSpacing),
        ) {
            item(key = "add_repository_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.configured_repositories),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Button(
                        onClick = { onAddRepository(null) },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.add_repository))
                    }
                }
            }
            if (configured.isEmpty()) {
                item(key = "no_configured_repo_hint") {
                    Surface(
                        shape = style.cardShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.no_extension_repositories),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.no_extension_repositories_text),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(configured, key = { it.id }) { item ->
                    UnifiedRepositoryCard(
                        item = item,
                        style = style,
                        expressive = expressive,
                        onRefresh = { onRefreshRepository(item) },
                        onDelete = { onDeleteRepository(item) },
                        onAdd = null,
                    )
                }
            }
            if (presets.isNotEmpty()) {
                item(key = "preset_repositories_header") {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.preset_repositories),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                items(presets, key = { it.id }) { item ->
                    UnifiedRepositoryCard(
                        item = item,
                        style = style,
                        expressive = expressive,
                        onRefresh = null,
                        onDelete = null,
                        onAdd = { onAddRepository(item) },
                    )
                }
            } else if (configured.isEmpty()) {
                item(key = "repo_empty_state") {
                    UnifiedEmptyState(
                        icon = Icons.Outlined.SearchOff,
                        title = stringResource(R.string.no_extension_repositories),
                        message = stringResource(R.string.no_sources_found_desc),
                    )
                }
            }
        }
        VerticalScrollbar(
            state = listState,
            alwaysVisible = true,
            endInset = 4.dp,
        )
    }
}

@Composable
private fun UnifiedRepositoryCard(
    item: UnifiedSourceRepositoryItem,
    style: UnifiedSourcesVisualStyle,
    expressive: Boolean,
    onRefresh: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onAdd: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = style.cardShape,
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (expressive) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = style.cardElevation,
        ),
    ) {
        Column(
            modifier = Modifier.padding(unifiedCardContentPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, style.iconShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = rememberSafePainter(item.kind.packageIconRes()),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = item.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (item.isConfigured) {
                    if (onRefresh != null) {
                        CompactActionChip(
                            onClick = onRefresh,
                            label = { Text(stringResource(R.string.refresh_action)) },
                        )
                    }
                    if (onDelete != null) {
                        CompactActionChip(
                            onClick = onDelete,
                            label = { Text(stringResource(R.string.delete)) },
                        )
                    }
                } else if (item.isPreset && onAdd != null) {
                    Button(
                        onClick = onAdd,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text(stringResource(R.string.add), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CompactTag(text = item.kind.displayLabel())
                CompactTag(text = item.locationType.displayLabel())
                if (item.isConfigured && item.lastError.isNullOrBlank()) {
                    CompactTag(text = stringResource(R.string.installed), tone = CompactTagTone.TestedAvailable)
                }
            }
            Text(
                text = item.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = stringResource(R.string.unified_sources_repository_last_refresh_failed, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun UnifiedPackageList(
    modifier: Modifier = Modifier,
    listState: LazyListState,
    packages: List<UnifiedSourcePackageItem>,
    recommendedPackages: List<RecommendedPackageItem> = emptyList(),
    missingSourcesWithoutMatch: List<MissingSourceHint> = emptyList(),
    suggestedRepositoriesForMissing: List<UnifiedRecommendedRepository> = emptyList(),
    updateAllInProgress: Boolean,
    onUpdateAllPackages: () -> Unit,
    onPackagePrimaryAction: (String) -> Unit,
    onPackageSystemInstall: (String) -> Unit,
    onPackageUninstall: (String) -> Unit,
    onPackageCancelInstall: (String) -> Unit,
    onImportLocalJar: () -> Unit,
    onAddRecommendedRepository: (UnifiedRecommendedRepository) -> Unit = {},
) {
    var recommendedExpanded by rememberSaveable { mutableStateOf(true) }
    val updateAvailableCount = remember(packages) {
        packages.count { it.state == UnifiedSourcePackageState.UPDATE_AVAILABLE }
    }
    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = unifiedCardListPadding,
            verticalArrangement = Arrangement.spacedBy(unifiedCardSpacing),
        ) {
            if (recommendedPackages.isNotEmpty() || missingSourcesWithoutMatch.isNotEmpty()) {
                item(key = "recommended_header") {
                    RecommendedSectionHeader(
                        missingCount = missingSourcesWithoutMatch.size,
                        recommendedCount = recommendedPackages.size,
                        expanded = recommendedExpanded,
                        onToggle = { recommendedExpanded = !recommendedExpanded },
                    )
                }
                if (recommendedExpanded) {
                    items(recommendedPackages, key = { "recommended_" + it.item.id }) { recommended ->
                        UnifiedPackageRow(
                            item = recommended.item,
                            coverageLabel = recommended.coversMissingSources.joinToString(", "),
                            isHighlighted = true,
                            onPrimaryAction = { onPackagePrimaryAction(recommended.item.id) },
                            onSystemInstall = { onPackageSystemInstall(recommended.item.id) },
                            onUninstall = { onPackageUninstall(recommended.item.id) },
                            onCancelInstall = { onPackageCancelInstall(recommended.item.id) },
                        )
                    }
                    if (missingSourcesWithoutMatch.isNotEmpty()) {
                        item(key = "missing_sources_hint") {
                            MissingSourcesCard(
                                missingSources = missingSourcesWithoutMatch,
                                suggestedRepositories = suggestedRepositoriesForMissing,
                                onAddRepository = onAddRecommendedRepository,
                            )
                        }
                    }
                }
            }
            if (updateAvailableCount > 0) {
                item(key = "update_all_banner") {
                    val style = rememberUnifiedSourcesVisualStyle()
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = style.cardShape,
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f, fill = false),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = stringResource(R.string.update_all_available_banner, updateAvailableCount),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = onUpdateAllPackages,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                if (updateAllInProgress) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text(
                                    text = stringResource(
                                        if (updateAllInProgress) {
                                            R.string.cancel_update_all_packages
                                        } else {
                                            R.string.update_all_packages
                                        },
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
            item(key = "package_actions") {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "update_all_packages") {
                        CompactActionChip(
                            onClick = onUpdateAllPackages,
                            label = {
                                Text(
                                    stringResource(
                                        if (updateAllInProgress) {
                                            R.string.cancel_update_all_packages
                                        } else {
                                            R.string.update_all_packages
                                        },
                                    ),
                                )
                            },
                        )
                    }
                    item(key = "import_local_jar") {
                        CompactActionChip(
                            onClick = onImportLocalJar,
                            label = { Text(stringResource(R.string.import_local_jar)) },
                        )
                    }
                }
            }
            items(packages, key = { it.id }) { item ->
                UnifiedPackageRow(
                    item = item,
                    onPrimaryAction = { onPackagePrimaryAction(item.id) },
                    onSystemInstall = { onPackageSystemInstall(item.id) },
                    onUninstall = { onPackageUninstall(item.id) },
                    onCancelInstall = { onPackageCancelInstall(item.id) },
                )
            }
            if (packages.isEmpty() && recommendedPackages.isEmpty() && missingSourcesWithoutMatch.isEmpty()) {
                item(key = "packages_empty_state") {
                    UnifiedEmptyState(
                        icon = Icons.Outlined.SearchOff,
                        title = stringResource(R.string.no_packages_found),
                        message = stringResource(R.string.no_packages_found_desc),
                    )
                }
            }
        }
        VerticalScrollbar(
            state = listState,
            alwaysVisible = true,
            endInset = 4.dp,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecommendedSectionHeader(
    missingCount: Int,
    recommendedCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val style = rememberUnifiedSourcesVisualStyle()
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(durationMillis = 180),
        label = "recommended_chevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(style.cardShape)
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, style.iconShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.packages_recommended_header),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (recommendedCount > 0) {
                    CompactTag(stringResource(R.string.packages_recommended_count, recommendedCount))
                }
            }
            Text(
                text = stringResource(R.string.packages_recommended_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (missingCount > 0) {
            CompactTag(
                text = stringResource(R.string.packages_recommended_missing_count, missingCount),
                isWarning = true,
            )
        }
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.collapse else R.string.expand,
            ),
            modifier = Modifier
                .size(22.dp)
                .rotate(chevronRotation),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MissingSourcesCard(
    missingSources: List<MissingSourceHint>,
    suggestedRepositories: List<UnifiedRecommendedRepository>,
    onAddRepository: (UnifiedRecommendedRepository) -> Unit,
) {
    val style = rememberUnifiedSourcesVisualStyle()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = style.cardShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, style.iconShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SearchOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Text(
                    text = stringResource(R.string.packages_missing_sources_hint, missingSources.size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                missingSources.take(10).forEach { source ->
                    CompactTag(source.label)
                }
                if (missingSources.size > 10) {
                    CompactTag("+" + (missingSources.size - 10))
                }
            }
            if (suggestedRepositories.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                Text(
                    text = stringResource(R.string.packages_missing_sources_add_repo),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    suggestedRepositories.forEach { repo ->
                        CompactActionChip(
                            onClick = { onAddRepository(repo) },
                            label = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(repo.name, style = MaterialTheme.typography.labelMedium)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UnifiedPackageRow(
    item: UnifiedSourcePackageItem,
    onPrimaryAction: () -> Unit,
    onSystemInstall: () -> Unit,
    onUninstall: () -> Unit,
    onCancelInstall: () -> Unit,
    coverageLabel: String? = null,
    isHighlighted: Boolean = false,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val style = rememberUnifiedSourcesVisualStyle()
    ElevatedCard(
        modifier = if (isHighlighted) {
            Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), style.cardShape)
        } else {
            Modifier.fillMaxWidth()
        },
        shape = style.cardShape,
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (expressive) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = style.cardElevation,
        ),
    ) {
        Column(
            modifier = Modifier.padding(unifiedCardContentPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                UnifiedPackageIcon(item = item)
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = item.name,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        CompactTag(item.kind.displayLabel())
                        CompactTag(item.state.displayLabel(), isWarning = item.state.isWarning)
                    }
                    Text(
                        text = buildPackageSubtitle(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (coverageLabel != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(R.string.packages_recommended_covers, coverageLabel),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
                if (item.installProgressPercent != null) {
                    Text(
                        text = stringResource(R.string.package_download_progress, item.installProgressPercent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { item.installProgressPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item.language.normalizedLanguageTag()?.let { CompactTag(it) }
                if (item.installedVersionName != null && item.state == UnifiedSourcePackageState.UPDATE_AVAILABLE) {
                    CompactTag(stringResource(R.string.installed_version_pattern, item.installedVersionName))
                }
                if (item.isNsfw) {
                    CompactTag(stringResource(R.string.nsfw), isWarning = true)
                }
                if (item.shadowedSourceCount > 0) {
                    CompactTag(
                        text = stringResource(R.string.unified_sources_shadowed_count, item.shadowedSourceCount),
                        isWarning = true,
                    )
                }
            }
            if (item.sourceNames.isNotEmpty()) {
                Text(
                    text = item.sourceNames.take(8).joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (item.state) {
                    UnifiedSourcePackageState.AVAILABLE,
                    UnifiedSourcePackageState.UPDATE_AVAILABLE -> {
                        if (
                            item.kind.isSideloadKind() &&
                            item.installLocation != UnifiedSourcePackageInstallLocation.LOCAL_APK
                        ) {
                            CompactActionChip(
                                onClick = onSystemInstall,
                                label = { Text(stringResource(R.string.install_extension)) },
                            )
                        }
                        CompactActionChip(
                            onClick = onPrimaryAction,
                            label = { Text(item.primaryActionLabel()) },
                        )
                    }
                    UnifiedSourcePackageState.UNTRUSTED,
                    UnifiedSourcePackageState.INCOMPATIBLE -> {
                        CompactActionChip(
                            onClick = onPrimaryAction,
                            label = { Text(item.primaryActionLabel()) },
                        )
                    }
                        UnifiedSourcePackageState.INSTALLING -> {
                            CompactActionChip(
                                onClick = onCancelInstall,
                                label = { Text(stringResource(android.R.string.cancel)) },
                            )
                        }
                    UnifiedSourcePackageState.INSTALLED -> Unit
                }
                    if (item.isInstalled) {
                        CompactActionChip(
                            onClick = onUninstall,
                            label = { Text(stringResource(R.string.remove)) },
                        )
                    }
            }
        }
    }
}

@Composable
private fun UnifiedPackageIcon(
    item: UnifiedSourcePackageItem,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fallbackPainter = rememberSafePainter(item.kind.packageIconRes())
    val installedPackageName = remember(item.kind, item.packageName, item.isInstalled) {
        item.installedIconPackageName()
    }
    var installedIcon by remember(installedPackageName) {
        mutableStateOf(installedPackageName?.let { PackageIconMemoryCache.get(it) })
    }
    LaunchedEffect(installedPackageName) {
        if (installedPackageName != null && installedIcon == null) {
            val icon = withContext(Dispatchers.IO) {
                runCatching { context.packageManager.getApplicationIcon(installedPackageName) }.getOrNull()
            }
            if (icon != null) {
                PackageIconMemoryCache.put(installedPackageName, icon)
                installedIcon = icon
            }
        }
    }
    val iconModel = installedIcon ?: item.iconUrl
    val style = rememberUnifiedSourcesVisualStyle()

    Box(
        modifier = modifier
            .size(32.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, style.iconShape),
        contentAlignment = Alignment.Center,
    ) {
        if (iconModel != null) {
            AsyncImage(
                model = iconModel,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                placeholder = fallbackPainter,
                error = fallbackPainter,
                fallback = fallbackPainter,
            )
        } else {
            Icon(
                painter = fallbackPainter,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
