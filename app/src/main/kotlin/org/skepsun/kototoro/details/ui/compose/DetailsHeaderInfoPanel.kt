package org.skepsun.kototoro.details.ui.compose


import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.widget.Toast
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.iconResId
import org.skepsun.kototoro.core.model.containsAdultTagKeyword
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.adaptive.tvChipRing
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.compose.rememberResolvedSourceTitle
import org.skepsun.kototoro.core.ui.compose.sharedCoverMemoryCacheKey
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.rememberGlassSurfaceColors
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.core.util.ext.takeIfUsableImageUri
import org.skepsun.kototoro.details.ui.model.DetailsSourceOption
import org.skepsun.kototoro.details.ui.model.DetailsSourceDisplayContext
import org.skepsun.kototoro.details.ui.model.DetailsSourceDisplayStrings
import org.skepsun.kototoro.details.ui.model.DetailsSourceRole
import org.skepsun.kototoro.details.ui.model.LinkedTrackingItemUiModel
import org.skepsun.kototoro.details.ui.model.toPresentationModel
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailsSourceSummaryRow(
    metadataDisplayModel: SourceOptionDisplayModel?,
    readingDisplayModel: SourceOptionDisplayModel?,
    onMetadataIconClick: () -> Unit,
    onMetadataNameClick: () -> Unit,
    onReadingIconClick: () -> Unit,
    onReadingNameClick: () -> Unit,
) {
    val metadataTitle = metadataDisplayModel?.selectorTitle.orEmpty()
    val readingTitle = readingDisplayModel?.selectorTitle.orEmpty()
    val metadataFallback = stringResource(R.string.details_metadata_binding_unavailable)
    val readingFallback = stringResource(R.string.details_reading_source_unavailable)
    val merged = sourceSummaryIsMerged(
        metadataSourceName = metadataDisplayModel?.source?.name,
        metadataIsTracker = metadataDisplayModel?.trackingService != null,
        readingSourceName = readingDisplayModel?.source?.name,
        readingIsTracker = readingDisplayModel?.trackingService != null,
    )
    BoxWithConstraints(modifier = Modifier.wrapContentWidth()) {
        val maxSegmentWidth = if (merged) {
            (maxWidth - MergedMetadataSegmentWidth - 1.dp).coerceAtLeast(1.dp)
        } else {
            ((maxWidth - 1.dp) / 2f).coerceAtLeast(1.dp)
        }
        Surface(
            modifier = Modifier.widthIn(max = maxWidth),
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.36f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.26f)),
        ) {
            Row(
                modifier = Modifier.wrapContentWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (merged) {
                    // Same source twice reads as a mistake; the metadata half collapses to the one thing
                    // it offers here, linking this title to a tracker or another metadata source. It keeps the
                    // metadata segment's place on the left.
                    Box(
                        modifier = Modifier
                            .width(MergedMetadataSegmentWidth)
                            .height(30.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                            .tvFocusable(shape = RoundedCornerShape(999.dp), addFocusTarget = false)
                            .clickable(onClick = onMetadataNameClick),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Link,
                            contentDescription = stringResource(R.string.details_metadata_source),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    SourceSummarySegment(
                        label = metadataTitle,
                        fallbackLabel = metadataFallback,
                        displayModel = metadataDisplayModel,
                        color = MaterialTheme.colorScheme.primary,
                        onIconClick = onMetadataIconClick,
                        onNameClick = onMetadataNameClick,
                        modifier = Modifier.widthIn(max = maxSegmentWidth),
                    )
                }
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)),
                )
                SourceSummarySegment(
                    label = readingTitle,
                    fallbackLabel = readingFallback,
                    displayModel = readingDisplayModel,
                    color = MaterialTheme.colorScheme.tertiary,
                    onIconClick = onReadingIconClick,
                    onNameClick = onReadingNameClick,
                    modifier = Modifier.widthIn(max = maxSegmentWidth),
                )
            }
        }
    }
}

private val MergedMetadataSegmentWidth = 34.dp

/**
 * Padding between a TV source segment's outline and the pill that contains it.
 *
 * The outline is drawn inside its own node, so a ring drawn on the bare segment would collide with
 * the pill's own 1 dp border and lose its outer corners to the pill's clip.
 */
private val TvSummarySegmentRingInset = 3.dp

@Composable
private fun SourceSummarySegment(
    label: String,
    fallbackLabel: String,
    displayModel: SourceOptionDisplayModel?,
    color: Color,
    onIconClick: () -> Unit,
    onNameClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasResolvedSource = displayModel != null
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    val segmentShape = RoundedCornerShape(999.dp)
    // Icon and label are two separate hit targets for a finger. A remote walked them as two stops
    // inside one pill instead: the outline shrank to a 20 dp circle over the icon and then to the
    // text, so the metadata/reading strip of a work became four stops that flicker in place and a
    // CENTER press had to guess which half it was on. On TV the whole segment is the stop, the
    // outline wraps icon + label, and CENTER runs the name action (the source sheet); opening the
    // source page itself stays a touch-only shortcut. Two stops remain, one per role.
    val rowModifier = if (isTvPresentation) {
        modifier
            .background(color.copy(alpha = if (hasResolvedSource) 0.18f else 0.10f))
            .padding(TvSummarySegmentRingInset)
            .tvFocusable(shape = segmentShape, addFocusTarget = false)
            .clickable(onClick = onNameClick)
            .padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
    } else {
        modifier
            .background(color.copy(alpha = if (hasResolvedSource) 0.18f else 0.10f))
            .padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
    }
    Row(
        modifier = rowModifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = if (isTvPresentation) {
                Modifier.padding(3.dp)
            } else {
                Modifier
                    .clip(segmentShape)
                    .tvFocusable(shape = segmentShape, addFocusTarget = false)
                    .clickable(onClick = if (hasResolvedSource) onIconClick else onNameClick)
                    .padding(3.dp)
            },
            contentAlignment = Alignment.Center,
        ) {
            SourceSummaryIcon(displayModel = displayModel)
        }
        Text(
            text = label.ifBlank { fallbackLabel },
            modifier = if (isTvPresentation) {
                Modifier
            } else {
                Modifier
                    .tvFocusable(shape = segmentShape, addFocusTarget = false)
                    .clickable(onClick = onNameClick)
            },
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (hasResolvedSource) 1f else 0.68f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SourceSummaryIcon(
    displayModel: SourceOptionDisplayModel?,
) {
    when {
        displayModel?.source != null -> {
            ContentSourceIcon(
                source = displayModel.source,
                modifier = Modifier.size(14.dp),
                contentDescription = null,
            )
        }
        displayModel?.trackingService != null -> {
            Icon(
                painter = rememberSafePainter(displayModel.trackingService.iconResId),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(14.dp),
            )
        }
        else -> {
            Icon(
                painter = painterResource(R.drawable.ic_manga_source),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * A possible tracker match, offered as one line under the metadata strip rather than a card of its own: it is a
 * one-off prompt, so it should not weigh as much as the page's content. Tapping the text opens the candidate.
 */
@Composable
internal fun TrackingSuggestionRow(
    match: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatchResult,
    onBindClick: () -> Unit,
    onOpenClick: () -> Unit,
    onIgnoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .tvFocusable(shape = RoundedCornerShape(8.dp), addFocusTarget = false)
                .clickable(onClick = onOpenClick)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painter = rememberSafePainter(match.service.iconResId),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(
                    R.string.details_tracking_suggestion_summary,
                    stringResource(match.service.titleResId),
                    match.title,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            onClick = onBindClick,
            modifier = Modifier.tvFocusable(shape = RoundedCornerShape(999.dp), addFocusTarget = false),
        ) {
            Text(stringResource(R.string.tracking_bind_suggestion_action))
        }
        IconButton(
            onClick = onIgnoreClick,
            modifier = Modifier
                .size(40.dp)
                .tvFocusable(shape = RoundedCornerShape(999.dp), addFocusTarget = false),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.details_tracking_suggestion_ignore),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun ReadingStatusChip(
    status: ScrobblingStatus,
    scrobblingStatuses: Array<String>,
    linkedTrackingItems: List<LinkedTrackingItemUiModel>,
    onUpdateStatus: (ScrobblingStatus) -> Unit,
    chipHeight: androidx.compose.ui.unit.Dp = 32.dp,
) {
    var expanded by remember(status, linkedTrackingItems) { mutableStateOf(false) }
    var menuAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    val statusAnchorFocus = remember { FocusRequester() }
    val supportedStatuses = remember(linkedTrackingItems) {
        linkedTrackingItems
            .map { supportedStatusesForService(it.service).toSet() }
            .reduceOrNull { acc, statuses -> acc intersect statuses }
            ?.takeIf { it.isNotEmpty() }
            ?.toList()
            ?: ScrobblingStatus.entries
    }
    Box(
        modifier = Modifier.onGloballyPositioned { menuAnchorBounds = it.boundsInRoot() },
    ) {
        Surface(
            modifier = Modifier.height(chipHeight),
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        ) {
            Row(
                modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .focusRequester(statusAnchorFocus)
                        .clip(RoundedCornerShape(999.dp))
                        // A plain clickable keeps the stop but draws no ring; on TV the chip would
                        // be a silent stop (and the ring after a menu returns here).
                        .tvFocusable(shape = RoundedCornerShape(999.dp), addFocusTarget = false)
                        .clickable { expanded = true }
                        .padding(horizontal = 2.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = scrobblingStatuses.getOrElse(status.ordinal) { status.name },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        GlassDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(28.dp),
            style = GlassDefaults.subtleStyle(),
            useRootOverlay = LocalInterfaceStyle.current == InterfaceStyle.IOS,
            anchorBounds = menuAnchorBounds,
            // Root-overlay menus cannot hand the ring back themselves.
            anchorFocusRequester = statusAnchorFocus,
        ) {
            supportedStatuses.forEach { candidate ->
                CompactDropdownMenuItem(
                    text = {
                        Text(
                            text = scrobblingStatuses.getOrElse(candidate.ordinal) { candidate.name },
                        )
                    },
                    onClick = {
                        expanded = false
                        onUpdateStatus(candidate)
                    },
                    leadingIcon = if (status == candidate) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsSourceSelectorButton(
    modifier: Modifier = Modifier,
    label: String,
    currentDisplayModel: SourceOptionDisplayModel?,
    onPrimaryClick: () -> Unit,
    isMenuEnabled: Boolean,
    onMenuClick: () -> Unit,
) {
    val isPrimaryEnabled = currentDisplayModel != null

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.18f),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.34f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 42.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (isPrimaryEnabled) {
                                Modifier.clickable(onClick = onPrimaryClick)
                            } else {
                                Modifier
                            },
                        )
                        .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    when {
                        currentDisplayModel?.source != null -> {
                            ContentSourceIcon(
                                source = currentDisplayModel.source,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        currentDisplayModel?.trackingService != null -> {
                            Icon(
                                painter = rememberSafePainter(currentDisplayModel.trackingService.iconResId),
                                contentDescription = null,
                                tint = Color.Unspecified,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        else -> {
                            Icon(
                                painter = painterResource(R.drawable.ic_manga_source),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    Text(
                        text = currentDisplayModel?.selectorTitle.orEmpty(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (isPrimaryEnabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(22.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
                )
                Box(
                    modifier = Modifier
                        .clickable(enabled = isMenuEnabled, onClick = onMenuClick)
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = if (isMenuEnabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        },
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

internal fun isSensitiveDetailsTag(tag: ContentTag): Boolean {
    return tag.title.containsAdultTagKeyword()
}

internal data class SourceOptionDisplayModel(
    val title: String,
    val subtitle: String,
    val selectorTitle: String,
    val selectorSubtitle: String,
    val coverUrl: String?,
    val source: ContentSource?,
    val trackingService: ScrobblerService?,
    val linkedTrackingItem: LinkedTrackingItemUiModel?,
    val isSelected: Boolean,
    val badgeText: String? = null,
    val isActiveSource: Boolean = false,
)

@Composable
internal fun DetailsSourceOption.resolveDisplayModel(
    role: DetailsSourceRole,
    currentContent: Content?,
    linkedTrackingItem: LinkedTrackingItemUiModel?,
    strings: DetailsSourceDisplayStrings,
    isSelected: Boolean,
): SourceOptionDisplayModel {
    val sourceTitle = if (source != null) rememberResolvedSourceTitle(source) else ""
    val trackingTitle = trackingService?.let { stringResource(it.titleResId) }.orEmpty()
    val presentation = toPresentationModel(
        context = DetailsSourceDisplayContext(
            role = role,
            currentContentTitle = currentContent?.title,
            currentContentSourceName = currentContent?.source?.name,
            linkedTrackingTitle = linkedTrackingItem?.title,
            resolvedSourceTitle = sourceTitle,
            resolvedTrackingTitle = trackingTitle,
            strings = strings,
        ),
    )
    val coverUrl = coverUrl
        ?: linkedTrackingItem?.coverUrl
        ?: currentContent?.coverUrl?.takeIf { source != null && currentContent.source.name == source.name }
    val selectorTitle = when {
        trackingTitle.isNotBlank() -> trackingTitle
        sourceTitle.isNotBlank() -> sourceTitle
        !subtitle.isNullOrBlank() -> subtitle.orEmpty()
        else -> presentation.title
    }
    val selectorSubtitle = when {
        presentation.title.isNotBlank() && presentation.title != selectorTitle -> presentation.title
        presentation.subtitle.isNotBlank() -> presentation.subtitle
        else -> ""
    }
    return SourceOptionDisplayModel(
        title = presentation.title,
        subtitle = presentation.subtitle,
        selectorTitle = selectorTitle,
        selectorSubtitle = selectorSubtitle,
        coverUrl = coverUrl,
        source = source,
        trackingService = trackingService,
        linkedTrackingItem = linkedTrackingItem,
        isSelected = isSelected,
    )
}

@Composable
internal fun SourceOptionCard(
    displayModel: SourceOptionDisplayModel,
    onClick: () -> Unit,
    scrobblingStatuses: Array<String>,
    onTrackingStatusClick: ((LinkedTrackingItemUiModel, ScrobblingStatus) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var statusMenuExpanded by remember(displayModel.linkedTrackingItem?.service, displayModel.linkedTrackingItem?.remoteId) {
        mutableStateOf(false)
    }
    var statusMenuAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val optionCardColors = rememberGlassSurfaceColors(style = GlassDefaults.subtleStyle())
    Surface(
        modifier = modifier
            .width(112.dp)
            .tvFocusable(
                shape = RoundedCornerShape(if (expressive) 20.dp else 12.dp),
                addFocusTarget = false,
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(if (expressive) 20.dp else 12.dp),
        color = when {
            displayModel.isActiveSource -> if (expressive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            }
            displayModel.isSelected -> if (expressive) {
                optionCardColors.containerColor.detailsButtonContainerColor()
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            }
            expressive -> optionCardColors.containerColor.detailsPanelContainerColor()
            else -> optionCardColors.containerColor
        },
        border = when {
            displayModel.isActiveSource -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
            displayModel.isSelected -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            else -> optionCardColors.border
        },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp, 80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val resolvedCoverUrl = displayModel.coverUrl?.takeIfUsableImageUri()
                when {
                    resolvedCoverUrl != null -> {
                        val cacheKey = remember(displayModel.source?.name, resolvedCoverUrl) {
                            sharedCoverMemoryCacheKey(
                                sourceName = displayModel.source?.name,
                                ownerKey = displayModel.title,
                                url = resolvedCoverUrl,
                            )?.let { "${it}#details-source-cover" }
                        }
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(resolvedCoverUrl)
                                .memoryCacheKey(cacheKey)
                                .diskCacheKey(cacheKey)
                                .apply { displayModel.source?.let(::mangaSourceExtra) }
                                .crossfade(false)
                                .build(),
                            contentDescription = displayModel.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    displayModel.trackingService != null -> {
                        Icon(
                            painter = rememberSafePainter(displayModel.trackingService.iconResId),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    displayModel.source != null -> {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            ContentSourceIcon(
                                source = displayModel.source,
                                modifier = Modifier.size(20.dp),
                                contentDescription = null,
                            )
                        }
                    }
                    else -> {
                        Icon(
                            painter = painterResource(R.drawable.ic_extension),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                displayModel.badgeText?.let { badgeText ->
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.92f),
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = displayModel.title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                text = displayModel.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            val linkedTrackingItem = displayModel.linkedTrackingItem
            if (linkedTrackingItem != null && linkedTrackingItem.status != null && onTrackingStatusClick != null) {
                Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier.onGloballyPositioned { statusMenuAnchorBounds = it.boundsInRoot() },
                    ) {
                    SuggestionChip(
                        onClick = { statusMenuExpanded = true },
                        label = {
                            Text(
                                text = scrobblingStatuses.getOrElse(linkedTrackingItem.status.ordinal) {
                                    linkedTrackingItem.status.name
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            labelColor = MaterialTheme.colorScheme.onSurface,
                            iconContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.tvChipRing(),
                    )
                    GlassDropdownMenu(
                        expanded = statusMenuExpanded,
                        onDismissRequest = { statusMenuExpanded = false },
                        shape = RoundedCornerShape(28.dp),
                        style = GlassDefaults.subtleStyle(),
                        // SourceOptionCard is rendered inside the metadata/reading source sheets,
                        // which live in their own Dialog window. The app-level root overlay would
                        // be behind that window and the menu would be occluded by the sheet.
                        useRootOverlay = false,
                        anchorBounds = statusMenuAnchorBounds,
                    ) {
                        supportedStatusesForService(linkedTrackingItem.service).forEach { status ->
                            CompactDropdownMenuItem(
                                text = {
                                    Text(
                                        text = scrobblingStatuses.getOrElse(status.ordinal) { status.name },
                                    )
                                },
                                onClick = {
                                    statusMenuExpanded = false
                                    onTrackingStatusClick(linkedTrackingItem, status)
                                },
                                leadingIcon = if (linkedTrackingItem.status == status) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun supportedStatusesForService(service: ScrobblerService): List<ScrobblingStatus> {
    return when (service) {
        ScrobblerService.MAL,
        ScrobblerService.KITSU,
        ScrobblerService.MANGAUPDATES,
        ScrobblerService.SIMKL,
        ScrobblerService.TMDB,
        ScrobblerService.MANGABAKA,
        ScrobblerService.ANILIST,
        ScrobblerService.SHIKIMORI,
        -> listOf(
            ScrobblingStatus.PLANNED,
            ScrobblingStatus.READING,
            if (service == ScrobblerService.SHIKIMORI || service == ScrobblerService.MANGABAKA) {
                ScrobblingStatus.RE_READING
            } else null,
            ScrobblingStatus.COMPLETED,
            ScrobblingStatus.ON_HOLD,
            ScrobblingStatus.DROPPED,
        ).filterNotNull()

        ScrobblerService.BANGUMI -> listOf(
            ScrobblingStatus.PLANNED,
            ScrobblingStatus.READING,
            ScrobblingStatus.COMPLETED,
            ScrobblingStatus.ON_HOLD,
            ScrobblingStatus.DROPPED,
        )
    }
}
