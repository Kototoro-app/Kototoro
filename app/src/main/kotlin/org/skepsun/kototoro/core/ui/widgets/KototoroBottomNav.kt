package org.skepsun.kototoro.core.ui.widgets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.animateIntSizeAsState
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Offset
import kotlin.math.roundToInt
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.material.navigation.NavigationBarView
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.BackgroundStyle
import org.skepsun.kototoro.core.prefs.NavItem
import org.skepsun.kototoro.core.prefs.limitMainNavigationItems
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.BaseActivityEntryPoint
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.theme.LocalBackgroundStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.glass.GlassBottomBarContainer
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.glass.GlassStyle
import org.skepsun.kototoro.core.ui.glass.glassContainerShadow
import org.skepsun.kototoro.core.util.FoldableUtils
import dagger.hilt.android.EntryPointAccessors
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import coil3.compose.rememberAsyncImagePainter

data class BadgeInfo(val number: Int = 0, val isVisible: Boolean = false)

data class BottomNavState(
    val items: List<NavItem> = emptyList(),
    val selectedItemId: Int = 0,
    val labelVisibilityMode: Int = NavigationBarView.LABEL_VISIBILITY_AUTO,
    val badges: Map<Int, BadgeInfo> = emptyMap(),
    val itemVisibility: Map<Int, Boolean> = emptyMap(),
)

@Immutable
private data class BottomNavPrefs(
    val isFloating: Boolean,
    val isExpressivePillEnabled: Boolean,
    val navHeight: Int,
    val navFloatingHeight: Int,
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
    val navState by state.collectAsState()
    val clickPulses = remember { mutableStateMapOf<Int, Int>() }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val layoutDirection = LocalLayoutDirection.current
    val appSettings = remember {
        EntryPointAccessors.fromApplication<BaseActivityEntryPoint>(context.applicationContext).settings
    }

    val prefs by appSettings.observeAsState(
        AppSettings.KEY_NAV_FLOATING,
        AppSettings.KEY_NAV_EXPRESSIVE_PILL,
        AppSettings.KEY_NAV_HEIGHT,
        AppSettings.KEY_NAV_FLOATING_HEIGHT,
    ) {
        BottomNavPrefs(
            isFloating = isNavFloating,
            isExpressivePillEnabled = isNavExpressivePillEnabled,
            navHeight = navHeight,
            navFloatingHeight = navFloatingHeight,
        )
    }
    val isFloating = prefs.isFloating
    val isExpressivePillEnabled = prefs.isExpressivePillEnabled
    val navHeight = prefs.navHeight
    val navFloatingHeight = prefs.navFloatingHeight
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val tabletUiMode by appSettings.observeAsState(AppSettings.KEY_TABLET_UI_MODE) { tabletUiMode }

    val activeItems = navState.items
        .filter { navState.itemVisibility[it.id] != false }
        .limitMainNavigationItems()
    val showSelectedLabels = navState.labelVisibilityMode != NavigationBarView.LABEL_VISIBILITY_UNLABELED
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
    val navBackdrop = LocalLiquidGlassBackdrop.current

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
                                )
                            },
                            label = { Text(stringResource(item.title)) },
                            alwaysShowLabel = showSelectedLabels,
                            colors = NavigationRailItemDefaults.colors(
                                indicatorColor = Color.Transparent,
                                selectedIconColor = if (isIosStyle) {
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
            ) {
                resolveBottomNavLayout(
                    availableWidth = maxWidth,
                    itemCount = activeItems.size,
                    fabWidth = 56.dp.takeIf { adjacentAction != null },
                    showLabels = showSelectedLabels,
                    isExpressivePill = isExpressivePillEnabled,
                )
            }
            Row(
                modifier = Modifier
                    .wrapContentWidth()
                    .padding(horizontal = layoutSpec.outerHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(layoutSpec.fabGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MainNavBottomContainer(
                    modifier = Modifier
                        .wrapContentWidth()
                        .mainNavBackdrop(
                            shape = RoundedCornerShape(28.dp),
                            enabled = isIosStyle,
                            backdrop = navBackdrop,
                        ),
                    style = navContainerStyle,
                    shape = RoundedCornerShape(28.dp),
                    useBackdrop = isIosStyle,
                ) {
                    FloatingBottomNavRow(
                        items = activeItems,
                        selectedItemId = navState.selectedItemId,
                        badges = navState.badges,
                        clickPulses = clickPulses,
                        showSelectedLabels = layoutSpec.showLabels,
                        useExpressivePill = isExpressivePillEnabled,
                        itemSpacing = layoutSpec.itemSpacing,
                        labelScale = layoutSpec.labelScale,
                        labelMaxWidth = layoutSpec.labelMaxWidth,
                        onItemSelected = onItemSelected,
                        onItemReselected = onItemReselected,
                        modifier = Modifier
                            .wrapContentWidth()
                            .height(currentExplicitHeight)
                            .padding(horizontal = layoutSpec.horizontalPadding),
                    )
                }
                adjacentAction?.invoke()
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
                MainNavSurface(
                modifier = Modifier
                    .weight(1f)
                    .mainNavBackdrop(
                        shape = RoundedCornerShape(0.dp),
                        enabled = isIosStyle,
                        backdrop = navBackdrop,
                    ),
                style = navContainerStyle,
                shape = RoundedCornerShape(0.dp),
                useBackdrop = isIosStyle,
                backdrop = navBackdrop,
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
                                )
                            },
                            label = { Text(stringResource(item.title)) },
                            alwaysShowLabel = showSelectedLabels,
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = Color.Transparent,
                                selectedIconColor = if (isIosStyle) {
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
    useBackdrop: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    if (useBackdrop) {
        Box(
            modifier = modifier
                .glassContainerShadow(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.34f), shape),
        ) {
            content()
        }
    } else {
        GlassBottomBarContainer(
            modifier = modifier,
            style = style,
            content = content,
        )
    }
}

@Composable
private fun MainNavSurface(
    modifier: Modifier,
    style: GlassStyle,
    shape: Shape,
    useBackdrop: Boolean,
    backdrop: com.kyant.backdrop.Backdrop?,
    content: @Composable BoxScope.() -> Unit,
) {
    if (useBackdrop) {
        Box(
            modifier = modifier
                .glassContainerShadow(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.34f), shape),
        ) {
            content()
        }
    } else {
        GlassSurface(
            modifier = modifier,
            style = style,
            shape = shape,
            componentRole = GlassComponentRole.BottomBar,
            content = content,
        )
    }
}

@Composable
private fun Modifier.mainNavBackdrop(
    shape: Shape,
    enabled: Boolean,
    backdrop: com.kyant.backdrop.Backdrop?,
): Modifier {
    val blurRadius = if (LocalBackgroundStyle.current == BackgroundStyle.DYNAMIC_ARTWORK_BLUR) {
        8.dp
    } else {
        4.dp
    }
    return then(if (enabled && backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(blurRadius.toPx())
                lens(
                    refractionHeight = 10.dp.toPx(),
                    refractionAmount = 12.dp.toPx(),
                    chromaticAberration = true,
                )
            },
        )
    } else {
        Modifier
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
    itemSpacing: Dp,
    labelScale: Float,
    labelMaxWidth: Dp?,
    onItemSelected: (Int) -> Unit,
    onItemReselected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val backdrop = LocalLiquidGlassBackdrop.current
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val useSharedLiquidGlassPill = useExpressivePill && isIosStyle
    val itemBounds = remember { mutableStateMapOf<Int, NavItemBounds>() }
    var containerPositionInRoot by remember { mutableStateOf(Offset.Zero) }
    var dragPreviewItemId by remember { mutableStateOf<Int?>(null) }
    val displayedSelectedItemId = dragPreviewItemId ?: selectedItemId
    val selectedBounds = itemBounds[displayedSelectedItemId]
    val density = LocalDensity.current
    val selectedContentColor = if (isIosStyle) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    val targetIndicatorOffset = selectedBounds?.offset?.copy(
        y = selectedBounds.offset.y + with(density) { 4.dp.roundToPx() },
    ) ?: IntOffset.Zero
    val targetIndicatorSize = selectedBounds?.size?.let {
        IntSize(it.width, with(density) { 40.dp.roundToPx() })
    } ?: IntSize.Zero
    val indicatorOffset by animateIntOffsetAsState(
        targetValue = targetIndicatorOffset,
        label = "bottomNavGlassPillOffset",
    )
    val indicatorSize by animateIntSizeAsState(
        targetValue = targetIndicatorSize,
        label = "bottomNavGlassPillSize",
    )
    Box(
        modifier = modifier
            .animateContentSize()
            .onGloballyPositioned { coordinates ->
                containerPositionInRoot = coordinates.positionInRoot()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (useSharedLiquidGlassPill && targetIndicatorSize != IntSize.Zero) {
            val indicatorShape = RoundedCornerShape(20.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { indicatorOffset }
                    .size(
                        width = with(density) { indicatorSize.width.toDp() },
                        height = with(density) { indicatorSize.height.toDp() },
                    )
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.42f),
                        indicatorShape,
                    )
                    .mainNavBackdrop(
                        shape = indicatorShape,
                        enabled = backdrop != null,
                        backdrop = backdrop,
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f),
                        shape = indicatorShape,
                    ),
            )
        }
        Row(
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
            val useLiquidGlassPill = isSelected && useExpressivePill && isIosStyle
            val selectedContainerColor = if (isSelected && useExpressivePill && !isIosStyle) {
                if (useLiquidGlassPill) {
                    Color.White.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                }
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
                                    val itemOffset = itemBounds[item.id]?.offset?.x?.toFloat() ?: 0f
                                    // detectDragGestures reports startOffset in the item's
                                    // local coordinates; convert it to the shared row space
                                    // before comparing against sibling bounds.
                                    pointerX = itemOffset + startOffset.x
                                    dragPreviewItemId = item.id
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    pointerX += dragAmount.x
                                    val targetItemId = itemBounds
                                        .values
                                        .firstOrNull { it.containsHorizontal(pointerX) }
                                        ?.itemId
                                    if (targetItemId != null) {
                                        dragPreviewItemId = targetItemId
                                    }
                                },
                                onDragCancel = {
                                    dragPreviewItemId = null
                                },
                                onDragEnd = {
                                    val targetItemId = dragPreviewItemId
                                    dragPreviewItemId = null
                                    if (targetItemId != null && targetItemId != selectedItemId) {
                                        onItemSelected(targetItemId)
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
                    Box(
                        modifier = itemModifier
                            .height(48.dp)
                            .animateContentSize(alignment = Alignment.Center)
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier
                                .height(40.dp)
                                .widthIn(min = 40.dp)
                                .animateContentSize(alignment = Alignment.Center)
                                .then(
                                    if (useSharedLiquidGlassPill) {
                                        Modifier
                                    } else {
                                        Modifier
                                            .background(selectedContainerColor, CircleShape)
                                            .mainNavBackdrop(
                                                shape = CircleShape,
                                                enabled = useLiquidGlassPill && backdrop != null,
                                                backdrop = backdrop,
                                            )
                                            .then(
                                                if (useLiquidGlassPill) {
                                                    Modifier.border(
                                                        1.dp,
                                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f),
                                                        CircleShape,
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
                                        .padding(start = 8.dp)
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
                    if (isSelected && showSelectedLabels) {
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
) {
    BadgedBox(
        badge = {
            if (badge?.isVisible == true) {
                if (badge.number > 0) {
                    Badge { Text(badge.number.toString()) }
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
        )
    }
}

@Composable
private fun AnimatedNavigationIcon(
    itemId: Int,
    isSelected: Boolean,
    clickPulse: Int,
    contentDescription: String,
) {
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val animatedResId = remember(itemId) { navEnterAnimationResId(itemId) }
    val staticResId = remember(itemId, isSelected) { premiumIconResId(itemId, isSelected) }
    val enterAnimationResId = if (isSelected && clickPulse > 0) animatedResId else null
    val tint = lerp(
        MaterialTheme.colorScheme.onSurfaceVariant,
        if (isIosStyle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer,
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
