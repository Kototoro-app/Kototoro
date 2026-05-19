package org.skepsun.kototoro.core.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.NavItem
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.BaseActivityEntryPoint
import org.skepsun.kototoro.core.ui.glass.GlassBottomBarContainer
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.util.FoldableUtils
import dagger.hilt.android.EntryPointAccessors

@Immutable
private data class BottomNavPrefs(
    val isFloating: Boolean,
    val isFloatingAdaptiveWidth: Boolean,
    val isLabelsVisible: Boolean,
    val navHeight: Int,
    val navFloatingHeight: Int,
)

@Composable
fun KototoroBottomNav(
    state: StateFlow<BottomNavState>,
    onItemSelected: (Int) -> Unit,
    onItemReselected: (Int) -> Unit
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
        AppSettings.KEY_NAV_FLOATING_ADAPTIVE_WIDTH,
        AppSettings.KEY_NAV_LABELS,
        AppSettings.KEY_NAV_HEIGHT,
        AppSettings.KEY_NAV_FLOATING_HEIGHT,
    ) {
        BottomNavPrefs(
            isFloating = isNavFloating,
            isFloatingAdaptiveWidth = isNavFloatingAdaptiveWidth,
            isLabelsVisible = isNavLabelsVisible,
            navHeight = navHeight,
            navFloatingHeight = navFloatingHeight,
        )
    }
    val isFloating = prefs.isFloating
    val isFloatingAdaptiveWidth = prefs.isFloatingAdaptiveWidth
    val isLabelsVisible = prefs.isLabelsVisible
    val navHeight = prefs.navHeight
    val navFloatingHeight = prefs.navFloatingHeight
    val tabletUiMode by appSettings.observeAsState(AppSettings.KEY_TABLET_UI_MODE) { tabletUiMode }

    val activeItems = navState.items.filter { navState.itemVisibility[it.id] != false }
    val useNavigationRail = remember(configuration.orientation, configuration.screenWidthDp, tabletUiMode) {
        FoldableUtils.shouldUseTabletLayout(context, appSettings, configuration)
    }
    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()
    val statusBarTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val railStartInset = systemBarsPadding.calculateStartPadding(layoutDirection)
    val railEndInset = systemBarsPadding.calculateEndPadding(layoutDirection)
    val railBottomInset = systemBarsPadding.calculateBottomPadding()

    val targetAlpha = 0.84f

    val horizontalPadding by androidx.compose.animation.core.animateDpAsState(
        if (isFloating && !useNavigationRail) 12.dp else 0.dp,
    )
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
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding)
                    .run { if (isFloating) navigationBarsPadding() else this }
            },
        )

    val currentExplicitHeight by androidx.compose.animation.core.animateDpAsState(
        if (isFloating && !useNavigationRail) (navFloatingHeight + 4).dp else navHeight.dp
    )
    val floatingAdaptiveWidth = remember(activeItems.size, isLabelsVisible) {
        val itemWidth = if (isLabelsVisible) 80 else 68
        (activeItems.size * itemWidth + 28).dp.coerceIn(220.dp, 520.dp)
    }
    val railWidth = if (isFloating) {
        (navFloatingHeight + 4).dp.coerceIn(60.dp, 160.dp)
    } else {
        navHeight.dp.coerceIn(60.dp, 160.dp)
    }

    val navContainerStyle = if (isFloating) {
        GlassDefaults.prominentStyle().copy(
            containerAlpha = targetAlpha,
            borderAlpha = 0.10f,
            shadowElevation = 0.dp,
        )
    } else {
        GlassDefaults.regularStyle().copy(
            containerAlpha = (targetAlpha - 0.06f).coerceAtLeast(0.70f),
            borderAlpha = 0.10f,
            shadowElevation = 0.dp,
        )
    }

    if (useNavigationRail) {
        GlassBottomBarContainer(
            modifier = navBarModifier,
            style = navContainerStyle,
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
                Spacer(modifier = Modifier.height(8.dp))
                activeItems.forEach { item ->
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
                            val indicatorShape = RoundedCornerShape(16.dp)
                            Box(
                                modifier = Modifier
                                    .then(
                                        if (isSelected) {
                                            Modifier
                                                .background(
                                                    MaterialTheme.colorScheme.secondaryContainer,
                                                    indicatorShape,
                                                )
                                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                        } else {
                                            Modifier
                                        },
                                    ),
                            ) {
                                PremiumNavigationIcon(
                                    itemId = item.id,
                                    isSelected = isSelected,
                                    clickPulse = clickPulses[item.id] ?: 0,
                                    badge = badge,
                                    contentDescription = stringResource(item.title),
                                )
                            }
                        },
                        label = if (isLabelsVisible) {
                            { Text(stringResource(item.title)) }
                        } else {
                            null
                        },
                        alwaysShowLabel = isLabelsVisible,
                        colors = NavigationRailItemDefaults.colors(
                            indicatorColor = Color.Transparent,
                            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    } else if (isFloating) {
        Box(
            modifier = navBarModifier,
            contentAlignment = Alignment.Center,
        ) {
            GlassBottomBarContainer(
                modifier = if (isFloatingAdaptiveWidth) {
                    Modifier.width(floatingAdaptiveWidth)
                } else {
                    Modifier.fillMaxWidth()
                },
                style = navContainerStyle,
            ) {
                FloatingBottomNavRow(
                    items = activeItems,
                    selectedItemId = navState.selectedItemId,
                    badges = navState.badges,
                    isLabelsVisible = isLabelsVisible,
                    clickPulses = clickPulses,
                    onItemSelected = onItemSelected,
                    onItemReselected = onItemReselected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(currentExplicitHeight)
                        .padding(horizontal = 4.dp),
                )
            }
        }
    } else {
        GlassSurface(
            modifier = navBarModifier,
            style = navContainerStyle,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(
                topStart = 32.dp,
                topEnd = 32.dp,
                bottomStart = 0.dp,
                bottomEnd = 0.dp,
            ),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                NavigationBar(
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(currentExplicitHeight),
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
                            label = if (isLabelsVisible) {
                                { Text(stringResource(item.title)) }
                            } else null,
                            alwaysShowLabel = isLabelsVisible,
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
                Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

@Composable
private fun FloatingBottomNavRow(
    items: List<NavItem>,
    selectedItemId: Int,
    badges: Map<Int, BadgeInfo>,
    isLabelsVisible: Boolean,
    clickPulses: MutableMap<Int, Int>,
    onItemSelected: (Int) -> Unit,
    onItemReselected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val isSelected = selectedItemId == item.id
            val interactionSource = remember(item.id) { MutableInteractionSource() }
            val contentColor = if (isSelected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
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
                        .padding(horizontal = 2.dp, vertical = if (isLabelsVisible) 6.dp else 0.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 64.dp, height = 32.dp)
                            .background(
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    Color.Transparent
                                },
                                shape = RoundedCornerShape(percent = 50),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        PremiumNavigationIcon(
                            itemId = item.id,
                            isSelected = isSelected,
                            clickPulse = clickPulses[item.id] ?: 0,
                            badge = badges[item.id],
                            contentDescription = stringResource(item.title),
                        )
                    }
                    if (isLabelsVisible) {
                        Spacer(modifier = Modifier.height(2.dp))
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
        R.id.nav_history -> R.drawable.avd_history_enter
        R.id.nav_feed -> R.drawable.avd_feed_enter
        R.id.nav_explore -> R.drawable.avd_explore_enter
        R.id.nav_favorites -> R.drawable.avd_favourites_enter
        else -> null
    }
}
