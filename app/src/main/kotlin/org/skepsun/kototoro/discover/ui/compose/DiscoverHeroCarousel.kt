package org.skepsun.kototoro.discover.ui.compose

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.image.panoramaBlur
import org.skepsun.kototoro.core.ui.image.rememberPanoramaRequestSize
import org.skepsun.kototoro.core.ui.compose.HeroAutoAdvanceEffect
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.unclippedBoundsInWindow
import org.skepsun.kototoro.core.ui.compose.HeroPagerIndicator
import org.skepsun.kototoro.core.ui.compose.LocalHeroTransitionInProgress
import org.skepsun.kototoro.core.ui.compose.rememberResolvedSourceTitle
import org.skepsun.kototoro.core.ui.compose.panoramaAnimationDurations
import org.skepsun.kototoro.core.ui.compose.panoramaAnimationMotion
import androidx.compose.animation.ExperimentalSharedTransitionApi
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.HeroCoverSnapshotStore
import org.skepsun.kototoro.core.ui.compose.logHeroTransition
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.compose.sharedCoverMemoryCacheKey
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.secondaryTitleText
import org.skepsun.kototoro.list.ui.model.supportingText
import org.skepsun.kototoro.list.ui.model.buildInfoText
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.core.util.ext.mangaExtra

private val DiscoverHeroHeight = 340.dp
private val DiscoverHeroHeightDetached = 262.dp
private val DiscoverHeroHeightLandscape = 220.dp

internal fun discoverHeroHeight(
    isLandscape: Boolean,
    detachedBottomContent: Boolean,
): Dp = when {
    isLandscape -> DiscoverHeroHeightLandscape
    detachedBottomContent -> DiscoverHeroHeightDetached
    else -> DiscoverHeroHeight
}

@Immutable
private data class DiscoverHeroPanoramaPrefs(
    val isEnabled: Boolean,
    val blurPercent: Int,
    val bottomGradientAlphaPercent: Int,
    val animationEnabled: Boolean,
    val animationSpeedPercent: Int,
    val blendHeight: Int,
    val downsampleEnabled: Boolean,
)

@Composable
private fun rememberDiscoverHeroPanoramaPrefs(settings: AppSettings): DiscoverHeroPanoramaPrefs {
    val supportsRealtimeEffects = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val prefs by settings.observeAsState(
        AppSettings.KEY_PANORAMA_ENABLED,
        AppSettings.KEY_PANORAMA_BLUR,
        AppSettings.KEY_BROWSE_PANORAMA_BOTTOM_GRADIENT_ALPHA,
        AppSettings.KEY_PANORAMA_ANIMATION_ENABLED,
        AppSettings.KEY_PANORAMA_ANIMATION_SPEED,
        AppSettings.KEY_BROWSE_PANORAMA_BLEND_HEIGHT,
        AppSettings.KEY_PANORAMA_DOWNSAMPLE,
        AppSettings.KEY_REDUCED_VISUAL_EFFECTS,
    ) {
        DiscoverHeroPanoramaPrefs(
            isEnabled = isPanoramaCoverEnabled,
            blurPercent = panoramaCoverBlur,
            bottomGradientAlphaPercent = browsePanoramaBottomGradientAlpha,
            animationEnabled = supportsRealtimeEffects &&
                isPanoramaCoverAnimationEnabled &&
                !isReducedVisualEffectsEnabled,
            animationSpeedPercent = panoramaAnimationSpeed,
            blendHeight = browsePanoramaBlendHeight,
            downsampleEnabled = isPanoramaDownsampleEnabled,
        )
    }
    return prefs
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun DiscoverHeroCarousel(
    title: String,
    items: List<ContentListModel>,
    activeService: ScrobblerService?,
    availableServices: List<ScrobblerService>,
    onItemClick: (ContentListModel, Rect?, String) -> Unit,
    onSelectService: (ScrobblerService) -> Unit,
    onOpenSchedule: (() -> Unit)? = null,
    topContentInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
    bottomContent: (@Composable () -> Unit)? = null,
    detachedBottomContent: Boolean = false,
    settings: AppSettings? = null,
    sharedElementKeyForItem: (ContentListModel, Int) -> String = { item, _ ->
        contentCoverSharedKey(item.manga.source.name, item.manga.coverUrl.orEmpty())
    },
) {
    if (items.isEmpty()) return

    val heroTransitionInProgress = LocalHeroTransitionInProgress.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val heroHeight = discoverHeroHeight(
        isLandscape = isLandscape,
        detachedBottomContent = detachedBottomContent,
    )
    val context = LocalContext.current
    val resolvedSettings = settings ?: remember(context.applicationContext) { AppSettings(context.applicationContext) }
    val panoramaPrefs = rememberDiscoverHeroPanoramaPrefs(resolvedSettings)
    val useRealtimeBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        panoramaPrefs.blurPercent > 0 &&
        !heroTransitionInProgress
    val isPanoramaEnabled = panoramaPrefs.isEnabled && !heroTransitionInProgress
    val isPanoramaAnimationEnabled = panoramaPrefs.animationEnabled && !heroTransitionInProgress
    val realtimeBlurRadius = ((panoramaPrefs.blurPercent.coerceIn(0, 100) / 100f) * 18f).dp
    val panoramaRequestSize = rememberPanoramaRequestSize(
        minWidthPx = 1280,
        minHeightPx = 960,
        maxWidthPx = 2200,
        maxHeightPx = 1600,
        widthOverscan = 1.34f,
        heightOverscan = 0.64f,
        downsample = panoramaPrefs.downsampleEnabled,
    )
    val heroContentColor = Color.White
    val heroSecondaryContentColor = Color.White.copy(alpha = 0.82f)
    val heroControlContainerColor = Color.Black.copy(alpha = 0.42f)

    val panoramaGradientAlphaFactor = (panoramaPrefs.bottomGradientAlphaPercent / 100f).coerceIn(0f, 1f)
    val animationDurations = panoramaAnimationDurations(panoramaPrefs.animationSpeedPercent)
    val animationMotion = panoramaAnimationMotion()
    val density = LocalDensity.current
    val horizontalPanPx = with(density) { animationMotion.horizontalPan.toPx() }
    val verticalPanPx = with(density) { animationMotion.verticalPan.toPx() }
    val heroBackgroundFadeModifier = Modifier
        .graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
        }
        .drawWithCache {
            val backgroundEndAlpha = resolveDiscoverHeroBackgroundEndAlpha(panoramaGradientAlphaFactor)
            val alphaMask = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.White,
                    0.62f to Color.White,
                    0.86f to Color.White.copy(alpha = (backgroundEndAlpha + 1f) / 2f),
                    1f to Color.White.copy(alpha = backgroundEndAlpha),
                ),
            )
            onDrawWithContent {
                drawContent()
                drawRect(alphaMask, blendMode = BlendMode.DstIn)
            }
        }
    val pagerState = rememberPagerState(pageCount = { items.size })
    val selectedIndex by remember(items, pagerState) {
        derivedStateOf { pagerState.currentPage.coerceIn(0, items.lastIndex) }
    }
    val selectedItem = items[selectedIndex]
    var isServiceMenuExpanded by rememberSaveable { mutableStateOf(false) }

    val infiniteTransition = if (isPanoramaAnimationEnabled) {
        rememberInfiniteTransition(label = "discover_hero_background")
    } else {
        null
    }
    val backgroundScaleState = if (infiniteTransition != null) {
        infiniteTransition.animateFloat(
            initialValue = animationMotion.initialScale,
            targetValue = animationMotion.targetScale,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = animationDurations.scaleMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "discover_hero_background_scale",
        )
    } else {
        null
    }
    val backgroundTranslationXState = if (infiniteTransition != null) {
        infiniteTransition.animateFloat(
            initialValue = -horizontalPanPx,
            targetValue = horizontalPanPx,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = animationDurations.horizontalPanMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "discover_hero_background_translation_x",
        )
    } else {
        null
    }
    val backgroundTranslationYState = if (infiniteTransition != null) {
        infiniteTransition.animateFloat(
            initialValue = -verticalPanPx,
            targetValue = verticalPanPx,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = animationDurations.verticalPanMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "discover_hero_background_translation_y",
        )
    } else {
        null
    }

    HeroAutoAdvanceEffect(
        pagerState = pagerState,
        pageCount = items.size,
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (bottomContent == null || detachedBottomContent) {
                    Modifier.height(heroHeight + topContentInset)
                } else {
                    Modifier
                }
            ),
    ) {
        // 背景层限制在 hero 图片高度内，不延伸到 bottomContent
        val heroImageModifier = Modifier
            .fillMaxWidth()
            .height(heroHeight + topContentInset)
            .clipToBounds()

        if (isPanoramaEnabled) {
            Crossfade(
                targetState = selectedItem.id,
                animationSpec = tween(if (heroTransitionInProgress) 0 else 180),
                label = "discover_hero_background",
                modifier = heroImageModifier.then(heroBackgroundFadeModifier),
            ) { currentId ->
                val backgroundItem = items.firstOrNull { it.id == currentId } ?: selectedItem
                val backgroundRequest = remember(
                    currentId,
                    backgroundItem.coverUrl,
                    context,
                    panoramaPrefs.blurPercent,
                    panoramaRequestSize,
                ) {
                    ImageRequest.Builder(context)
                        .data(backgroundItem.coverUrl)
                        .size(panoramaRequestSize)
                        .apply {
                            if (!useRealtimeBlur) {
                                panoramaBlur(panoramaPrefs.blurPercent)
                            }
                        }
                        .build()
                }
                AsyncImage(
                    model = backgroundRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (useRealtimeBlur) {
                                Modifier.blur(
                                    radius = realtimeBlurRadius,
                                    edgeTreatment = BlurredEdgeTreatment.Unbounded,
                                )
                            } else {
                                Modifier
                            }
                        )
                        .graphicsLayer {
                            val backgroundScale = backgroundScaleState?.value ?: 1f
                            scaleX = backgroundScale
                            scaleY = backgroundScale
                            translationX = backgroundTranslationXState?.value ?: 0f
                            translationY = backgroundTranslationYState?.value ?: 0f
                        }
                        .alpha(0.94f),
                )
            }
        }
        Box(
            modifier = heroImageModifier
                .drawBehind {
                    drawRect(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Black.copy(alpha = 0.38f),
                                0.18f to Color.Black.copy(alpha = 0.22f),
                                0.48f to Color.Black.copy(alpha = 0.18f),
                                0.72f to Color.Black.copy(alpha = 0.08f),
                                1.0f to Color.Transparent,
                            ),
                        ),
                    )
                },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (bottomContent == null || detachedBottomContent) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                    }
                )
                .padding(
                    top = topContentInset + 12.dp,
                    bottom = when {
                        detachedBottomContent -> 20.dp
                        bottomContent == null -> 14.dp
                        else -> 0.dp
                    },
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CompactTopBarHorizontalPadding),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = heroSecondaryContentColor,
                    modifier = Modifier.weight(1f),
                )
                activeService?.let { service ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onOpenSchedule != null) {
                                DiscoverHeroOverlaySurface {
                                    IconButton(
                                        onClick = onOpenSchedule,
                                        modifier = Modifier.size(40.dp),
                                    ) {
                                    Icon(
                                        imageVector = Icons.Filled.DateRange,
                                        contentDescription = stringResource(R.string.open_daily_schedule),
                                        modifier = Modifier.size(18.dp),
                                        tint = heroContentColor,
                                    )
                                }
                            }
                        }
                        Box {
                            DiscoverHeroOverlaySurface {
                                Row(
                                    modifier = Modifier
                                        .heightIn(min = 40.dp)
                                        .widthIn(min = 40.dp)
                                        .clickable(role = Role.Button) {
                                            isServiceMenuExpanded = true
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        painter = rememberSafePainter(service.iconResId),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = heroContentColor,
                                    )
                                    Text(
                                        text = stringResource(service.titleResId),
                                        color = heroContentColor,
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Icon(
                                        imageVector = Icons.Filled.ArrowDropDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = heroContentColor,
                                    )
                                }
                            }
                            GlassDropdownMenu(
                                expanded = isServiceMenuExpanded,
                                onDismissRequest = { isServiceMenuExpanded = false },
                                offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = 4.dp),
                                shape = RoundedCornerShape(28.dp),
                                style = GlassDefaults.subtleStyle(),
                            ) {
                                availableServices.forEach { candidate ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(candidate.titleResId)) },
                                        leadingIcon = {
                                            Icon(
                                                painter = rememberSafePainter(candidate.iconResId),
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        },
                                        onClick = {
                                            isServiceMenuExpanded = false
                                            onSelectService(candidate)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalPager(
                state = pagerState,
                pageSpacing = 0.dp,
                contentPadding = PaddingValues(horizontal = 0.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { page ->
                val item = items[page]
                val sharedElementKey = remember(item.id, page) { sharedElementKeyForItem(item, page) }
                var coverBounds by remember(item.id) { mutableStateOf<Rect?>(null) }
                val posterRequest = remember(item.id, item.coverUrl) {
                    val memoryCacheKey = sharedCoverMemoryCacheKey(
                        sourceName = item.manga.source.name,
                        ownerKey = item.manga.url,
                        url = item.coverUrl,
                    )
                    ImageRequest.Builder(context)
                        .data(item.coverUrl)
                        .memoryCacheKey(memoryCacheKey)
                        .diskCacheKey(memoryCacheKey)
                        .crossfade(false)
                        .apply { mangaExtra(item.manga) }
                        .build()
                }
                val sharedTransitionScope = LocalSharedTransitionScope.current
                val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            logHeroTransition(
                                "discover_click title=${item.title} sharedKey=$sharedElementKey bounds=${coverBounds != null}",
                            )
                            onItemClick(item, coverBounds, sharedElementKey)
                        }
                        .padding(horizontal = CompactTopBarHorizontalPadding, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        AsyncImage(
                            model = posterRequest,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(96.dp)
                                .aspectRatio(0.72f)
                                .onGloballyPositioned { coordinates ->
                                    coverBounds = coordinates.unclippedBoundsInWindow()
                                }
                                .then(
                                    if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                                        with(sharedTransitionScope) {
                                            Modifier.sharedElement(
                                                rememberSharedContentState(
                                                    key = sharedElementKey,
                                                ),
                                                animatedVisibilityScope = animatedVisibilityScope,
                                            )
                                        }
                                    } else Modifier,
                                )
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            onSuccess = { state ->
                                HeroCoverSnapshotStore.put(sharedElementKey, state.result.image)
                            },
                        )
                        item.scoreText?.takeIf { it.isNotBlank() }?.let { scoreText ->
                            DiscoverHeroOverlaySurface(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp),
                            ) {
                                Text(
                                    text = scoreText,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = heroContentColor,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = heroContentColor,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val infoText = remember(item.manga.state, item.manga.chapters?.size, item.manga.tags, item.scoreText, context) {
                            item.buildInfoText(context)
                        }
                        infoText?.takeIf { it.isNotBlank() }?.let { info ->
                            Text(
                                text = info,
                                style = MaterialTheme.typography.bodyMedium,
                                color = heroSecondaryContentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        item.secondaryTitleText()?.takeIf { it.isNotBlank() }?.let { secondaryTitle ->
                            Text(
                                text = secondaryTitle,
                                style = MaterialTheme.typography.bodyLarge,
                                color = heroSecondaryContentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        item.supportingText()?.takeIf { it.isNotBlank() }?.let { supportingText ->
                            Text(
                                text = supportingText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = heroSecondaryContentColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        DiscoverHeroPill(
                            text = rememberResolvedSourceTitle(item.source),
                            contentColor = heroContentColor,
                            containerColor = heroControlContainerColor,
                        )
                    }
                }
            }
            if (!detachedBottomContent) {
                HeroPagerIndicator(
                    pageCount = items.size,
                    currentPage = selectedIndex,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    activeColor = heroContentColor,
                    inactiveColor = heroContentColor.copy(alpha = 0.34f),
                    pageCounter = "${selectedIndex + 1} / ${items.size}",
                )
            }
            if (bottomContent != null) {
                if (!detachedBottomContent) {
                    Spacer(modifier = Modifier.height(14.dp))
                }
                bottomContent()
            }
        }
        if (detachedBottomContent) {
            HeroPagerIndicator(
                pageCount = items.size,
                currentPage = selectedIndex,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = CompactTopBarHorizontalPadding + 8.dp,
                        end = CompactTopBarHorizontalPadding,
                        bottom = 20.dp,
                    ),
                activeColor = heroContentColor,
                inactiveColor = heroContentColor.copy(alpha = 0.34f),
                pageCounter = "${selectedIndex + 1} / ${items.size}",
            )
        }
    }
}

internal fun resolveDiscoverHeroBlendAlpha(alpha: Float, strength: Float): Float {
    return (alpha.coerceIn(0f, 1f) * strength.coerceIn(0f, 1f)).coerceIn(0f, 1f)
}

internal fun resolveDiscoverHeroBackgroundEndAlpha(strength: Float): Float {
    return 1f - strength.coerceIn(0f, 1f)
}

@Composable
private fun DiscoverHeroPill(
    text: String,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
) {
    DiscoverHeroOverlaySurface(
        containerColor = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun DiscoverHeroOverlaySurface(
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Black.copy(alpha = 0.42f),
    contentColor: Color = Color.White,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content,
    )
}
