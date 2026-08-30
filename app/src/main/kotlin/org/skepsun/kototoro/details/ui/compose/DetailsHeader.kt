package org.skepsun.kototoro.details.ui.compose


import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.image.tvboxSearchCoverModel
import org.skepsun.kototoro.core.model.iconResId
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.ui.compose.KototoroLinearProgressIndicator
import org.skepsun.kototoro.core.ui.compose.sharedCoverMemoryCacheKey
import org.skepsun.kototoro.core.ui.compose.AppLayoutTokens
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.ui.theme.isDarkTheme
import org.skepsun.kototoro.core.util.ext.copyToClipboard
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.core.util.ext.takeIfUsableImageUri
import org.skepsun.kototoro.core.util.ext.toLocaleOrNull
import org.skepsun.kototoro.details.data.ContentDetails
import org.skepsun.kototoro.details.ui.model.DetailsSourceOption
import org.skepsun.kototoro.details.ui.model.DetailsSourceDisplayStrings
import org.skepsun.kototoro.details.ui.model.DetailsSourceRole
import org.skepsun.kototoro.details.ui.model.DetailsSupplementAction
import org.skepsun.kototoro.details.ui.model.HistoryInfo
import org.skepsun.kototoro.details.ui.model.LinkedTrackingItemUiModel
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.parsers.model.ContentType
import java.util.Locale
import kotlin.math.roundToInt

internal fun Color.detailsButtonContainerColor(): Color = withDetailsMinAlpha(0.80f)

/**
 * Bounds on the title/description expansion. Without these, a pathologically long title or
 * description (some sources return megabyte-long strings) expands to `Int.MAX_VALUE` lines
 * inside a [CompositingStrategy.Offscreen] layer, which allocates an offscreen buffer
 * proportional to the whole paragraph — crashing or freezing the details page. Real titles
 * and descriptions are well below these limits, so the caps are purely defensive. The
 * Offscreen layer is also only used while collapsed (where the bottom fade needs it): the
 * layer is what makes the oversized buffer dangerous, so an expanded text never pays for one.
 */
private const val COLLAPSED_TITLE_MAX_LINES = 3
private const val EXPANDED_TITLE_MAX_LINES = 12
private const val MAX_RENDERED_TITLE_LENGTH = 1_000
private const val MAX_RENDERED_DESCRIPTION_LENGTH = 4_000


@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DetailsHeader(
    mangaDetails: ContentDetails?,
    localSize: Long,
    historyInfo: HistoryInfo,
    favouriteCategories: Set<FavouriteCategory>,
    linkedTrackingItems: List<LinkedTrackingItemUiModel>,
    readingStatus: ScrobblingStatus,
    unifiedRating: Float,
    canEditUnifiedRating: Boolean,
    trackingSuggestion: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatchResult?,
    metadataSourceOptions: List<DetailsSourceOption>,
    readingSourceOptions: List<DetailsSourceOption>,
    supplementalActions: List<DetailsSupplementAction>,
    resolvedContentType: ContentType?,
    metadataLanguageCode: String?,
    readingLanguageCode: String?,
    translatedTitle: String?,
    translatedDescription: String?,
    isShowingTranslation: Boolean,
    panoramaEnabled: Boolean,
    settings: AppSettings,
    collapseProgressProvider: () -> Float,
    coverVisualAlphaProvider: () -> Float,
    coverUrl: String?,
    fallbackCoverUrl: String?,
    sharedElementKey: String? = null,
    showWorkActions: Boolean = true,
    outerHorizontalPadding: Dp = AppLayoutTokens.screenHorizontalPadding,
    onInfoCardBoundsSync: (Float, Float) -> Unit,
    onCoverClick: (String?) -> Unit,
    onFavoriteClick: () -> Unit,
    onSourceClick: (ContentSource) -> Unit,
    onTrackingSourceClick: (DetailsSourceOption) -> Unit,
    onOpenTrackingDiscover: (ScrobblerService) -> Unit,
    onOpenMetadataSourceSheet: () -> Unit,
    onOpenReadingSourceSheet: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenSupplementalAction: (DetailsSupplementAction) -> Unit,
    onAuthorClick: (String) -> Unit,
    onTagClick: (ContentTag) -> Unit,
    onOpenLinkedTracking: (LinkedTrackingItemUiModel) -> Unit,
    onManageLinkedTracking: (LinkedTrackingItemUiModel) -> Unit,
    onUpdateLinkedTrackingStatus: (LinkedTrackingItemUiModel, ScrobblingStatus) -> Unit,
    onUpdateReadingStatus: (ScrobblingStatus) -> Unit,
    onUpdateUnifiedRating: (Float) -> Unit,
    onRemoveLinkedTracking: (org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatchResult) -> Unit,
    onBindTrackingSuggestion: (org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatchResult) -> Unit,
    onOpenTrackingSuggestion: (org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatchResult) -> Unit,
    onIgnoreTrackingSuggestion: (org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatchResult) -> Unit,
    onManageTrackingSuggestion: (org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatchResult) -> Unit,
) {
    val context = LocalContext.current
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val isDarkTheme = MaterialTheme.colorScheme.isDarkTheme()
    val immersiveTitleColor = if (isDarkTheme) Color.White else Color.Black
    val content = mangaDetails?.toContent()
    val originalTitle = content?.title.orEmpty()
    val displayTitle = translatedTitle ?: originalTitle
    val titleForDisplay = displayTitle.take(MAX_RENDERED_TITLE_LENGTH)
    val displayDescription = translatedDescription ?: mangaDetails?.description?.toString().orEmpty()
    val fallbackDescription = stringResource(R.string.no_description)
    val scrobblingStatuses = stringArrayResource(R.array.scrobbling_statuses)
    val defaultLocale = Locale.getDefault()
    val primaryAuthor = content?.authors?.firstOrNull { it.isNotBlank() }
    val author = primaryAuthor ?: stringResource(R.string.unknown_author)
    val hasKnownAuthor = primaryAuthor != null
    val originalLanguage = metadataLanguageCode
        ?.toLocaleOrNull()
        ?.getDisplayName(defaultLocale)
        ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(defaultLocale) else it.toString() }
        .orEmpty()
    val readingLanguage = readingLanguageCode
        ?.toLocaleOrNull()
        ?.getDisplayName(defaultLocale)
        ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(defaultLocale) else it.toString() }
        .orEmpty()
    val languageSummary = when {
        originalLanguage.isBlank() && readingLanguage.isBlank() -> stringResource(R.string.unknown)
        originalLanguage.isBlank() -> readingLanguage
        readingLanguage.isBlank() || readingLanguage == originalLanguage -> originalLanguage
        else -> "$originalLanguage -> $readingLanguage"
    }
    val chapterProgressLabel = when {
        historyInfo.totalChapters > 0 && historyInfo.currentChapter >= 0 -> "${historyInfo.currentChapter + 1}/${historyInfo.totalChapters}"
        historyInfo.totalChapters > 0 -> "0/${historyInfo.totalChapters}"
        else -> "-"
    }
    val isFavourite = favouriteCategories.isNotEmpty()
    val contentRating = content?.contentRating
    val alternateTitlesText = remember(isShowingTranslation, originalTitle, displayTitle, content?.altTitles) {
        buildList {
            if (isShowingTranslation && originalTitle.isNotBlank() && originalTitle != displayTitle) {
                add(originalTitle)
            }
            addAll(content?.altTitles.orEmpty().filter { it.isNotBlank() && it != displayTitle })
        }.distinct().joinToString(" / ")
    }
    val ratingLabel = remember(content?.hasRating, content?.rating, defaultLocale) {
        content
            ?.takeIf { it.hasRating }
            ?.let { String.format(defaultLocale, "%.1f", it.rating * 10f) }
    }
    val state = content?.state
    val progressLabel = if (historyInfo.history != null) {
        "${(historyInfo.percent * 100f).roundToInt()}%"
    } else {
        "-"
    }
    val localContent = mangaDetails?.local
    val onDeviceSizeLabel = localSize
        .takeIf { it > 0L }
        ?.let { Formatter.formatFileSize(context, it) }
    val metadataSourceOption = metadataSourceOptions.firstOrNull { it.isSelected } ?: metadataSourceOptions.firstOrNull()
    val readingSourceOption = readingSourceOptions.firstOrNull { it.isSelected } ?: readingSourceOptions.firstOrNull()
    val visibleTrackingSuggestion = trackingSuggestion?.takeUnless { suggestion ->
        linkedTrackingItems.any { linked ->
            linked.service == suggestion.service && linked.remoteId == suggestion.remoteId
        }
    }
    val readingSourceLabelRes = when (resolvedContentType) {
        ContentType.VIDEO,
        ContentType.HENTAI_VIDEO -> R.string.details_playback_source
        else -> R.string.details_reading_source
    }
    val readingLanguageLabelRes = when (resolvedContentType) {
        ContentType.VIDEO,
        ContentType.HENTAI_VIDEO -> R.string.details_playback_language_short
        else -> R.string.details_reading_language_short
    }
    val metadataDisplayModel = metadataSourceOption?.resolveDisplayModel(
        role = DetailsSourceRole.ENTITY_METADATA,
        currentContent = content,
        linkedTrackingItem = metadataSourceOption.trackingService?.let { service ->
            linkedTrackingItems.firstOrNull {
                it.service == service && it.remoteId == metadataSourceOption.remoteId
            }
        },
        strings = DetailsSourceDisplayStrings(
            unavailableText = stringResource(R.string.details_metadata_binding_unavailable),
            metadataBindingLabel = stringResource(R.string.details_entity_metadata_binding),
            currentProjectionLabel = stringResource(R.string.details_current_projection),
            switchableProjectionLabel = stringResource(R.string.details_switchable_projection),
        ),
        isSelected = true,
    )
    val readingDisplayModel = if (showWorkActions) {
        readingSourceOption?.resolveDisplayModel(
            role = DetailsSourceRole.READING_PROJECTION,
            currentContent = content,
            linkedTrackingItem = null,
            strings = DetailsSourceDisplayStrings(
                unavailableText = stringResource(R.string.details_reading_source_unavailable),
                metadataBindingLabel = stringResource(R.string.details_entity_metadata_binding),
                currentProjectionLabel = stringResource(readingSourceLabelRes),
                switchableProjectionLabel = stringResource(R.string.details_switchable_projection),
            ),
            isSelected = true,
        )
    } else {
        null
    }

    val normalizedCoverUrl = coverUrl?.takeIfUsableImageUri()
    val normalizedFallbackCoverUrl = fallbackCoverUrl?.takeIfUsableImageUri()
    var hasCoverLoadFailed by remember(normalizedCoverUrl) { mutableStateOf(false) }
    val currentCoverUrl = if (hasCoverLoadFailed && normalizedFallbackCoverUrl != null) {
        normalizedFallbackCoverUrl
    } else {
        normalizedCoverUrl
    }

    var isDescriptionExpanded by remember(settings.isDescriptionExpanded) { mutableStateOf(settings.isDescriptionExpanded) }
    var isTitleExpanded by rememberSaveable(displayTitle) { mutableStateOf(false) }
    var canExpandTitle by remember(displayTitle) { mutableStateOf(false) }
    val description = displayDescription.ifBlank { fallbackDescription }
    val descriptionForDisplay = description.take(MAX_RENDERED_DESCRIPTION_LENGTH)
    val collapsedDescriptionMaxLines = 3
    var canExpandDescription by remember(description) { mutableStateOf(false) }
    val coverModel = remember(content?.source?.name, content?.url, currentCoverUrl) {
        when {
            currentCoverUrl != null -> {
                val cacheKey = sharedCoverMemoryCacheKey(
                    sourceName = content?.source?.name,
                    ownerKey = content?.url,
                    url = currentCoverUrl,
                )
                ImageRequest.Builder(context)
                    .data(currentCoverUrl)
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .crossfade(false)
                    .apply { content?.let { mangaExtra(it) } }
                    .build()
            }
            content?.url?.startsWith("tvbox://item/") == true -> {
                val fallbackCacheKey = sharedCoverMemoryCacheKey(
                    sourceName = content.source.name,
                    ownerKey = content.url,
                    url = "tvbox-search-cover:${content.url}",
                )
                ImageRequest.Builder(context)
                    .data(tvboxSearchCoverModel(content))
                    .memoryCacheKey(fallbackCacheKey)
                    .diskCacheKey(fallbackCacheKey)
                    .crossfade(false)
                    .mangaExtra(content)
                    .build()
            }
            else -> null
        }
    }
    val isNsfw = content?.isNsfw() == true
    val infoItems = buildList {
        content?.let {
            add(
                DetailsInfoItem(
                    label = stringResource(R.string.author),
                    value = author,
                    iconRes = R.drawable.ic_info_outline,
                    valueMuted = !hasKnownAuthor,
                    onClick = if (primaryAuthor != null) {
                        { onAuthorClick(primaryAuthor) }
                    } else {
                        null
                    },
                ),
            )
        }
        add(
            DetailsInfoItem(
                label = stringResource(R.string.state),
                value = state?.let { stringResource(it.titleResId) } ?: stringResource(R.string.unknown),
                iconRes = state?.iconResId ?: R.drawable.ic_info_outline,
                valueMuted = state == null,
            ),
        )
        add(
            DetailsInfoItem(
                label = stringResource(readingLanguageLabelRes),
                value = languageSummary,
                iconRes = R.drawable.ic_language,
                valueMuted = originalLanguage.isBlank() && readingLanguage.isBlank(),
            ),
        )
        add(
            DetailsInfoItem(
                label = stringResource(R.string.chapters),
                value = chapterProgressLabel,
                iconRes = R.drawable.ic_book_page,
                onClick = onOpenChapters,
                showNavigationIndicator = true,
            ),
        )
        if (localContent != null || localSize > 0L) {
            add(
                DetailsInfoItem(
                    label = stringResource(R.string.on_device),
                    value = onDeviceSizeLabel ?: "-",
                    iconRes = R.drawable.ic_storage,
                ),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = outerHorizontalPadding,
                vertical = 12.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            DetailsCoverFrame(
                coverModel = coverModel,
                contentDescription = displayTitle,
                showNsfwBadge = isNsfw,
                sourceName = content?.source?.name,
                ownerKey = content?.url,
                coverUrl = currentCoverUrl,
                sharedElementKey = sharedElementKey,
                topBadgeText = ratingLabel,
                topBadgeIconRes = R.drawable.ic_star_small,
                onClick = { onCoverClick(currentCoverUrl) },
                onState = { state ->
                    if (state is coil3.compose.AsyncImagePainter.State.Error) {
                        hasCoverLoadFailed = true
                    }
                },
                modifier = Modifier
                    .graphicsLayer {
                        val coverCollapseProgress = (collapseProgressProvider() / 0.48f).coerceIn(0f, 1f)
                        alpha = (1f - coverCollapseProgress) * coverVisualAlphaProvider().coerceIn(0f, 1f)
                    },
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        val textCollapseProgress = ((collapseProgressProvider() - 0.08f) / 0.44f).coerceIn(0f, 1f)
                        alpha = 1f - textCollapseProgress
                    },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SelectionContainer(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = titleForDisplay,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 27.sp,
                        ),
                        color = if (panoramaEnabled) {
                            immersiveTitleColor
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize()
                            .then(
                                if (canExpandTitle && !isTitleExpanded) {
                                    Modifier
                                        .graphicsLayer {
                                            compositingStrategy = CompositingStrategy.Offscreen
                                        }
                                        .drawWithContent {
                                            drawContent()
                                            drawRect(
                                                brush = Brush.verticalGradient(
                                                    colors = listOf(Color.Black, Color.Transparent),
                                                    startY = size.height * 0.62f,
                                                    endY = size.height,
                                                ),
                                                blendMode = BlendMode.DstIn,
                                            )
                                        }
                                } else {
                                    Modifier
                                },
                            )
                            .clickable(enabled = canExpandTitle) {
                                isTitleExpanded = !isTitleExpanded
                            },
                        maxLines = if (isTitleExpanded) EXPANDED_TITLE_MAX_LINES else COLLAPSED_TITLE_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { textLayoutResult ->
                            val hasCollapsedOverflow = textLayoutResult.hasVisualOverflow ||
                                textLayoutResult.lineCount > COLLAPSED_TITLE_MAX_LINES
                            if (canExpandTitle != hasCollapsedOverflow) {
                                canExpandTitle = hasCollapsedOverflow
                            }
                        },
                    )
                }
                if (alternateTitlesText.isNotEmpty()) {
                    Text(
                        text = alternateTitlesText,
                        style = MaterialTheme.typography.labelMedium.copy(lineHeight = 16.sp),
                        color = if (panoramaEnabled) {
                            immersiveTitleColor.copy(alpha = 0.82f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (metadataDisplayModel != null || readingDisplayModel != null) {
                    DetailsSourceSummaryRow(
                        metadataDisplayModel = metadataDisplayModel,
                        readingDisplayModel = readingDisplayModel,
                        onMetadataIconClick = {
                            when {
                                metadataSourceOption?.source != null -> onSourceClick(metadataSourceOption.source)
                                metadataSourceOption?.trackingService != null -> onTrackingSourceClick(metadataSourceOption)
                            }
                        },
                        onMetadataNameClick = onOpenMetadataSourceSheet,
                        onReadingIconClick = {
                            when {
                                readingSourceOption?.source != null -> onSourceClick(readingSourceOption.source)
                                readingSourceOption?.trackingService != null -> onTrackingSourceClick(readingSourceOption)
                            }
                        },
                        onReadingNameClick = onOpenReadingSourceSheet,
                    )
                }
                if (supplementalActions.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(supplementalActions, key = { it.title + it.url }) { action ->
                            SuggestionChip(
                                onClick = { onOpenSupplementalAction(action) },
                                label = { Text(action.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                Row(
                    modifier = Modifier
                        .graphicsLayer {
                            val actionsCollapseProgress =
                                ((collapseProgressProvider() - 0.18f) / 0.36f).coerceIn(0f, 1f)
                            alpha = 1f - actionsCollapseProgress
                        }
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DetailsHeaderIconButton(
                        iconRes = R.drawable.ic_copy,
                        onClick = {
                            context.copyToClipboard(displayTitle, displayTitle)
                            Toast.makeText(context, R.string.details_title_copied, Toast.LENGTH_SHORT).show()
                        },
                        contentDescription = stringResource(R.string.details_copy_title),
                        buttonSize = 32.dp,
                        iconSize = 17.dp,
                    )
                    if (showWorkActions) {
                        DetailsHeaderIconButton(
                            iconRes = if (isFavourite) R.drawable.ic_heart else R.drawable.ic_heart_outline,
                            onClick = onFavoriteClick,
                            filled = isFavourite,
                            buttonSize = 32.dp,
                            iconSize = 17.dp,
                        )
                    }
                    if (showWorkActions) {
                        RatingStatusChip(
                            rating = unifiedRating,
                            canEditRating = canEditUnifiedRating,
                            status = readingStatus,
                            scrobblingStatuses = scrobblingStatuses,
                            linkedTrackingItems = linkedTrackingItems,
                            onUpdateRating = onUpdateUnifiedRating,
                            onUpdateStatus = onUpdateReadingStatus,
                        )
                    }
                }
            }
        }

        val showProgress = historyInfo.history != null && historyInfo.percent > 0f
        val showInfoCard = infoItems.isNotEmpty() || showProgress
        if (showInfoCard) {
            DetailsInfoPanelSurface(
                panoramaEnabled = panoramaEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        val bounds = coordinates.boundsInRoot()
                        onInfoCardBoundsSync(bounds.top, bounds.bottom)
                    },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(),
                ) {
                    if (infoItems.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            infoItems.chunked(2).forEach { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    rowItems.forEach { item ->
                                        MetadataItem(
                                            label = item.label,
                                            value = item.value,
                                            iconRes = item.iconRes,
                                            modifier = Modifier.weight(1f),
                                            valueMuted = item.valueMuted,
                                            onClick = item.onClick,
                                            showNavigationIndicator = item.showNavigationIndicator,
                                        )
                                    }
                                    if (rowItems.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                    if (showProgress) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 12.dp, top = if (infoItems.isNotEmpty()) 0.dp else 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            KototoroLinearProgressIndicator(
                                progress = { historyInfo.percent.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                            )
                            Text(
                                text = progressLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        visibleTrackingSuggestion?.let { suggestion ->
            TrackingSuggestionCard(
                match = suggestion,
                onBindClick = { onBindTrackingSuggestion(suggestion) },
                onOpenClick = { onOpenTrackingSuggestion(suggestion) },
                onIgnoreClick = { onIgnoreTrackingSuggestion(suggestion) },
            )
        }

        DetailsReadableSurface(
            panoramaEnabled = panoramaEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.description),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SelectionContainer {
                    Text(
                        text = descriptionForDisplay,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                enabled = canExpandDescription,
                                role = Role.Button,
                            ) {
                                isDescriptionExpanded = !isDescriptionExpanded
                            }
                            .animateContentSize()
                            .then(
                                if (canExpandDescription && !isDescriptionExpanded) {
                                    Modifier
                                        .graphicsLayer {
                                            compositingStrategy = CompositingStrategy.Offscreen
                                        }
                                        .drawWithContent {
                                            drawContent()
                                            drawRect(
                                                brush = Brush.verticalGradient(
                                                    colors = listOf(Color.Black, Color.Transparent),
                                                    startY = size.height * 0.62f,
                                                    endY = size.height,
                                                ),
                                                blendMode = BlendMode.DstIn,
                                            )
                                        }
                                } else {
                                    Modifier
                                },
                            ),
                        maxLines = if (isDescriptionExpanded) Int.MAX_VALUE else collapsedDescriptionMaxLines,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { textLayoutResult ->
                            val hasCollapsedOverflow = textLayoutResult.hasVisualOverflow ||
                                textLayoutResult.lineCount > collapsedDescriptionMaxLines
                            if (canExpandDescription != hasCollapsedOverflow) {
                                canExpandDescription = hasCollapsedOverflow
                            }
                        },
                    )
                }
            }
        }

        if (!content?.tags.isNullOrEmpty()) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 24.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        content?.tags.orEmpty().forEach { tag ->
                            val isSensitiveTag = isSensitiveDetailsTag(tag)
                            SuggestionChip(
                                onClick = { onTagClick(tag) },
                                modifier = Modifier.heightIn(min = 24.dp),
                                shape = RoundedCornerShape(8.dp),
                                label = {
                                    Text(
                                        text = tag.title,
                                        style = MaterialTheme.typography.labelSmall.copy(lineHeight = 12.sp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = if (isSensitiveTag) {
                                        Color(0xFFE3B341).copy(alpha = 0.22f)
                                    } else {
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.48f)
                                    },
                                    labelColor = if (isSensitiveTag) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                ),
                                border = SuggestionChipDefaults.suggestionChipBorder(
                                    enabled = true,
                                    borderColor = if (isSensitiveTag) {
                                        Color(0xFFE3B341).copy(alpha = 0.68f)
                                    } else {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

