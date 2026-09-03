package org.skepsun.kototoro.details.ui.compose


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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
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
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.compose.rememberResolvedSourceTitle
import org.skepsun.kototoro.core.ui.compose.sharedCoverMemoryCacheKey
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.glass.LiquidGlassSurface
import org.skepsun.kototoro.core.ui.glass.rememberGlassSurfaceColors
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
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
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun DetailsInfoPanelSurface(
    panoramaEnabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    DetailsReadableSurface(
        panoramaEnabled = panoramaEnabled,
        modifier = modifier,
        content = content,
    )
}

@Composable
internal fun DetailsReadableSurface(
    panoramaEnabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val shape = RoundedRectangle(LocalInterfaceStyleTokens.current.groupCornerRadius)
    if (isIosStyle) {
        LiquidGlassSurface(
            modifier = modifier,
            style = GlassDefaults.regularStyle().copy(
                containerAlpha = 0.88f,
                borderAlpha = 0.18f,
            ),
            shape = shape,
            componentRole = GlassComponentRole.ContentOverlay,
            // Large static info panel: no always-on edge highlight, brightens
            // only while the user is pressing it.
            highlightOnIdle = false,
        ) {
            content()
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = if (panoramaEnabled) {
                MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.90f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f),
            ),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            content()
        }
    }
}

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
    BoxWithConstraints(modifier = Modifier.wrapContentWidth()) {
        val maxSegmentWidth = ((maxWidth - 1.dp) / 2f).coerceAtLeast(1.dp)
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
                SourceSummarySegment(
                    label = metadataTitle,
                    fallbackLabel = metadataFallback,
                    displayModel = metadataDisplayModel,
                    color = MaterialTheme.colorScheme.primary,
                    onIconClick = onMetadataIconClick,
                    onNameClick = onMetadataNameClick,
                    modifier = Modifier.widthIn(max = maxSegmentWidth),
                )
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
    Row(
        modifier = modifier
            .background(color.copy(alpha = if (hasResolvedSource) 0.18f else 0.10f))
            .padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = if (hasResolvedSource) onIconClick else onNameClick)
                .padding(3.dp),
            contentAlignment = Alignment.Center,
        ) {
            SourceSummaryIcon(displayModel = displayModel)
        }
        Text(
            text = label.ifBlank { fallbackLabel },
            modifier = Modifier.clickable(onClick = onNameClick),
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

@Composable
internal fun TrackingSuggestionCard(
    match: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatchResult,
    onBindClick: () -> Unit,
    onOpenClick: () -> Unit,
    onIgnoreClick: () -> Unit,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val defaultLocale = Locale.getDefault()
    val confidenceLabel = String.format(defaultLocale, "%.0f%%", match.confidence * 100f)
    val cardContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = rememberSafePainter(match.service.iconResId),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(18.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.details_tracking_suggestion_title),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(
                            R.string.details_tracking_suggestion_summary,
                            stringResource(match.service.titleResId),
                            match.title,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                ) {
                    Text(
                        text = confidenceLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SuggestionChip(
                    onClick = onBindClick,
                    shape = RoundedCornerShape(if (expressive) 999.dp else 8.dp),
                    label = { Text(stringResource(R.string.tracking_bind_suggestion_action)) },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                        )
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        labelColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                SuggestionChip(
                    onClick = onOpenClick,
                    shape = RoundedCornerShape(if (expressive) 999.dp else 8.dp),
                    label = { Text(stringResource(R.string.details_tracking_suggestion_view)) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.42f),
                    ),
                    border = SuggestionChipDefaults.suggestionChipBorder(
                        enabled = true,
                        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    ),
                )
                SuggestionChip(
                    onClick = onIgnoreClick,
                    shape = RoundedCornerShape(if (expressive) 999.dp else 8.dp),
                    label = { Text(stringResource(R.string.details_tracking_suggestion_ignore)) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.42f),
                    ),
                    border = SuggestionChipDefaults.suggestionChipBorder(
                        enabled = true,
                        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    ),
                )
            }
        }
    }
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        style = GlassDefaults.subtleStyle().copy(
            containerAlpha = 0.78f,
            borderAlpha = if (expressive) 0.26f else 0.22f,
        ),
        shape = if (expressive) Capsule() else RoundedRectangle(22.dp),
    ) {
        cardContent()
    }
}

@Composable
internal fun RatingStatusChip(
    rating: Float,
    canEditRating: Boolean,
    status: ScrobblingStatus,
    scrobblingStatuses: Array<String>,
    linkedTrackingItems: List<LinkedTrackingItemUiModel>,
    onUpdateRating: (Float) -> Unit,
    onUpdateStatus: (ScrobblingStatus) -> Unit,
) {
    var expanded by remember(status, linkedTrackingItems) { mutableStateOf(false) }
    var menuAnchorBounds by remember { mutableStateOf<Rect?>(null) }
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
            modifier = Modifier.height(32.dp),
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        ) {
            Row(
                modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactRatingChip(
                    rating = rating,
                    enabled = canEditRating,
                    onRatingChanged = onUpdateRating,
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(18.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)),
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
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

@Composable
private fun CompactRatingChip(
    rating: Float,
    enabled: Boolean,
    onRatingChanged: (Float) -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var menuAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    val score = (rating.coerceIn(0f, 1f) * 10f).roundToInt()
    val contentAlpha = if (score > 0) 1f else 0.62f
    Box(
        modifier = Modifier.onGloballyPositioned { menuAnchorBounds = it.boundsInRoot() },
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable {
                    if (enabled) {
                        expanded = true
                    } else {
                        Toast.makeText(
                            context,
                            R.string.details_rating_requires_tracking,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                .padding(horizontal = 2.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = if (score > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }.copy(alpha = if (enabled) contentAlpha else 0.45f),
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = score.toString(),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface
                }.copy(alpha = contentAlpha),
                maxLines = 1,
            )
        }
        GlassDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(24.dp),
            style = GlassDefaults.subtleStyle(),
            useRootOverlay = LocalInterfaceStyle.current == InterfaceStyle.IOS,
            anchorBounds = menuAnchorBounds,
        ) {
            (0..10).forEach { candidate ->
                CompactDropdownMenuItem(
                    text = { Text(candidate.toString()) },
                    onClick = {
                        expanded = false
                        onRatingChanged(candidate / 10f)
                    },
                    leadingIcon = if (candidate == score) {
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
    val isActiveProjection: Boolean = false,
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
            isSelected = isSelected,
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
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(if (expressive) 20.dp else 12.dp),
        color = when {
            displayModel.isActiveProjection -> if (expressive) {
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
            displayModel.isActiveProjection -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
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
        ScrobblerService.ANILIST,
        ScrobblerService.SHIKIMORI,
        -> listOf(
            ScrobblingStatus.PLANNED,
            ScrobblingStatus.READING,
            if (service == ScrobblerService.SHIKIMORI) ScrobblingStatus.RE_READING else null,
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

