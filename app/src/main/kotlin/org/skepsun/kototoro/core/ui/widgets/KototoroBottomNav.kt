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
import org.skepsun.kototoro.core.prefs.NavItem
import org.skepsun.kototoro.core.prefs.limitMainNavigationItems
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.BaseActivityEntryPoint
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.glass.GlassBottomBarContainer
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.glass.GlassStyle
import org.skepsun.kototoro.core.ui.glass.GlassVisualTreatment
import org.skepsun.kototoro.core.util.FoldableUtils
import dagger.hilt.android.EntryPointAccessors
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

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

    val verticalPadding by androidx.compose.animation.core.animateDpAsState(
        if (isFloating && !useNavigationRail) 16.dp else 0.dp,
    )
    val railHorizontalPadding by androidx.compose.animation.core.animateDpAsState(
        if (isFloating && useNavigationRail) 12.dp else 0.dp,
    )
    val railVerticalPadding by androidx.compose.animation.core.animateDpAsState(
        if (isFloating && useNavigationRail) 18.dp else 0.dp,
    )

    val navBarModifier = Modifier
        .then(
            if (useNavigationRail) {
                Modifier
                    .fillMaxHeight()
                    .padding(
                        start = railHorizontalPadding + railStartInset,
                        end = railHorizontalPadding + railEndInset,
                        top = railVerticalPadding + statusBarTopPadding,
                        bottom = railVerticalPadding + railBottomInset,
                    )
            } else {
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isFloating) 12.dp else 0.dp, vertical = verticalPadding)
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
        .padding(vertical = verticalPadding)
        .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)

    val currentExplicitHeight by androidx.compose.animation.core.animateDpAsState(
        if (isFloating && !useNavigationRail) (navFloatingHeight + 4).dp else navHeight.dp
    )
    val nonFloatingContentHorizontalPadding = 6.dp
    val nonFloatingTopPadding = 4.dp
    val railWidth = if (isFloating) {
        (navFloatingHeight + 4).dp.coerceIn(60.dp, 160.dp)
    } else {
        navHeight.dp.coerceIn(60.dp, 160.dp)
    }

    val navContainerStyle = if (isFloating) {
        GlassDefaults.bottomBarChromeStyle().copy(
            containerAlpha = targetAlpha,
            borderAlpha = 0.10f,
            shadowElevation = 0.dp,
        )
    } else {
        GlassDefaults.bottomBarChromeStyle().copy(
            containerAlpha = (targetAlpha - 0.06f).coerceAtLeast(0.70f),
            borderAlpha = 0f,
            shadowElevation = 0.dp,
        )
    }
    val navBackdrop = LocalLiquidGlassBackdrop.current
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS

    if (useNavigationRail) {
        MainNavBottomContainer(
            modifier = navBarModifier.mainNavBackdrop(
                shape = RoundedCornerShape(24.dp),
                enabled = isIosStyle,
                backdrop = navBackdrop,
            ),
            style = navContainerStyle,
            useBackdrop = isIosStyle && navBackdrop != null,
            backdrop = navBackdrop,
        ) {
            NavigationRail(
                containerColor = Color.Transparent,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(railWidth)
                    .padding(
                        horizontal = if (isFloating) 6.dp else 0.dp,
                        vertical = if (isFloating) 10.dp else 0.dp,
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
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
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
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
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
            val layoutSpec = remember(maxWidth, activeItems.size, adjacentAction != null, showSelectedLabels) {
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
                    useBackdrop = isIosStyle && navBackdrop != null,
                    backdrop = navBackdrop,
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
                useBackdrop = isIosStyle && navBackdrop != null,
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
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
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
    useBackdrop: Boolean = false,
    backdrop: com.kyant.backdrop.Backdrop? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    if (useBackdrop && backdrop != null) {
        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.28f), RoundedCornerShape(24.dp))
                .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(24.dp)),
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
    if (useBackdrop && backdrop != null) {
        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.28f), shape)
                .border(1.dp, Color.White.copy(alpha = 0.22f), shape),
        ) {
            content()
        }
    } else {
        GlassSurface(
            modifier = modifier,
            style = style,
            shape = shape,
            visualTreatment = GlassVisualTreatment.TopBarPrototype,
            componentRole = GlassComponentRole.BottomBar,
            content = content,
        )
    }
}

private fun Modifier.mainNavBackdrop(
    shape: Shape,
    enabled: Boolean,
    backdrop: com.kyant.backdrop.Backdrop?,
): Modifier = then(
    if (enabled && backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(4.dp.toPx())
                lens(
                    refractionHeight = 10.dp.toPx(),
                    refractionAmount = 12.dp.toPx(),
                    chromaticAberration = true,
                )
            },
        )
    } else {
        Modifier
    },
)

@Composable
private fun ContinueReadingRailButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(18.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_read),
                contentDescription = null,
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
    val useSharedLiquidGlassPill = useExpressivePill && isIosStyle && backdrop != null
    val itemBounds = remember { mutableStateMapOf<Int, NavItemBounds>() }
    var containerPositionInRoot by remember { mutableStateOf(Offset.Zero) }
    var dragPreviewItemId by remember { mutableStateOf<Int?>(null) }
    val displayedSelectedItemId = dragPreviewItemId ?: selectedItemId
    val selectedBounds = itemBounds[displayedSelectedItemId]
    val density = LocalDensity.current
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
        if (useSharedLiquidGlassPill && indicatorSize != IntSize.Zero) {
            val indicatorShape = RoundedCornerShape(20.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { indicatorOffset }
                    .size(
                        width = with(density) { indicatorSize.width.toDp() },
                        height = with(density) { indicatorSize.height.toDp() },
                    )
                    .background(Color.White.copy(alpha = 0.12f), indicatorShape)
                    .mainNavBackdrop(
                        shape = indicatorShape,
                        enabled = true,
                        backdrop = backdrop,
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.28f), indicatorShape),
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
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            val useLiquidGlassPill = isSelected && useExpressivePill && isIosStyle && backdrop != null
            val selectedContainerColor = if (isSelected && useExpressivePill) {
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
                                onDragStart = {
                                    pointerX = itemBounds[item.id]?.offset?.x?.toFloat() ?: 0f
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
                                                enabled = useLiquidGlassPill,
                                                backdrop = backdrop,
                                            )
                                            .then(
                                                if (useLiquidGlassPill) {
                                                    Modifier.border(
                                                        1.dp,
                                                        Color.White.copy(alpha = 0.28f),
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
    val animatedResId = remember(itemId) { navEnterAnimationResId(itemId) }
    val staticResId = remember(itemId, isSelected) { premiumIconResId(itemId, isSelected) }
    val enterAnimationResId = if (isSelected && clickPulse > 0) animatedResId else null
    val tint = lerp(
        MaterialTheme.colorScheme.onSurfaceVariant,
        MaterialTheme.colorScheme.onSecondaryContainer,
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
