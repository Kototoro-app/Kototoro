package org.skepsun.kototoro.home.ui.compose.hero

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.animation.ExperimentalSharedTransitionApi
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.util.Locale
import kotlin.random.Random
import androidx.core.text.HtmlCompat
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.ui.compose.HeroAutoAdvanceEffect
import org.skepsun.kototoro.core.ui.compose.HeroPagerIndicator
import org.skepsun.kototoro.core.ui.compose.ContentCoverShape
import org.skepsun.kototoro.core.ui.compose.rememberResolvedSourceTitle
import org.skepsun.kototoro.core.prefs.HomeHeroBackground
import org.skepsun.kototoro.core.prefs.HomeHeroContentLayout
import org.skepsun.kototoro.core.prefs.HomeHeroMode
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.HeroCoverSnapshotStore
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.unclippedBoundsInWindow
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.details.ui.compose.AnimatedPanoramaBackdrop
import org.skepsun.kototoro.details.ui.compose.PanoramaBackdropPrefs
import org.skepsun.kototoro.list.ui.compose.ContentCardNsfwBadge
import org.skepsun.kototoro.list.ui.compose.contentCardBadgeMetricsFor
import org.skepsun.kototoro.parsers.model.Content

import org.skepsun.kototoro.home.ui.compose.HomeBadge
import org.skepsun.kototoro.home.ui.compose.hasDistinctLargeCover
import org.skepsun.kototoro.home.ui.compose.homeHeroTonalColor
import org.skepsun.kototoro.home.ui.compose.rememberHomeCoverRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun HomeHeroSection(
    entries: List<HomeHeroEntry>,
    mode: HomeHeroMode,
    fixedPresentation: HomeHeroPresentation,
    panoramaPrefs: PanoramaBackdropPrefs,
    onClick: (Content, Rect?, String?) -> Unit,
    topContentInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
    autoAdvance: Boolean = false,
) {
    if (entries.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { entries.size })
    val selectedIndex by remember(entries, pagerState) {
        derivedStateOf { pagerState.currentPage.coerceIn(0, entries.lastIndex) }
    }
    val mixedSeed = rememberSaveable(entries.map(HomeHeroEntry::groupKey)) { Random.nextInt() }

    HeroAutoAdvanceEffect(
        pagerState = pagerState,
        pageCount = entries.size,
        intervalMillis = 5200L,
        enabled = autoAdvance,
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topContentInset),
    ) {
        val edgePadding = CompactTopBarHorizontalPadding
        val cardWidth = minOf(312.dp, maxWidth * 0.78f).coerceAtMost(
            (maxWidth - edgePadding * 2).coerceAtLeast(0.dp),
        )
        val pageSpacing = 6.dp
        val contentPadding = PaddingValues(horizontal = edgePadding)
        val viewportWidth = maxWidth
        val density = LocalDensity.current
        val contentPadPx = with(density) { contentPadding.calculateLeftPadding(LocalLayoutDirection.current).toPx() }
        val stepPx = with(density) { (cardWidth + pageSpacing).toPx() }
        val pagerWidthPx = with(density) { viewportWidth.toPx() }
        val cardWidthPx = with(density) { cardWidth.toPx() }

        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
            HorizontalPager(
                state = pagerState,
                pageSize = PageSize.Fixed(cardWidth),
                pageSpacing = pageSpacing,
                beyondViewportPageCount = 1,
                contentPadding = contentPadding,
                key = { page ->
                    entries.getOrNull(page)?.let { entry ->
                        "home_hero_${entry.kind.name}_${entry.groupKey}_${entry.content.id}"
                    } ?: "home_hero_pending_$page"
                },
                modifier = Modifier.width(viewportWidth),
            ) { page ->
                entries.getOrNull(page)?.let { entry ->
                    val presentation = resolveHomeHeroPresentation(
                        mode = mode,
                        fixedPresentation = fixedPresentation,
                        signals = HomeHeroStyleSignals(
                            contentType = entry.content.source.getContentType(),
                            isResume = entry.kind == HomeHeroKind.RESUME,
                            hasDistinctLargeCover = entry.content.hasDistinctLargeCover(),
                            isRecommendation = entry.kind == HomeHeroKind.RECOMMENDATION,
                        ),
                        page = page,
                        mixedSeed = mixedSeed,
                    )
                    HomeHeroCard(
                        entry = entry,
                        presentation = presentation,
                        cardHeight = HOME_HERO_CARD_HEIGHT,
                        panoramaPrefs = panoramaPrefs,
                        onClick = onClick,
                        modifier = Modifier
                            .zIndex(if (page == selectedIndex) 1f else 0f)
                            .graphicsLayer {
                                val rawOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                                val signedOffset = rawOffset.coerceIn(-1f, 1f)
                                val visualLeft = contentPadPx - rawOffset * stepPx
                                val visualRight = visualLeft + cardWidthPx
                                val focus = when {
                                    visualRight <= 0f || visualLeft >= pagerWidthPx -> 0f
                                    visualLeft >= 0f && visualRight <= pagerWidthPx -> 1f
                                    visualLeft < 0f -> (visualRight / cardWidthPx).coerceIn(0f, 1f)
                                    else -> ((pagerWidthPx - visualLeft) / cardWidthPx).coerceIn(0f, 1f)
                                }
                                val hOrigin = when {
                                    signedOffset < -0.02f -> 0f
                                    signedOffset > 0.02f -> 1f
                                    else -> 0.5f
                                }
                                scaleX = 0.9f + (0.1f * focus)
                                scaleY = 0.9f + (0.1f * focus)
                                alpha = 0.64f + (0.36f * focus)
                                transformOrigin = TransformOrigin(hOrigin, 0.5f)
                            },
                    )
                }
            }
            }
            if (entries.size > 1) {
                val currentEntry = entries[selectedIndex]
                val indicatorColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                HeroPagerIndicator(
                    pageCount = entries.size,
                    currentPage = selectedIndex,
                    pageCounter = "${selectedIndex + 1} / ${entries.size}",
                    counterColor = indicatorColor,
                    trailingIcon = {
                        Icon(
                            painter = painterResource(currentEntry.kind.iconRes),
                            contentDescription = null,
                            tint = indicatorColor,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    modifier = Modifier.padding(start = edgePadding, top = 6.dp),
                )
            }
        }

    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun HomeHeroCard(
    entry: HomeHeroEntry,
    presentation: HomeHeroPresentation,
    cardHeight: Dp,
    panoramaPrefs: PanoramaBackdropPrefs,
    onClick: (Content, Rect?, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val content = entry.content
    val backdropRequest = rememberHomeCoverRequest(
        context = context,
        content = content,
        allowCrossfade = true,
        memoryCacheVariant = "home_hero_backdrop",
    )
    val coverRequest = rememberHomeCoverRequest(
        context = context,
        content = content,
        allowCrossfade = true,
        memoryCacheVariant = "home_hero_cover",
    )
    var coverBounds by remember(entry.kind, entry.groupKey) { mutableStateOf<Rect?>(null) }
    val sharedElementKey = remember(entry.kind, entry.groupKey, content.coverUrl, content.source.name) {
        contentCoverSharedKey(
            sourceName = content.source.name,
            url = content.coverUrl.orEmpty(),
            instanceKey = "home_hero_${entry.kind.name.lowercase(Locale.ROOT)}_${entry.groupKey}",
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(cardHeight)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick(content, coverBounds, sharedElementKey) },
    ) {
        when (presentation.background) {
            HomeHeroBackground.BLURRED_ARTWORK -> if (panoramaPrefs.isEnabled && backdropRequest != null) {
                AnimatedPanoramaBackdrop(
                    prefs = panoramaPrefs,
                    model = backdropRequest,
                    placeholderMemoryCacheKey = coverRequest?.memoryCacheKey,
                    snapshotKey = sharedElementKey,
                    contentAlpha = 0.94f,
                    backgroundColor = MaterialTheme.colorScheme.surface,
                )
            } else if (backdropRequest != null) {
                AsyncImage(
                    model = backdropRequest,
                    contentDescription = content.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            HomeHeroBackground.TONAL -> Box(
                Modifier.fillMaxSize().background(homeHeroTonalColor(content.id, isSystemInDarkTheme())),
            )
            HomeHeroBackground.IMMERSIVE_ARTWORK -> if (coverRequest != null) {
                AsyncImage(
                    model = coverRequest,
                    contentDescription = content.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            HomeHeroBackground.COVER_SPLIT -> Row(Modifier.fillMaxSize()) {
                if (coverRequest != null) {
                    AsyncImage(
                        model = coverRequest,
                        contentDescription = content.title,
                        modifier = Modifier.weight(0.46f).fillMaxHeight(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(Modifier.weight(0.46f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant))
                }
                Box(
                    modifier = Modifier
                        .weight(0.54f)
                        .fillMaxHeight()
                        .background(homeHeroTonalColor(content.id, isSystemInDarkTheme())),
                )
            }
            HomeHeroBackground.PLAIN -> Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
        if (presentation.background == HomeHeroBackground.BLURRED_ARTWORK ||
            presentation.background == HomeHeroBackground.IMMERSIVE_ARTWORK
        ) {
            Box(Modifier.fillMaxSize().drawBehind {
                drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.20f), Color.Black.copy(alpha = 0.52f))))
            })
        }
        val textColor = if (presentation.background == HomeHeroBackground.PLAIN) {
            MaterialTheme.colorScheme.onSurface
        } else {
            Color.White
        }
        HomeHeroInfoLayout(
            entry = entry,
            content = content,
            presentation = presentation,
            coverRequest = coverRequest,
            sharedElementKey = sharedElementKey,
            textColor = textColor,
            onBoundsChanged = { coverBounds = it },
        )

    }
}

@Composable
private fun HomeHeroInfoLayout(
    entry: HomeHeroEntry,
    content: Content,
    presentation: HomeHeroPresentation,
    coverRequest: ImageRequest?,
    sharedElementKey: String,
    textColor: Color,
    onBoundsChanged: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSplit = presentation.background == HomeHeroBackground.COVER_SPLIT
    val contentModifier = modifier.fillMaxSize()
    Row(modifier = contentModifier) {
        if (isSplit) Spacer(Modifier.weight(0.46f))
        Box(modifier = Modifier.weight(if (isSplit) 0.54f else 1f).fillMaxHeight()) {
        when (presentation.contentLayout) {
            HomeHeroContentLayout.STANDARD -> Row(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isSplit) {
                    HomeHeroPoster(coverRequest, content, sharedElementKey, 72.dp, 112.dp, onBoundsChanged)
                }
                HomeHeroText(entry, content, compact = true, textColor = textColor, modifier = Modifier.weight(1f))
            }
            HomeHeroContentLayout.EDITORIAL -> Column(
                modifier = Modifier.fillMaxSize().padding(18.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                HomeHeroText(entry, content, compact = false, textColor = textColor)
                if (!isSplit) {
                    HomeHeroPoster(coverRequest, content, sharedElementKey, 54.dp, 72.dp, onBoundsChanged,
                        modifier = Modifier.align(Alignment.End))
                }
            }
            HomeHeroContentLayout.TEXT_QUOTE -> HomeHeroTextQuote(
                entry, content, textColor = textColor, modifier = Modifier.fillMaxSize(),
            )
            HomeHeroContentLayout.MINIMAL_PROGRESS -> HomeHeroMinimal(
                entry, content, textColor = textColor, modifier = Modifier.fillMaxSize(),
            )
            HomeHeroContentLayout.DETAILS -> Row(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isSplit) {
                    HomeHeroPoster(coverRequest, content, sharedElementKey, 86.dp, 126.dp, onBoundsChanged)
                }
                HomeHeroText(entry, content, compact = true, textColor = textColor, modifier = Modifier.weight(1f))
            }
        }
        }
    }
}

@Composable
private fun HomeHeroPoster(
    request: ImageRequest?,
    content: Content,
    snapshotKey: String,
    width: Dp,
    height: Dp,
    onBoundsChanged: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .onGloballyPositioned { onBoundsChanged(it.unclippedBoundsInWindow()) }
            .then(
                if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedElement(
                            rememberSharedContentState(key = snapshotKey),
                            animatedVisibilityScope = animatedVisibilityScope,
                        )
                    }
                } else Modifier,
            )
            .clip(ContentCoverShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.28f)),
    ) {
        if (request != null) {
            HomeHeroCoverImage(
                request = request,
                cacheKey = request.memoryCacheKey,
                snapshotKey = snapshotKey,
                contentDescription = content.title,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { result -> HeroCoverSnapshotStore.put(snapshotKey, result.image) },
            )
        }
        if (content.isNsfw()) {
            ContentCardNsfwBadge(
                metrics = contentCardBadgeMetricsFor(width),
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            )
        }
    }
}

@Composable
private fun HomeHeroText(
    entry: HomeHeroEntry,
    content: Content,
    compact: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp)) {
        HomeBadge(text = stringResource(entry.kind.labelRes), iconRes = entry.kind.iconRes)
        Text(
            text = content.title,
            style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
            color = textColor,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = rememberResolvedSourceTitle(content.source),
            style = MaterialTheme.typography.bodyMedium,
            color = textColor.copy(alpha = 0.86f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        entry.supportingText()?.let { supportingText ->
            Text(
                text = supportingText,
                style = MaterialTheme.typography.labelLarge,
                color = textColor.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HomeHeroTextQuote(
    entry: HomeHeroEntry,
    content: Content,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val parsedDescription = remember(content.description) { content.description?.toHeroExcerpt() }
    val excerpt = parsedDescription ?: entry.supportingText()
    Row(
        modifier = modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(72.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            HomeBadge(text = stringResource(entry.kind.labelRes), iconRes = entry.kind.iconRes)
            Text(
                text = excerpt?.let { "\"$it\"" } ?: content.title,
                style = MaterialTheme.typography.titleLarge,
                color = textColor,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (excerpt != null) {
                Text(
                    text = content.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = content.authors.firstOrNull() ?: rememberResolvedSourceTitle(content.source),
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun String.toHeroExcerpt(): String? = HtmlCompat.fromHtml(this, HtmlCompat.FROM_HTML_MODE_COMPACT)
    .toString()
    .replace(Regex("\\s+"), " ")
    .trim()
    .takeIf(String::isNotEmpty)

@Composable
private fun HomeHeroMinimal(
    entry: HomeHeroEntry,
    content: Content,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        HomeBadge(text = stringResource(entry.kind.labelRes), iconRes = entry.kind.iconRes)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = content.title,
            style = MaterialTheme.typography.headlineSmall,
            color = textColor,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = entry.supportingText() ?: rememberResolvedSourceTitle(content.source),
            style = MaterialTheme.typography.bodyMedium,
            color = textColor.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        entry.progressPercent?.let { progress ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0, 100) / 100f)
                        .height(5.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

