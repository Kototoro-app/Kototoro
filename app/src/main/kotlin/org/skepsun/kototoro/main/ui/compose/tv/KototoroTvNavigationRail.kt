package org.skepsun.kototoro.main.ui.compose.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.NavItem
import org.skepsun.kototoro.core.prefs.limitMainNavigationItems
import org.skepsun.kototoro.core.ui.widgets.BadgeInfo
import org.skepsun.kototoro.core.ui.widgets.BottomNavState
import org.skepsun.kototoro.core.ui.widgets.premiumIconResId

private val TvNavigationRailWidth = 84.dp
private val TvNavigationItemShape = RoundedCornerShape(14.dp)

/**
 * Fixed-width TV navigation that shares the normal bottom navigation state.
 * Focus is tracked independently from the selected destination so a remote
 * can browse items without switching pages until the confirm key is pressed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TvNavigationRail(
    state: StateFlow<BottomNavState>,
    onItemSelected: (Int) -> Unit,
    onItemReselected: (Int) -> Unit,
    railHeaderContent: (@Composable () -> Unit)? = null,
    showContinueReadingButton: Boolean = false,
    onContinueReadingClick: () -> Unit = {},
    continueReadingIconRes: Int = R.drawable.ic_read,
    continueReadingContentDescriptionRes: Int = R.string._continue,
) {
    val navState by state.collectAsStateWithLifecycle()
    val activeItems = remember(navState.items, navState.itemVisibility) {
        navState.items
            .filter { navState.itemVisibility[it.id] != false }
            .limitMainNavigationItems()
    }
    val activeItemIds = remember(activeItems) { activeItems.map(NavItem::id) }
    val focusRequesters = remember(activeItemIds) {
        activeItemIds.associateWith { FocusRequester() }
    }
    var focusedItemId by rememberSaveable { mutableStateOf<Int?>(null) }
    var railHasFocus by remember { mutableStateOf(false) }

    val fallbackItemId = resolveTvNavigationFocusItem(activeItemIds, navState.selectedItemId)
    val fallbackFocusRequester = focusRequesters[fallbackItemId] ?: FocusRequester.Default

    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(TvNavigationRailWidth),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding() + 12.dp,
                    bottom = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding() +
                        12.dp,
                    start = 8.dp,
                    end = 8.dp,
                )
                .onFocusChanged { railHasFocus = it.hasFocus }
                .focusRestorer(fallback = fallbackFocusRequester)
                .focusGroup()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            railHeaderContent?.invoke()
            if (railHeaderContent != null) {
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (showContinueReadingButton) {
                TvNavigationAction(
                    iconRes = continueReadingIconRes,
                    contentDescriptionRes = continueReadingContentDescriptionRes,
                    onClick = onContinueReadingClick,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            activeItems.forEach { item ->
                val isSelected = navState.selectedItemId == item.id
                val isFocused = railHasFocus && focusedItemId == item.id
                val badge = navState.badges[item.id]
                val itemFocusRequester = focusRequesters.getValue(item.id)
                Surface(
                    onClick = {
                        if (isSelected) {
                            onItemReselected(item.id)
                        } else {
                            onItemSelected(item.id)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .focusRequester(itemFocusRequester)
                        .onFocusChanged { focusState ->
                            if (focusState.hasFocus) {
                                focusedItemId = item.id
                            } else if (focusedItemId == item.id) {
                                focusedItemId = null
                            }
                        },
                    shape = TvNavigationItemShape,
                    color = when {
                        isFocused -> MaterialTheme.colorScheme.primaryContainer
                        isSelected -> MaterialTheme.colorScheme.secondaryContainer
                        else -> Color.Transparent
                    },
                    contentColor = when {
                        isFocused -> MaterialTheme.colorScheme.onPrimaryContainer
                        isSelected -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    border = if (isFocused) {
                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        null
                    },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    ) {
                        BadgedBox(
                            badge = {
                                if (badge?.isVisible == true) {
                                    TvNavigationBadge(badge)
                                }
                            },
                        ) {
                            Icon(
                                painter = painterResource(tvNavigationIconResId(item.id, isSelected)),
                                contentDescription = stringResource(item.title),
                                modifier = Modifier.width(28.dp),
                            )
                        }
                        Text(
                            text = stringResource(item.title),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvNavigationAction(
    iconRes: Int,
    contentDescriptionRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .onFocusChanged {
                isFocused = it.isFocused
            },
        shape = TvNavigationItemShape,
        color = if (isFocused) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.tertiaryContainer
        },
        contentColor = if (isFocused) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onTertiaryContainer
        },
        border = if (isFocused) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = stringResource(contentDescriptionRes),
                modifier = Modifier.width(28.dp),
            )
            Text(
                text = stringResource(contentDescriptionRes),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TvNavigationBadge(info: BadgeInfo) {
    Badge {
        Text(text = info.number.coerceAtMost(99).toString())
    }
}

internal fun tvNavigationIconResId(
    itemId: Int,
    isSelected: Boolean,
): Int = premiumIconResId(itemId, isSelected)

internal fun resolveTvNavigationFocusItem(activeItemIds: List<Int>, selectedItemId: Int): Int? =
    selectedItemId.takeIf(activeItemIds::contains) ?: activeItemIds.firstOrNull()
