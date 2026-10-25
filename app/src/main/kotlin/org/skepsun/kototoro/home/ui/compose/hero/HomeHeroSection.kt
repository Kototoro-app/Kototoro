package org.skepsun.kototoro.home.ui.compose.hero

import org.skepsun.kototoro.core.ui.adaptive.TabletLayoutClass
import org.skepsun.kototoro.core.ui.home.HomeHeroCardSurface
import org.skepsun.kototoro.core.ui.home.HomeHeroPager
import org.skepsun.kototoro.core.ui.home.HomeHeroStandardContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.skepsun.kototoro.main.ui.compose.LocalMainContentFocusAnchor
import androidx.compose.animation.ExperimentalSharedTransitionApi
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.launch
import androidx.core.text.HtmlCompat
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.ui.compose.HeroAutoAdvanceEffect
import org.skepsun.kototoro.core.ui.compose.HeroPagerIndicator
import org.skepsun.kototoro.core.ui.compose.HeroTransitionPhase
import org.skepsun.kototoro.core.ui.compose.LocalHeroTransitionPhase
import org.skepsun.kototoro.core.ui.compose.ContentCoverShape
import org.skepsun.kototoro.core.ui.compose.rememberResolvedSourceTitle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.core.prefs.HomeHeroBackground
import org.skepsun.kototoro.core.prefs.HomeHeroContentLayout
import org.skepsun.kototoro.core.prefs.HomeHeroMode
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
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
import androidx.compose.runtime.withFrameNanos

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
    layoutClass: TabletLayoutClass = TabletLayoutClass.COMPACT,
) {
    if (entries.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { entries.size })
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    val heroFocusRequester = remember { FocusRequester() }
    val initialFocusPage = remember { pagerState.currentPage }
    var initialFocusAttempted by rememberSaveable { mutableStateOf(false) }
    // TV: the hero card is the first stop of the page, so publish its requester to the top bar. The bar
    // is an overlay sibling of the content and cannot search its way down into the page on its own.
    val contentFocusAnchor = LocalMainContentFocusAnchor.current.takeIf { isTvPresentation }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(contentFocusAnchor, lifecycleOwner) {
        val anchor = contentFocusAnchor ?: return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> anchor.register(heroFocusRequester)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                    anchor.unregister(heroFocusRequester)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            anchor.register(heroFocusRequester)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            anchor.unregister(heroFocusRequester)
        }
    }
    val selectedIndex by remember(entries, pagerState) {
        derivedStateOf { pagerState.currentPage.coerceIn(0, entries.lastIndex) }
    }
    val mixedSeed = rememberSaveable(entries.map(HomeHeroEntry::groupKey)) { Random.nextInt() }
    val navigationScope = rememberCoroutineScope()
    // The shared-element flight measures the cover through this pager item's
    // graphicsLayer on the way out, and lands on the rect the card settles into.
    // Anything still moving when that rect is captured points the flight at a rect
    // the landing never reaches — the cover lands low and small, then snaps. So the
    // card that owns the flight is held at rest for the whole hero window, and a
    // still-glideing pager is landed before the route changes.
    val heroPhase = LocalHeroTransitionPhase.current

    HeroAutoAdvanceEffect(
        pagerState = pagerState,
        pageCount = entries.size,
        intervalMillis = 5200L,
        enabled = autoAdvance && !isTvPresentation,
    )
    // Recommendation refreshes must not steal focus from another row or the navigation rail.
    LaunchedEffect(isTvPresentation) {
        if (isTvPresentation && !initialFocusAttempted) {
            withFrameNanos { }
            // Lazy item re-entry must not pull focus back from a lower content row.
            initialFocusAttempted = true
            runCatching { heroFocusRequester.requestFocus() }
        }
    }
    val activeContentId = entries.getOrNull(selectedIndex)?.content?.id ?: -1L
    val isDarkTheme = isSystemInDarkTheme()
    val activeTonalColor = remember(activeContentId, isDarkTheme) {
        if (activeContentId != -1L) homeHeroTonalColor(activeContentId, isDarkTheme) else Color.Transparent
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val auraColor = if (activeTonalColor != Color.Transparent) {
                    activeTonalColor.copy(alpha = 0.22f)
                } else {
                    Color.Transparent
                }
                if (auraColor != Color.Transparent) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(auraColor, auraColor.copy(alpha = 0.06f), Color.Transparent),
                            center = Offset(size.width * 0.5f, 0f),
                            radius = size.width * 0.85f,
                        ),
                        center = Offset(size.width * 0.5f, 0f),
                        radius = size.width * 0.85f,
                    )
                }
            }
            .padding(top = topContentInset),
    ) {
        HomeHeroPager(
            entries = entries,
            key = { entry -> "home_hero_${entry.kind.name}_${entry.groupKey}_${entry.content.id}" },
            pagerState = pagerState,
            layoutClass = layoutClass,
            tvPresentation = isTvPresentation,
            holdSelectedCardStill = heroPhase != HeroTransitionPhase.Idle,
            pagerModifier = if (isTvPresentation) Modifier.focusRestorer().focusGroup() else Modifier,
        ) { page, entry, cardModifier ->
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
                cardHeight = if (isTvPresentation) 240.dp else HOME_HERO_CARD_HEIGHT,
                panoramaPrefs = panoramaPrefs,
                showIndicator = page == selectedIndex,
                indicator = if (entries.size > 1) {
                    HeroIndicatorState(
                        pageCount = entries.size,
                        currentPage = selectedIndex,
                    )
                } else {
                    null
                },
                onClick = { content, coverBounds, sharedElementKey ->
                    // Land the pager before the route changes: the flight's start rect is
                    // measured from this card the moment Home leaves the composition, so a
                    // card that is still gliding would be recorded mid-glide and the return
                    // flight would snap to the settled rect later. `finally` so a cancelled
                    // scope can never swallow the tap.
                    val gliding = page == pagerState.currentPage &&
                        (pagerState.isScrollInProgress || pagerState.currentPageOffsetFraction != 0f)
                    if (gliding) {
                        navigationScope.launch {
                            try {
                                pagerState.scrollToPage(page)
                            } finally {
                                onClick(content, coverBounds, sharedElementKey)
                            }
                        }
                    } else {
                        onClick(content, coverBounds, sharedElementKey)
                    }
                },
                modifier = cardModifier,
                focusRequester = heroFocusRequester.takeIf { page == initialFocusPage },
            )
        }
    }
}

/** State for the pager indicator rendered inside the hero card; null hides it. */
internal data class HeroIndicatorState(
    val pageCount: Int,
    val currentPage: Int,
)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun HomeHeroCard(
    entry: HomeHeroEntry,
    presentation: HomeHeroPresentation,
    cardHeight: Dp,
    panoramaPrefs: PanoramaBackdropPrefs,
    indicator: HeroIndicatorState?,
    showIndicator: Boolean,
    onClick: (Content, Rect?, String?) -> Unit,
    focusRequester: FocusRequester? = null,
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
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    var isFocused by remember { mutableStateOf(false) }
    // The hero card shares the home section trays' restrained corner radius so
    // the top of the page reads as one family with the trays below it.
    val heroCardShape = RoundedCornerShape(LocalInterfaceStyleTokens.current.sectionCornerRadius)

    HomeHeroCardSurface(
        modifier = modifier,
        cardHeight = cardHeight,
        onClick = { onClick(content, coverBounds, sharedElementKey) },
        interactionModifier = Modifier
            .then(if (isTvPresentation && focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .then(if (isTvPresentation && isFocused) {
                Modifier.border(3.dp, MaterialTheme.colorScheme.primary, heroCardShape)
            } else Modifier),
        artworkScrim = presentation.background == HomeHeroBackground.BLURRED_ARTWORK ||
            presentation.background == HomeHeroBackground.IMMERSIVE_ARTWORK,
        background = {
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
        },
    ) {
        val textColor = if (presentation.background == HomeHeroBackground.PLAIN) {
            MaterialTheme.colorScheme.onSurface
        } else {
            Color.White
        }
        val indicatorPlacement = indicator?.let {
            resolveHeroIndicatorPlacement(presentation.contentLayout)
        }
        HomeHeroInfoLayout(
            entry = entry,
            content = content,
            presentation = presentation,
            coverRequest = coverRequest,
            sharedElementKey = sharedElementKey,
            textColor = textColor,
            bottomAvoidance = indicatorPlacement?.bottomAvoidance ?: 0.dp,
            onBoundsChanged = { coverBounds = it },
        )
        if (showIndicator && indicator != null && indicatorPlacement != null) {
            val onArtwork = presentation.background != HomeHeroBackground.PLAIN
            HeroPagerIndicator(
                pageCount = indicator.pageCount,
                currentPage = indicator.currentPage,
                activeColor = if (onArtwork) Color.White.copy(alpha = 0.92f) else MaterialTheme.colorScheme.primary,
                inactiveColor = if (onArtwork) {
                    Color.White.copy(alpha = 0.38f)
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)
                },
                modifier = Modifier
                    .align(indicatorPlacement.alignment)
                    .padding(start = 14.dp, end = 14.dp, bottom = 7.dp),
            )
        }

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
    bottomAvoidance: Dp,
    onBoundsChanged: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSplit = presentation.background == HomeHeroBackground.COVER_SPLIT
    if (presentation.contentLayout == HomeHeroContentLayout.STANDARD ||
        presentation.contentLayout == HomeHeroContentLayout.DETAILS
    ) {
        val details = presentation.contentLayout == HomeHeroContentLayout.DETAILS
        HomeHeroStandardContent(
            modifier = modifier,
            splitArtwork = isSplit,
            posterWidth = if (details) 86.dp else 72.dp,
            posterHeight = if (details) 126.dp else 112.dp,
            poster = { width, height ->
                HomeHeroPoster(coverRequest, content, sharedElementKey, width, height, onBoundsChanged)
            },
            text = { textModifier ->
                HomeHeroText(entry, content, compact = true, textColor = textColor, modifier = textModifier)
            },
        )
        return
    }
    val contentModifier = modifier.fillMaxSize()
    Row(modifier = contentModifier) {
        if (isSplit) Spacer(Modifier.weight(0.46f))
        Box(modifier = Modifier.weight(if (isSplit) 0.54f else 1f).fillMaxHeight()) {
        when (presentation.contentLayout) {
            HomeHeroContentLayout.EDITORIAL -> Column(
                modifier = Modifier.fillMaxSize().padding(18.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                HomeHeroText(entry, content, compact = false, textColor = textColor)
                if (!isSplit) {
                    HomeHeroPoster(coverRequest, content, sharedElementKey, 54.dp, 72.dp, onBoundsChanged,
                        modifier = Modifier.align(Alignment.End).padding(bottom = bottomAvoidance))
                }
            }
            HomeHeroContentLayout.TEXT_QUOTE -> HomeHeroTextQuote(
                entry, content, textColor = textColor,
                bottomAvoidance = bottomAvoidance,
                modifier = Modifier.fillMaxSize(),
            )
            HomeHeroContentLayout.MINIMAL_PROGRESS -> HomeHeroMinimal(
                entry, content, textColor = textColor,
                bottomAvoidance = bottomAvoidance,
                modifier = Modifier.fillMaxSize(),
            )
            HomeHeroContentLayout.STANDARD, HomeHeroContentLayout.DETAILS -> Unit
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
    // Shared with the Windows host's hero.
    org.skepsun.kototoro.core.ui.home.HomeHeroText(
        kindLabel = stringResource(entry.kind.labelRes),
        kindIcon = painterResource(entry.kind.iconRes),
        title = content.title,
        sourceTitle = rememberResolvedSourceTitle(content.source),
        supportingText = entry.supportingText(),
        compact = compact,
        textColor = textColor,
        modifier = modifier,
    )
}

@Composable
private fun HomeHeroTextQuote(
    entry: HomeHeroEntry,
    content: Content,
    textColor: Color,
    bottomAvoidance: Dp,
    modifier: Modifier = Modifier,
) {
    val parsedDescription = remember(content.description) { content.description?.toHeroExcerpt() }
    val excerpt = parsedDescription ?: entry.supportingText()
    Row(
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + bottomAvoidance),
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
    bottomAvoidance: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(
            start = 18.dp,
            end = 18.dp,
            top = 18.dp,
            bottom = 18.dp + bottomAvoidance,
        ),
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
