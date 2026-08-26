package org.skepsun.kototoro.core.ui.widgets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.animateIntSizeAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.NavIndicatorStyle
import org.skepsun.kototoro.core.prefs.NavItem
import org.skepsun.kototoro.core.prefs.limitMainNavigationItems
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.BaseActivityEntryPoint
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.theme.LocalAmoledTheme
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.glass.GlassStyle
import org.skepsun.kototoro.core.ui.glass.backdropSurfaceAlpha
import org.skepsun.kototoro.core.ui.glass.rememberGlassPrefsOrFallback
import org.skepsun.kototoro.core.ui.theme.isDarkTheme
import org.skepsun.kototoro.core.util.FoldableUtils
import dagger.hilt.android.EntryPointAccessors
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.delay

data class BadgeInfo(val number: Int = 0, val isVisible: Boolean = false)

data class BottomNavState(
    val items: List<NavItem> = emptyList(),
    val selectedItemId: Int = 0,
    val showLabels: Boolean = true,
    val badges: Map<Int, BadgeInfo> = emptyMap(),
    val itemVisibility: Map<Int, Boolean> = emptyMap(),
)

@Immutable
private data class BottomNavPrefs(
    val isFloating: Boolean,
    val indicatorStyle: NavIndicatorStyle,
    val isNavFullWidth: Boolean,
    val isNavLabelsAlwaysVisible: Boolean,
    val isSampleBlueNavAccentEnabled: Boolean,
    val navHeight: Int,
    val navFloatingHeight: Int,
    val isNavCapsuleEnabled: Boolean,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KototoroBottomNav(
    state: StateFlow<BottomNavState>,
    onItemSelected: (Int) -> Unit,
    onItemReselected: (Int) -> Unit,
    railHeaderContent: (@Composable () -> Unit)? = null,
    adjacentAction: (@Composable () -> Unit)? = null,
    showContinueReadingButton: Boolean = false,
    onContinueReadingClick: () -> Unit = {},
    continueReadingIconRes: Int = R.drawable.ic_read,
    continueReadingContentDescriptionRes: Int = R.string._continue,
    continueReadingCoverModel: Any? = null,
) {
    val navState by state.collectAsStateWithLifecycle()
    val clickPulses = remember { mutableStateMapOf<Int, Int>() }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val layoutDirection = LocalLayoutDirection.current
    val appSettings = remember {
        EntryPointAccessors.fromApplication<BaseActivityEntryPoint>(context.applicationContext).settings
    }

    val prefs by appSettings.observeAsState(
        AppSettings.KEY_NAV_FLOATING,
        AppSettings.KEY_NAV_INDICATOR_STYLE,
        AppSettings.KEY_NAV_FULL_WIDTH,
        AppSettings.KEY_NAV_LABELS_ALWAYS_VISIBLE,
        AppSettings.KEY_NAV_ACCENT_SAMPLE_BLUE,
        AppSettings.KEY_NAV_HEIGHT,
        AppSettings.KEY_NAV_FLOATING_HEIGHT,
        AppSettings.KEY_NAV_CAPSULE,
    ) {
        BottomNavPrefs(
            isFloating = isNavFloating,
            indicatorStyle = navIndicatorStyle,
            isNavFullWidth = isNavFullWidth,
            isNavLabelsAlwaysVisible = isNavLabelsAlwaysVisible,
            isSampleBlueNavAccentEnabled = isSampleBlueNavAccentEnabled,
            navHeight = navHeight,
            navFloatingHeight = navFloatingHeight,
            isNavCapsuleEnabled = isNavCapsuleEnabled,
        )
    }
    val isFloating = prefs.isFloating
    val isExpressivePillEnabled = prefs.indicatorStyle == NavIndicatorStyle.LABELS_RIGHT
    val isCapsuleEnabled = prefs.isNavCapsuleEnabled
    val navHeight = prefs.navHeight
    val navFloatingHeight = prefs.navFloatingHeight
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    // Full-width floating navigation bar: the AndroidLiquidGlass LiquidBottomTabs
    // layout spans the full screen width with evenly weighted tabs. It is now an
    // independent toggle (default off) that combines with either label
    // arrangement instead of being the implicit look of a "full-width capsule
    // indicator" flag.
    val useFullWidthIndicator = prefs.isNavFullWidth
    // Optional sample-blue accent (0xFF0088FF / 0xFF0091FF) for the selected
    // tab content, mirroring the LiquidBottomTabs sample.
    val sampleAccent = if (isIosStyle && prefs.isSampleBlueNavAccentEnabled) {
        if (MaterialTheme.colorScheme.isDarkTheme()) Color(0xFF0091FF) else Color(0xFF0088FF)
    } else {
        null
    }
    // Persistent labels: with the labels-right (pill) arrangement off this
    // reproduces the sample's always-visible tab labels.
    val labelsAlwaysVisible = prefs.isNavLabelsAlwaysVisible
    val tabletUiMode by appSettings.observeAsState(AppSettings.KEY_TABLET_UI_MODE) { tabletUiMode }

    val activeItems = navState.items
        .filter { navState.itemVisibility[it.id] != false }
        .limitMainNavigationItems()
    val showSelectedLabels = navState.showLabels
    val useNavigationRail = remember(configuration.orientation, configuration.screenWidthDp, tabletUiMode) {
        FoldableUtils.shouldUseTabletLayout(context, appSettings, configuration)
    }
    val systemBarsPadding = WindowInsets.systemBarsIgnoringVisibility.asPaddingValues()
    val statusBarTopPadding = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    val railStartInset = systemBarsPadding.calculateStartPadding(layoutDirection)
    val railEndInset = systemBarsPadding.calculateEndPadding(layoutDirection)
    val railBottomInset = systemBarsPadding.calculateBottomPadding()

    val targetAlpha = 0.84f

    val floatingVerticalPadding by androidx.compose.animation.core.animateDpAsState(
        if (isFloating && !useNavigationRail) 16.dp else 0.dp,
    )
    val navBarModifier = Modifier
        .then(
            if (useNavigationRail) {
                Modifier.fillMaxHeight()
            } else {
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (isFloating) 12.dp else 0.dp,
                        top = floatingVerticalPadding,
                        end = if (isFloating) 12.dp else 0.dp,
                        bottom = floatingVerticalPadding,
                    )
                    .run {
                        if (isFloating) {
                            windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                        } else {
                            this
                        }
                    }
            },
        )
    val floatingNavModifier = Modifier
        .fillMaxWidth()
        .padding(vertical = floatingVerticalPadding)
        .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)

    val currentExplicitHeight by androidx.compose.animation.core.animateDpAsState(
        if (isFloating && !useNavigationRail) (navFloatingHeight + 4).dp else navHeight.dp,
    )
    val nonFloatingContentHorizontalPadding = 6.dp
    val nonFloatingTopPadding = 4.dp
    val railWidth = navHeight.dp.coerceIn(80.dp, 160.dp)

    val navContainerStyle = if (isFloating) {
        GlassDefaults.bottomBarChromeStyle().copy(
            containerAlpha = targetAlpha,
            borderAlpha = 0.10f,
        )
    } else {
        GlassDefaults.bottomBarChromeStyle().copy(
            containerAlpha = (targetAlpha - 0.06f).coerceAtLeast(0.70f),
            borderAlpha = 0f,
        )
    }
    if (useNavigationRail) {
        Surface(
            modifier = navBarModifier,
            color = NavigationRailDefaults.ContainerColor,
            contentColor = contentColorFor(NavigationRailDefaults.ContainerColor),
            tonalElevation = 3.dp,
        ) {
            NavigationRail(
                containerColor = Color.Transparent,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(railWidth)
                    .padding(
                        start = railStartInset,
                        end = railEndInset,
                        top = statusBarTopPadding,
                        bottom = railBottomInset,
                    ),
                windowInsets = WindowInsets(0),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (railHeaderContent != null) {
                        item {
                            railHeaderContent()
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                    if (showContinueReadingButton) {
                        item {
                            ContinueReadingRailButton(
                                onClick = onContinueReadingClick,
                                iconRes = continueReadingIconRes,
                                contentDescriptionRes = continueReadingContentDescriptionRes,
                                coverModel = continueReadingCoverModel,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                    items(
                        items = activeItems,
                        key = { it.id },
                    ) { item ->
                        val isSelected = navState.selectedItemId == item.id
                        val badge = navState.badges[item.id]

                        NavigationRailItem(
                            selected = isSelected,
                            onClick = {
                                if (isSelected) {
                                    onItemReselected(item.id)
                                } else {
                                    clickPulses[item.id] = (clickPulses[item.id] ?: 0) + 1
                                    onItemSelected(item.id)
                                }
                            },
                            icon = {
                                PremiumNavigationIcon(
                                    itemId = item.id,
                                    isSelected = isSelected,
                                    clickPulse = clickPulses[item.id] ?: 0,
                                    badge = badge,
                                    contentDescription = stringResource(item.title),
                                    selectedTint = sampleAccent,
                                )
                            },
                            label = { Text(stringResource(item.title)) },
                            alwaysShowLabel = showSelectedLabels,
                            colors = NavigationRailItemDefaults.colors(
                                indicatorColor = Color.Transparent,
                                selectedIconColor = sampleAccent ?: if (isIosStyle) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        }
    } else if (isFloating) {
        BoxWithConstraints(
            modifier = floatingNavModifier,
            contentAlignment = Alignment.Center,
        ) {
            val layoutSpec = remember(
                maxWidth,
                activeItems.size,
                adjacentAction != null,
                showSelectedLabels,
                isExpressivePillEnabled,
                navFloatingHeight,
            ) {
                resolveBottomNavLayout(
                    availableWidth = maxWidth,
                    itemCount = activeItems.size,
                    fabWidth = resolveNavBarHeight(
                        isFloating = true,
                        navHeight = navHeight,
                        navFloatingHeight = navFloatingHeight,
                    ).takeIf { adjacentAction != null },
                    showLabels = showSelectedLabels,
                    isExpressivePill = isExpressivePillEnabled,
                )
            }
            if (useFullWidthIndicator) {
                // LiquidBottomTabs sample layout: a full-width capsule bar whose
                // tabs are split evenly (weight 1f) with the content inset by 4dp
                // on every side, and the selected tab is highlighted by a 56dp
                // full-tab pill. Bar height follows the floating-height slider so
                // 64dp (the sample look) stays reachable via the nav settings.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = layoutSpec.outerHorizontalPadding),
                    horizontalArrangement = Arrangement.spacedBy(layoutSpec.fabGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FullWidthFloatingBottomNavRow(
                        items = activeItems,
                        selectedItemId = navState.selectedItemId,
                        badges = navState.badges,
                        clickPulses = clickPulses,
                        showSelectedLabels = showSelectedLabels,
                        labelsAlwaysVisible = labelsAlwaysVisible,
                        labelsToTheRight = isExpressivePillEnabled,
                        accentOverride = sampleAccent,
                        capsuleEnabled = isCapsuleEnabled,
                        onItemSelected = onItemSelected,
                        onItemReselected = onItemReselected,
                        barStyle = navContainerStyle,
                        barShape = Capsule(),
                        modifier = Modifier
                            .weight(1f)
                            .height((navFloatingHeight + 4).dp),
                    )
                    adjacentAction?.invoke()
                }
            } else {
                Row(
                    modifier = Modifier
                        .wrapContentWidth()
                        .padding(horizontal = layoutSpec.outerHorizontalPadding),
                    horizontalArrangement = Arrangement.spacedBy(layoutSpec.fabGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FloatingBottomNavRow(
                        items = activeItems,
                        selectedItemId = navState.selectedItemId,
                        badges = navState.badges,
                        clickPulses = clickPulses,
                        showSelectedLabels = layoutSpec.showLabels,
                        useExpressivePill = isExpressivePillEnabled,
                        labelsAlwaysVisible = labelsAlwaysVisible,
                        capsuleEnabled = isCapsuleEnabled,
                        itemSpacing = layoutSpec.itemSpacing,
                        labelScale = layoutSpec.labelScale,
                        labelMaxWidth = layoutSpec.labelMaxWidth,
                        accentOverride = sampleAccent,
                        onItemSelected = onItemSelected,
                        onItemReselected = onItemReselected,
                        barStyle = navContainerStyle,
                        barShape = RoundedRectangle(28.dp),
                        modifier = Modifier
                            .wrapContentWidth()
                            .height(currentExplicitHeight)
                            .padding(horizontal = layoutSpec.horizontalPadding),
                    )
                    adjacentAction?.invoke()
                }
            }
        }
    } else {
        Row(
            modifier = navBarModifier
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .then(if (adjacentAction != null) Modifier.padding(end = 12.dp) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MainNavBottomContainer(
                modifier = Modifier.weight(1f),
                style = navContainerStyle,
                shape = RoundedRectangle(0.dp),
            ) {
                NavigationBar(
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(currentExplicitHeight)
                        .padding(
                            start = nonFloatingContentHorizontalPadding,
                            end = nonFloatingContentHorizontalPadding,
                            top = nonFloatingTopPadding,
                        ),
                    windowInsets = WindowInsets(0),
                ) {
                    activeItems.forEach { item ->
                        val isSelected = navState.selectedItemId == item.id
                        val badge = navState.badges[item.id]

                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                if (isSelected) {
                                    onItemReselected(item.id)
                                } else {
                                    clickPulses[item.id] = (clickPulses[item.id] ?: 0) + 1
                                    onItemSelected(item.id)
                                }
                            },
                            icon = {
                                PremiumNavigationIcon(
                                    itemId = item.id,
                                    isSelected = isSelected,
                                    clickPulse = clickPulses[item.id] ?: 0,
                                    badge = badge,
                                    contentDescription = stringResource(item.title),
                                    selectedTint = sampleAccent,
                                )
                            },
                            label = { Text(stringResource(item.title)) },
                            alwaysShowLabel = showSelectedLabels,
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = Color.Transparent,
                                selectedIconColor = sampleAccent ?: if (isIosStyle) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
            adjacentAction?.invoke()
        }
    }
}

@Composable
private fun MainNavBottomContainer(
    modifier: Modifier,
    style: GlassStyle,
    shape: Shape,
    exportedBackdrop: LayerBackdrop? = null,
    lensEnabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        style = style,
        shape = shape,
        componentRole = GlassComponentRole.BottomBar,
        exportedBackdrop = exportedBackdrop,
        lensEnabled = lensEnabled,
        content = content,
    )
}

internal data class BottomNavPillEffectSpec(
    val progress: Float,
    val idleMaterialFraction: Float,
    val lensHeightDp: Float,
    val lensAmountDp: Float,
    val highlightAlpha: Float,
    val innerShadowAlpha: Float,
)

internal fun resolveBottomNavPillEffect(progress: Float): BottomNavPillEffectSpec {
    val p = progress.coerceIn(0f, 1f)
    return BottomNavPillEffectSpec(
        progress = p,
        idleMaterialFraction = 1f - p,
        lensHeightDp = 10f * p,
        lensAmountDp = 14f * p,
        highlightAlpha = p,
        innerShadowAlpha = 0.15f * p,
    )
}

internal fun resolveBottomNavMagnifyScale(): Float = 78f / 56f

/**
 * Actual on-screen height of the navigation bar shell: the floating bar is the
 * floating-height setting plus the 4dp glass inset, the docked bar is exactly
 * the nav-height setting. The continue-reading FAB next to the bar mirrors the
 * same value so both stay visually consistent when the height slider changes.
 */
internal fun resolveNavBarHeight(
    isFloating: Boolean,
    navHeight: Int,
    navFloatingHeight: Int,
): Dp = if (isFloating) (navFloatingHeight + 4).dp else navHeight.dp

/**
 * Resting height of the full-width tab pill. The sample design is a 56dp pill
 * inside a 64dp bar (the 4dp content inset on each side leaves a 56dp tab).
 * The floating bar height is user-configurable down to 48dp (a 52dp bar whose
 * content area is only 44dp), so the pill fills the tab's measured content
 * height up to the 56dp sample cap instead of overflowing the nav shell. While
 * the pill is in motion (press/fly-to-tab) it may still cross the bar — only
 * the resting capsule is clamped.
 */
internal fun resolveBottomNavFullWidthPillHeight(
    tabContentHeightPx: Int,
    idealPillHeightPx: Int,
): Int = if (tabContentHeightPx <= 0) {
    idealPillHeightPx
} else {
    tabContentHeightPx.coerceAtMost(idealPillHeightPx)
}

internal fun resolveBottomNavDragIndicatorX(
    pointerX: Float?,
    indicatorWidth: Int,
    containerWidth: Int,
    snappedOffsetX: Int,
): Int {
    if (pointerX == null || indicatorWidth <= 0 || containerWidth <= 0) return snappedOffsetX
    val maxOffset = (containerWidth - indicatorWidth).coerceAtLeast(0)
    return (pointerX - indicatorWidth / 2f).roundToInt().coerceIn(0, maxOffset)
}

internal fun interpolateBottomNavSettleX(startX: Float, targetX: Float, progress: Float): Float =
    startX + (targetX - startX) * progress.coerceIn(0f, 1f)

private data class BottomNavSettleMotion(
    val startCenterX: Float,
    val targetCenterX: Float,
)


@Composable
private fun Modifier.mainNavBackdrop(
    shape: Shape,
    enabled: Boolean,
    backdrop: com.kyant.backdrop.Backdrop?,
    selectionTint: Color,
    pressProgress: () -> Float = { 0f },
    magnifyScale: Float = 1f,
): Modifier {
    return then(if (enabled && backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                val spec = resolveBottomNavPillEffect(pressProgress())
                // The combined backdrop already contains the single, fully
                // rendered navigation glass shell. BiliPai does not blur that
                // material a second time inside each indicator; only the
                // moving/magnified state adds a lens.
                if (spec.progress > 0.001f) {
                    lens(
                        refractionHeight = spec.lensHeightDp.dp.toPx(),
                        refractionAmount = spec.lensAmountDp.dp.toPx(),
                        depthEffect = true,
                        chromaticAberration = true,
                    )
                }
            },
            highlight = {
                val spec = resolveBottomNavPillEffect(pressProgress())
                if (spec.highlightAlpha > 0f) Highlight(width = 0.5.dp, alpha = spec.highlightAlpha) else null
            },
            // Kyant enables Shadow.Default unless it is explicitly replaced.
            // BiliPai's indicator has no independent idle shadow: the whole
            // navigation shell is one glass surface.
            shadow = { null },
            innerShadow = {
                val spec = resolveBottomNavPillEffect(pressProgress())
                if (spec.innerShadowAlpha > 0f) {
                    InnerShadow(
                        radius = 8.dp * spec.progress,
                        color = Color.Black.copy(alpha = 0.15f),
                        alpha = spec.innerShadowAlpha,
                    )
                } else {
                    null
                }
            },
            // BiliPai scales the Backdrop render layer itself. Scaling the
            // outer Compose node leaves the sampled layer at its resting
            // bounds, which makes the out-of-bar portion stale or clipped.
            layerBlock = {
                val progress = resolveBottomNavPillEffect(pressProgress()).progress
                val scale = 1f + (magnifyScale - 1f) * progress
                scaleX = scale
                scaleY = scale
            },
            onDrawSurface = {
                val spec = resolveBottomNavPillEffect(pressProgress())
                if (spec.idleMaterialFraction > 0f) {
                    drawRect(selectionTint, alpha = spec.idleMaterialFraction)
                }
            },
        )
    } else {
        Modifier.background(selectionTint, shape)
    })
}

@Composable
private fun ContinueReadingRailButton(
    onClick: () -> Unit,
    iconRes: Int,
    contentDescriptionRes: Int,
    coverModel: Any?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(52.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = shape,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (coverModel != null) {
                Image(
                    painter = rememberAsyncImagePainter(coverModel),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.38f)),
                )
            }
            Icon(
                painter = painterResource(iconRes),
                contentDescription = stringResource(contentDescriptionRes),
                tint = if (coverModel != null) Color.White else LocalContentColor.current,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun FloatingBottomNavRow(
    items: List<NavItem>,
    selectedItemId: Int,
    badges: Map<Int, BadgeInfo>,
    clickPulses: MutableMap<Int, Int>,
    showSelectedLabels: Boolean,
    useExpressivePill: Boolean,
    labelsAlwaysVisible: Boolean,
    capsuleEnabled: Boolean,
    accentOverride: Color? = null,
    itemSpacing: Dp,
    labelScale: Float,
    labelMaxWidth: Dp?,
    onItemSelected: (Int) -> Unit,
    onItemReselected: (Int) -> Unit,
    barStyle: GlassStyle,
    barShape: Shape,
    modifier: Modifier = Modifier,
) {
    val backdrop = LocalLiquidGlassBackdrop.current
    val navigationShellBackdrop = rememberLayerBackdrop()
    val navigationContentBackdrop = rememberLayerBackdrop()
    val indicatorBackdrop = if (backdrop != null) {
        // BiliPai/InstallerX ordering: page, tinted dock material, then dock
        // foreground. The moving indicator samples all three, while the
        // shell's final hairline/highlight stays outside the capture.
        rememberCombinedBackdrop(backdrop, navigationShellBackdrop, navigationContentBackdrop)
    } else {
        null
    }
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    // The shared moving capsule is the indicator for the labels-below
    // arrangement (labels-below + non-full-width previously had no capsule at
    // all) and for the iOS expressive pill. Non-iOS expressive keeps its
    // per-item static capsule instead. Disabling the nav capsule switch hides
    // every capsule background and leaves only the accent content color.
    val useSharedLiquidGlassPill = capsuleEnabled && (!useExpressivePill || isIosStyle)
    val pillSelectionTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    // pointerInput blocks only restart when their keys change, so any parameter
    // they capture (selectedItemId, onItemSelected) goes stale across
    // recompositions. rememberUpdatedState keeps the drag handlers reading the
    // live selection and callback.
    val currentSelectedItemId by rememberUpdatedState(selectedItemId)
    val currentOnItemSelected by rememberUpdatedState(onItemSelected)
    val itemBounds = remember { mutableStateMapOf<Int, NavItemBounds>() }
    var containerPositionInRoot by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var dragPreviewItemId by remember { mutableStateOf<Int?>(null) }
    var dragIndicatorCenterX by remember { mutableStateOf<Float?>(null) }
    var dragIndicatorWidthPx by remember { mutableStateOf<Int?>(null) }
    var settleMotion by remember { mutableStateOf<BottomNavSettleMotion?>(null) }
    val settleProgress by animateFloatAsState(
        targetValue = if (settleMotion == null) 0f else 1f,
        animationSpec = spring(
            dampingRatio = 0.78f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "bottomNavPillSettleProgress",
    )
    LaunchedEffect(settleMotion, settleProgress) {
        val motion = settleMotion ?: return@LaunchedEffect
        if (settleProgress >= 0.999f) {
            dragPreviewItemId = null
            dragIndicatorCenterX = null
            delay(180)
            if (settleMotion == motion) {
                settleMotion = null
                dragIndicatorWidthPx = null
            }
        }
    }
    val pillPressProgress = remember { Animatable(0f) }
    LaunchedEffect(dragPreviewItemId) {
        pillPressProgress.animateTo(
            targetValue = if (dragPreviewItemId != null) 1f else 0f,
            animationSpec = tween(if (dragPreviewItemId != null) 90 else 160),
        )
    }
    val pillSelectPulse = remember { Animatable(0f) }
    var hasInitialSelection by remember { mutableStateOf(false) }
    var justReleasedFromDrag by remember { mutableStateOf(false) }
    LaunchedEffect(selectedItemId) {
        if (!hasInitialSelection) {
            hasInitialSelection = true
            return@LaunchedEffect
        }
        if (justReleasedFromDrag) {
            justReleasedFromDrag = false
            return@LaunchedEffect
        }
        pillSelectPulse.snapTo(0f)
        pillSelectPulse.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 400
                0f at 0
                1f at 200 using FastOutSlowInEasing
                0f at 400 using FastOutSlowInEasing
            },
        )
    }
    val displayedSelectedItemId = dragPreviewItemId ?: selectedItemId
    val selectedBounds = itemBounds[displayedSelectedItemId]
    val density = LocalDensity.current
    val selectedContentColor = accentOverride ?: if (isIosStyle) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    val pillHeightPx = with(density) { 40.dp.roundToPx() }
    // Use the same relative magnification as the 56dp BiliPai indicator: the
    // compact 40dp expressive pill grows to about 56dp rather than nearly
    // doubling to 78dp. The growth happens while the pill is in motion and may
    // cross the bar edge; the resting capsule stays inside the shell.
    val pillMagnifyScale = resolveBottomNavMagnifyScale()
    // Center the capsule on the selected item (not anchored to its top), so it
    // stays symmetric for any bar height.
    val targetIndicatorWidth = dragIndicatorWidthPx ?: selectedBounds?.size?.width ?: 0
    val snappedIndicatorOffset = selectedBounds?.let {
        IntOffset(
            it.offset.x,
            it.offset.y + (it.size.height - pillHeightPx) / 2,
        )
    } ?: IntOffset.Zero
    val targetIndicatorOffset = snappedIndicatorOffset.copy(
        x = resolveBottomNavDragIndicatorX(
            pointerX = settleMotion?.let {
                interpolateBottomNavSettleX(it.startCenterX, it.targetCenterX, settleProgress)
            } ?: dragIndicatorCenterX,
            indicatorWidth = targetIndicatorWidth,
            containerWidth = containerSize.width,
            snappedOffsetX = snappedIndicatorOffset.x,
        ),
    )
    val targetIndicatorSize = if (targetIndicatorWidth > 0) {
        IntSize(targetIndicatorWidth, with(density) { 40.dp.roundToPx() })
    } else {
        IntSize.Zero
    }
    val animatedIndicatorOffset by animateIntOffsetAsState(
        targetValue = targetIndicatorOffset,
        label = "bottomNavGlassPillOffset",
    )
    val animatedIndicatorSize by animateIntSizeAsState(
        targetValue = targetIndicatorSize,
        label = "bottomNavGlassPillSize",
    )
    val isIndicatorDirectlyPositioned = dragIndicatorCenterX != null || settleMotion != null
    val indicatorOffset = if (isIndicatorDirectlyPositioned) targetIndicatorOffset else animatedIndicatorOffset
    val indicatorSize = if (isIndicatorDirectlyPositioned) targetIndicatorSize else animatedIndicatorSize
    val pillEffectProgress = maxOf(pillSelectPulse.value, pillPressProgress.value)
    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                containerPositionInRoot = coordinates.positionInRoot()
                containerSize = coordinates.size
            },
        contentAlignment = Alignment.Center,
    ) {
        MainNavBottomContainer(
            modifier = Modifier.matchParentSize(),
            style = barStyle,
            shape = barShape,
            exportedBackdrop = navigationShellBackdrop,
            // Refraction follows the BottomBar scope like every other glass
            // role. An older hard-disable predated the lens safety clamp and
            // the role-delta tuner; the clamp now bounds the lens on this wide
            // capsule, and the moving indicator keeps its own selection
            // refraction layered on top. Tune per-role in the Glass tuner.
        ) {}
        if (useSharedLiquidGlassPill && targetIndicatorSize != IntSize.Zero) {
            val indicatorShape = Capsule()
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { indicatorOffset }
                    .size(
                        width = with(density) { indicatorSize.width.toDp() },
                        height = with(density) { indicatorSize.height.toDp() },
                    )
                    .graphicsLayer {
                        clip = false
                    }
                    .zIndex(2f)
                    .mainNavBackdrop(
                        shape = indicatorShape,
                        enabled = backdrop != null,
                        backdrop = indicatorBackdrop,
                        selectionTint = pillSelectionTint,
                        pressProgress = { pillEffectProgress },
                        magnifyScale = pillMagnifyScale,
                    ),
            )
        }
        Row(
            modifier = Modifier.layerBackdrop(navigationContentBackdrop),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        items.forEach { item ->
            val isSelected = displayedSelectedItemId == item.id
            val interactionSource = remember(item.id) { MutableInteractionSource() }
            val iconOffsetY by androidx.compose.animation.core.animateDpAsState(
                targetValue = if (isSelected && !useExpressivePill) (-3).dp else 0.dp,
            )
            val contentColor = if (isSelected) {
                selectedContentColor
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            val useLiquidGlassPill = isSelected && capsuleEnabled && useExpressivePill && isIosStyle
            val selectedContainerColor = if (isSelected && capsuleEnabled && useExpressivePill && !isIosStyle) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                Color.Transparent
            }
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                val itemModifier = Modifier
                    .widthIn(min = 48.dp)
                    .onGloballyPositioned { coordinates ->
                        if (useSharedLiquidGlassPill) {
                            val position = coordinates.positionInRoot() - containerPositionInRoot
                            itemBounds[item.id] = NavItemBounds(
                                itemId = item.id,
                                offset = IntOffset(position.x.roundToInt(), position.y.roundToInt()),
                                size = IntSize(coordinates.size.width, coordinates.size.height),
                            )
                        }
                    }
                    .pointerInput(useSharedLiquidGlassPill, item.id) {
                        if (useSharedLiquidGlassPill) {
                            var pointerX = 0f
                            detectDragGestures(
                                onDragStart = { startOffset ->
                                    settleMotion = null
                                    val itemOffset = itemBounds[item.id]?.offset?.x?.toFloat() ?: 0f
                                    // detectDragGestures reports startOffset in the item's
                                    // local coordinates; convert it to the shared row space
                                    // before comparing against sibling bounds.
                                    pointerX = itemOffset + startOffset.x
                                    dragIndicatorCenterX = pointerX
                                    dragIndicatorWidthPx = itemBounds[item.id]?.size?.width
                                    dragPreviewItemId = item.id
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    pointerX += dragAmount.x
                                    dragIndicatorCenterX = pointerX
                                    val targetItemId = itemBounds
                                        .values
                                        .firstOrNull { it.containsHorizontal(pointerX) }
                                        ?.itemId
                                    if (targetItemId != null) {
                                        dragPreviewItemId = targetItemId
                                    }
                                },
                                onDragCancel = {
                                    settleMotion = null
                                    dragIndicatorCenterX = null
                                    dragIndicatorWidthPx = null
                                    dragPreviewItemId = null
                                },
                                onDragEnd = {
                                    val targetItemId = dragPreviewItemId
                                    val targetBounds = targetItemId?.let(itemBounds::get)
                                    if (targetItemId != null && targetBounds != null) {
                                        settleMotion = BottomNavSettleMotion(
                                            startCenterX = pointerX,
                                            targetCenterX = targetBounds.offset.x + targetBounds.size.width / 2f,
                                        )
                                    }
                                    if (targetItemId != null && targetItemId != currentSelectedItemId) {
                                        justReleasedFromDrag = true
                                        currentOnItemSelected(targetItemId)
                                    }
                                    if (targetBounds == null) {
                                        dragIndicatorCenterX = null
                                        dragIndicatorWidthPx = null
                                        dragPreviewItemId = null
                                    }
                                },
                            )
                        }
                    }
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = {
                            if (isSelected) {
                                onItemReselected(item.id)
                            } else {
                                clickPulses[item.id] = (clickPulses[item.id] ?: 0) + 1
                                onItemSelected(item.id)
                            }
                        },
                    )
                if (useExpressivePill) {
                    val hasNumberBadge = badges[item.id]?.let { it.isVisible && it.number > 0 } == true
                    Box(
                        modifier = itemModifier
                            .height(48.dp)
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier
                                .height(40.dp)
                                .widthIn(min = 40.dp)
                                .then(
                                    if (useSharedLiquidGlassPill) {
                                        Modifier
                                    } else {
                                        Modifier
                                            .background(selectedContainerColor, Capsule())
                                            .then(
                                                if (useLiquidGlassPill) {
                                                    Modifier.mainNavBackdrop(
                                                        shape = Capsule(),
                                                        enabled = backdrop != null,
                                                        backdrop = backdrop,
                                                        selectionTint = pillSelectionTint,
                                                    )
                                                } else {
                                                    Modifier
                                                },
                                            )
                                            .then(
                                                if (useLiquidGlassPill) {
                                                    Modifier.border(
                                                        1.dp,
                                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f),
                                                        Capsule(),
                                                    )
                                                } else {
                                                    Modifier
                                                },
                                            )
                                    },
                                )
                                .padding(horizontal = if (isSelected) 8.dp else 0.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PremiumNavigationIcon(
                                itemId = item.id,
                                isSelected = isSelected,
                                clickPulse = clickPulses[item.id] ?: 0,
                                badge = badges[item.id],
                                contentDescription = stringResource(item.title),
                            )
                            AnimatedVisibility(
                                visible = isSelected && showSelectedLabels,
                                enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                                exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
                            ) {
                                Text(
                                    text = stringResource(item.title),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontSize = MaterialTheme.typography.labelMedium.fontSize * labelScale,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .padding(start = if (hasNumberBadge) 20.dp else 8.dp)
                                        .then(
                                            labelMaxWidth?.let { Modifier.widthIn(max = it) } ?: Modifier,
                                        ),
                                )
                            }
                        }
                    }
                } else {
                    Column(
                    modifier = Modifier
                        .then(itemModifier.fillMaxHeight())
                        .padding(horizontal = 1.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(modifier = Modifier.offset(y = iconOffsetY)) {
                        PremiumNavigationIcon(
                            itemId = item.id,
                            isSelected = isSelected,
                            clickPulse = clickPulses[item.id] ?: 0,
                            badge = badges[item.id],
                            contentDescription = stringResource(item.title),
                        )
                    }
                    if ((isSelected || labelsAlwaysVisible) && showSelectedLabels) {
                        Spacer(modifier = Modifier.height(0.dp))
                        Text(
                            text = stringResource(item.title),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
                }
            }
        }
    }
}
}

/**
 * Full-width floating navigation row (the AndroidLiquidGlass LiquidBottomTabs
 * layout): evenly weighted tabs with 4dp insets and a selected tab wrapped by
 * a 56dp full-tab capsule pill whose glass follows the bottom navigation shell
 * material while idle. With [labelsToTheRight] each tab lays its label beside
 * the icon (the pill arrangement) instead of below it.
 * The selected content may optionally take the sample's blue accent.
 */
@Composable
private fun FullWidthFloatingBottomNavRow(
    items: List<NavItem>,
    selectedItemId: Int,
    badges: Map<Int, BadgeInfo>,
    clickPulses: MutableMap<Int, Int>,
    showSelectedLabels: Boolean,
    labelsAlwaysVisible: Boolean,
    labelsToTheRight: Boolean = false,
    accentOverride: Color? = null,
    capsuleEnabled: Boolean,
    onItemSelected: (Int) -> Unit,
    onItemReselected: (Int) -> Unit,
    barStyle: GlassStyle,
    barShape: Shape,
    modifier: Modifier = Modifier,
) {
    val backdrop = LocalLiquidGlassBackdrop.current
    val navigationShellBackdrop = rememberLayerBackdrop()
    val navigationContentBackdrop = rememberLayerBackdrop()
    val indicatorBackdrop = if (backdrop != null) {
        rememberCombinedBackdrop(backdrop, navigationShellBackdrop, navigationContentBackdrop)
    } else {
        null
    }
    val accentColor = accentOverride ?: MaterialTheme.colorScheme.primary
    val pillSelectionTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    // pointerInput blocks only restart when their keys change, so any parameter
    // they capture (selectedItemId, onItemSelected) goes stale across
    // recompositions. rememberUpdatedState keeps the drag handlers reading the
    // live selection and callback.
    val currentSelectedItemId by rememberUpdatedState(selectedItemId)
    val currentOnItemSelected by rememberUpdatedState(onItemSelected)
    val itemBounds = remember { mutableStateMapOf<Int, NavItemBounds>() }
    var containerPositionInRoot by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var dragPreviewItemId by remember { mutableStateOf<Int?>(null) }
    var dragIndicatorCenterX by remember { mutableStateOf<Float?>(null) }
    var dragIndicatorWidthPx by remember { mutableStateOf<Int?>(null) }
    var settleMotion by remember { mutableStateOf<BottomNavSettleMotion?>(null) }
    val settleProgress by animateFloatAsState(
        targetValue = if (settleMotion == null) 0f else 1f,
        animationSpec = spring(
            dampingRatio = 0.78f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "bottomNavFullWidthPillSettleProgress",
    )
    LaunchedEffect(settleMotion, settleProgress) {
        val motion = settleMotion ?: return@LaunchedEffect
        if (settleProgress >= 0.999f) {
            dragPreviewItemId = null
            dragIndicatorCenterX = null
            delay(180)
            if (settleMotion == motion) {
                settleMotion = null
                dragIndicatorWidthPx = null
            }
        }
    }
    val pillPressProgress = remember { Animatable(0f) }
    LaunchedEffect(dragPreviewItemId) {
        pillPressProgress.animateTo(
            targetValue = if (dragPreviewItemId != null) 1f else 0f,
            animationSpec = tween(if (dragPreviewItemId != null) 90 else 160),
        )
    }
    // Selection "magnet magnify" mirroring the LiquidBottomTabs sample: the pill
    // swells while flying to the new tab, then settles on arrival. The transient
    // growth is allowed to cross the bar edge; only the resting capsule below is
    // kept inside the nav shell.
    val pillSelectPulse = remember { Animatable(0f) }
    var hasInitialSelection by remember { mutableStateOf(false) }
    var justReleasedFromDrag by remember { mutableStateOf(false) }
    LaunchedEffect(selectedItemId) {
        if (!hasInitialSelection) {
            hasInitialSelection = true
            return@LaunchedEffect
        }
        if (justReleasedFromDrag) {
            justReleasedFromDrag = false
            return@LaunchedEffect
        }
        pillSelectPulse.snapTo(0f)
        pillSelectPulse.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 400
                0f at 0
                1f at 200 using FastOutSlowInEasing
                0f at 400 using FastOutSlowInEasing
            },
        )
    }
    val displayedSelectedItemId = dragPreviewItemId ?: selectedItemId
    val selectedBounds = itemBounds[displayedSelectedItemId]
    val density = LocalDensity.current
    // The pill is 56dp tall at the sample's 64dp bar (64dp minus the 4dp inset
    // on each side) and spans the selected tab's full content width. Smaller
    // floating bars (the height slider goes down to 48dp -> a 52dp bar) shrink
    // the resting pill to the tab's measured content height so the capsule
    // never overflows the navigation shell edges.
    val restingPillHeightPx = with(density) { 56.dp.roundToPx() }
    val pillHeightPx = resolveBottomNavFullWidthPillHeight(
        tabContentHeightPx = selectedBounds?.size?.height ?: 0,
        idealPillHeightPx = restingPillHeightPx,
    )
    // Motion magnification keeps the full BiliPai amplitude (it may cross the
    // bar while flying between tabs); the resting capsule stays in bounds.
    val pillMagnifyScale = resolveBottomNavMagnifyScale()
    val targetIndicatorWidth = dragIndicatorWidthPx ?: selectedBounds?.size?.width ?: 0
    val snappedIndicatorOffset = selectedBounds?.let {
        IntOffset(
            it.offset.x,
            it.offset.y + (it.size.height - pillHeightPx) / 2,
        )
    } ?: IntOffset.Zero
    val targetIndicatorOffset = snappedIndicatorOffset.copy(
        x = resolveBottomNavDragIndicatorX(
            pointerX = settleMotion?.let {
                interpolateBottomNavSettleX(it.startCenterX, it.targetCenterX, settleProgress)
            } ?: dragIndicatorCenterX,
            indicatorWidth = targetIndicatorWidth,
            containerWidth = containerSize.width,
            snappedOffsetX = snappedIndicatorOffset.x,
        ),
    )
    val targetIndicatorSize = if (targetIndicatorWidth > 0) {
        IntSize(targetIndicatorWidth, pillHeightPx)
    } else {
        IntSize.Zero
    }
    val animatedIndicatorOffset by animateIntOffsetAsState(
        targetValue = targetIndicatorOffset,
        label = "bottomNavSamplePillOffset",
    )
    val animatedIndicatorSize by animateIntSizeAsState(
        targetValue = targetIndicatorSize,
        label = "bottomNavSamplePillSize",
    )
    val isIndicatorDirectlyPositioned = dragIndicatorCenterX != null || settleMotion != null
    val indicatorOffset = if (isIndicatorDirectlyPositioned) targetIndicatorOffset else animatedIndicatorOffset
    val indicatorSize = if (isIndicatorDirectlyPositioned) targetIndicatorSize else animatedIndicatorSize
    val pillEffectProgress = maxOf(pillSelectPulse.value, pillPressProgress.value)
    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            containerPositionInRoot = coordinates.positionInRoot()
            containerSize = coordinates.size
        },
        contentAlignment = Alignment.Center,
    ) {
        MainNavBottomContainer(
            modifier = Modifier.matchParentSize(),
            style = barStyle,
            shape = barShape,
            exportedBackdrop = navigationShellBackdrop,
        ) {}
        if (capsuleEnabled && targetIndicatorSize != IntSize.Zero) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { indicatorOffset }
                    .size(
                        width = with(density) { indicatorSize.width.toDp() },
                        height = with(density) { indicatorSize.height.toDp() },
                    )
                    .graphicsLayer {
                        clip = false
                    }
                    .zIndex(2f)
                    .mainNavBackdrop(
                        shape = Capsule(),
                        enabled = backdrop != null,
                        backdrop = indicatorBackdrop,
                        selectionTint = pillSelectionTint,
                        pressProgress = { pillEffectProgress },
                        magnifyScale = pillMagnifyScale,
                    ),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
                .layerBackdrop(navigationContentBackdrop),
            horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val isSelected = displayedSelectedItemId == item.id
                val contentColor = if (isSelected) {
                    accentColor
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                CompositionLocalProvider(LocalContentColor provides contentColor) {
                    val itemModifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onGloballyPositioned { coordinates ->
                            if (capsuleEnabled) {
                                val position = coordinates.positionInRoot() - containerPositionInRoot
                                itemBounds[item.id] = NavItemBounds(
                                    itemId = item.id,
                                    offset = IntOffset(position.x.roundToInt(), position.y.roundToInt()),
                                    size = IntSize(coordinates.size.width, coordinates.size.height),
                                )
                            }
                        }
                        .pointerInput(items, item.id) {
                            if (!capsuleEnabled) return@pointerInput
                            var pointerX = 0f
                            detectDragGestures(
                                onDragStart = { startOffset ->
                                    settleMotion = null
                                    val itemOffset = itemBounds[item.id]?.offset?.x?.toFloat() ?: 0f
                                    pointerX = itemOffset + startOffset.x
                                    dragIndicatorCenterX = pointerX
                                    dragIndicatorWidthPx = itemBounds[item.id]?.size?.width
                                    dragPreviewItemId = item.id
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    pointerX += dragAmount.x
                                    dragIndicatorCenterX = pointerX
                                    val targetItemId = itemBounds
                                        .values
                                        .firstOrNull { it.containsHorizontal(pointerX) }
                                        ?.itemId
                                    if (targetItemId != null) {
                                        dragPreviewItemId = targetItemId
                                    }
                                },
                                onDragCancel = {
                                    settleMotion = null
                                    dragIndicatorCenterX = null
                                    dragIndicatorWidthPx = null
                                    dragPreviewItemId = null
                                },
                                onDragEnd = {
                                    val targetItemId = dragPreviewItemId
                                    val targetBounds = targetItemId?.let(itemBounds::get)
                                    if (targetItemId != null && targetBounds != null) {
                                        settleMotion = BottomNavSettleMotion(
                                            startCenterX = pointerX,
                                            targetCenterX = targetBounds.offset.x + targetBounds.size.width / 2f,
                                        )
                                    }
                                    if (targetItemId != null && targetItemId != currentSelectedItemId) {
                                        justReleasedFromDrag = true
                                        currentOnItemSelected(targetItemId)
                                    }
                                    if (targetBounds == null) {
                                        dragIndicatorCenterX = null
                                        dragIndicatorWidthPx = null
                                        dragPreviewItemId = null
                                    }
                                },
                            )
                        }
                        .clickable(
                            interactionSource = remember(item.id) { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                if (isSelected) {
                                    onItemReselected(item.id)
                                } else {
                                    clickPulses[item.id] = (clickPulses[item.id] ?: 0) + 1
                                    onItemSelected(item.id)
                                }
                            },
                        )
                    if (labelsToTheRight) {
                        Row(
                            modifier = itemModifier,
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PremiumNavigationIcon(
                                itemId = item.id,
                                isSelected = isSelected,
                                clickPulse = clickPulses[item.id] ?: 0,
                                badge = badges[item.id],
                                contentDescription = stringResource(item.title),
                                selectedTint = accentColor,
                            )
                            // Pill arrangement shows its label beside the selected
                            // icon only (mirrors the compact pill behaviour).
                            if (showSelectedLabels && isSelected) {
                                Text(
                                    text = stringResource(item.title),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 72.dp),
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = itemModifier,
                            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            PremiumNavigationIcon(
                                itemId = item.id,
                                isSelected = isSelected,
                                clickPulse = clickPulses[item.id] ?: 0,
                                badge = badges[item.id],
                                contentDescription = stringResource(item.title),
                                selectedTint = accentColor,
                            )
                            // The sample always keeps every tab label visible; expose
                            // that via the "always show labels" toggle (fall back to
                            // the selected-only label when it is off).
                            if (showSelectedLabels && (isSelected || labelsAlwaysVisible)) {
                                Text(
                                    text = stringResource(item.title),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class NavItemBounds(
    val itemId: Int,
    val offset: IntOffset,
    val size: IntSize,
) {
    fun containsHorizontal(positionX: Float): Boolean =
        positionX >= offset.x && positionX <= offset.x + size.width
}

@Composable
private fun PremiumNavigationIcon(
    itemId: Int,
    isSelected: Boolean,
    clickPulse: Int,
    badge: BadgeInfo?,
    contentDescription: String,
    selectedTint: Color? = null,
) {
    BadgedBox(
        modifier = Modifier.wrapContentSize(unbounded = true),
        badge = {
            if (badge?.isVisible == true) {
                if (badge.number > 0) {
                    Badge(
                        modifier = Modifier
                            .heightIn(min = 16.dp)
                            .widthIn(min = 24.dp),
                    ) {
                        Text(
                            text = formatBottomNavBadgeNumber(badge.number),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                } else {
                    Badge()
                }
            }
        },
    ) {
        AnimatedNavigationIcon(
            itemId = itemId,
            isSelected = isSelected,
            clickPulse = clickPulse,
            contentDescription = contentDescription,
            selectedTint = selectedTint,
        )
    }
}

internal fun formatBottomNavBadgeNumber(number: Int): String = when {
    number > 999 -> "999+"
    number > 0 -> number.toString()
    else -> ""
}

@Composable
private fun AnimatedNavigationIcon(
    itemId: Int,
    isSelected: Boolean,
    clickPulse: Int,
    contentDescription: String,
    selectedTint: Color? = null,
) {
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val animatedResId = remember(itemId) { navEnterAnimationResId(itemId) }
    val staticResId = remember(itemId, isSelected) { premiumIconResId(itemId, isSelected) }
    val enterAnimationResId = if (isSelected && clickPulse > 0) animatedResId else null
    val tint = lerp(
        MaterialTheme.colorScheme.onSurfaceVariant,
        selectedTint ?: if (isIosStyle) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        if (isSelected) 1f else 0f,
    )

    if (enterAnimationResId != null) {
        key(clickPulse) {
            AndroidView(
                modifier = Modifier.size(24.dp),
                factory = { context ->
                    android.widget.ImageView(context).apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER
                        setColorFilter(tint.toArgb())
                        this.contentDescription = contentDescription
                    }
                },
                update = { view ->
                    view.contentDescription = contentDescription
                    view.setColorFilter(tint.toArgb())
                    view.setImageDrawable(ContextCompat.getDrawable(view.context, enterAnimationResId)?.mutate())
                    (view.drawable as? android.graphics.drawable.Animatable)?.start()
                },
            )
        }
    } else {
        Icon(
            painter = painterResource(staticResId),
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
        )
    }
}

private fun premiumIconResId(itemId: Int, isSelected: Boolean): Int {
    return when (itemId) {
        R.id.nav_home -> if (isSelected) R.drawable.ic_home_filled else R.drawable.ic_home
        R.id.nav_history -> R.drawable.ic_history
        R.id.nav_favorites -> if (isSelected) R.drawable.ic_heart else R.drawable.ic_heart_outline
        R.id.nav_explore -> if (isSelected) R.drawable.ic_explore_checked else R.drawable.ic_explore_normal
        R.id.nav_discover -> if (isSelected) R.drawable.ic_bangumi else R.drawable.ic_bangumi_outline
        R.id.nav_suggestions -> if (isSelected) R.drawable.ic_suggestion_checked else R.drawable.ic_suggestion
        R.id.nav_feed -> R.drawable.ic_feed
        R.id.nav_updated -> if (isSelected) R.drawable.ic_updated_checked else R.drawable.ic_updated
        R.id.nav_bookmarks -> if (isSelected) R.drawable.ic_bookmark_checked else R.drawable.ic_bookmark
        R.id.nav_local -> if (isSelected) R.drawable.ic_storage_checked else R.drawable.ic_storage
        else -> R.drawable.ic_home // fallback
    }
}

private fun navEnterAnimationResId(itemId: Int): Int? {
    return when (itemId) {
        R.id.nav_home -> R.drawable.avd_home_enter
        R.id.nav_history -> R.drawable.avd_history_enter
        R.id.nav_feed -> R.drawable.avd_feed_enter
        R.id.nav_explore -> R.drawable.avd_explore_enter
        R.id.nav_favorites -> R.drawable.avd_favourites_enter
        else -> null
    }
}
