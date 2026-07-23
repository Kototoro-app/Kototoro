package org.skepsun.kototoro.settings.compose

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.AppLayoutTokens
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.settings.search.SettingsItem

data class SettingsRootSection(
    val title: String,
    val items: List<SettingsRootItem>,
)

data class SettingsRootItem(
    val key: String,
    @DrawableRes val iconRes: Int,
    val title: String,
    val summary: String,
    val onClick: () -> Unit,
)

@Composable
fun SettingsRootScreen(
    sections: List<SettingsRootSection>,
    searchQuery: String,
    searchResults: List<SettingsItem>,
    onSearchResultClick: (SettingsItem) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) },
    topInset: Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
    horizontalPadding: Dp = SettingsContentHorizontalPadding,
    applyHorizontalDisplayCutoutPadding: Boolean = true,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        val layoutDirection = LocalLayoutDirection.current
        val displayCutoutStart = if (applyHorizontalDisplayCutoutPadding) {
            WindowInsets.displayCutout
                .only(WindowInsetsSides.Start)
                .asPaddingValues()
                .calculateLeftPadding(layoutDirection)
        } else {
            0.dp
        }
        val displayCutoutEnd = if (applyHorizontalDisplayCutoutPadding) {
            WindowInsets.displayCutout
                .only(WindowInsetsSides.End)
                .asPaddingValues()
                .calculateRightPadding(layoutDirection)
        } else {
            0.dp
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = displayCutoutStart + horizontalPadding,
                end = displayCutoutEnd + horizontalPadding,
                top = topInset + 4.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (searchQuery.isBlank()) {
                items(sections, key = { it.title }, contentType = { "settings_section" }) { section ->
                    SettingsSectionCard(section = section)
                }
            } else {
                item(key = "search_results") {
                    SettingsSearchResultsCard(
                        results = searchResults,
                        onItemClick = onSearchResultClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    section: SettingsRootSection,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(
                horizontal = AppLayoutTokens.screenHorizontalPadding,
                vertical = 4.dp,
            ),
        )
        if (expressive || isIosStyle) {
            SettingsGroupSurface {
                section.items.forEachIndexed { index, item ->
                    SettingsRootRow(item = item)
                    if (index != section.items.lastIndex) {
                        SettingsRootDivider(startPadding = 62.dp)
                    }
                }
            }
        } else {
            section.items.forEachIndexed { index, item ->
                SettingsRootRow(item = item)
                if (index != section.items.lastIndex) {
                    SettingsRootDivider(startPadding = 62.dp)
                }
            }
        }
    }
}

@Composable
private fun SettingsSearchResultsCard(
    results: List<SettingsItem>,
    onItemClick: (SettingsItem) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (results.isEmpty()) {
            Text(
                text = stringResource(R.string.nothing_found),
                modifier = Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            if (
                LocalMaterialExpressiveComponentsEnabled.current ||
                LocalInterfaceStyle.current == InterfaceStyle.IOS
            ) {
                SettingsGroupSurface {
                    results.forEachIndexed { index, item ->
                        SettingsSearchResultRow(
                            item = item,
                            onClick = { onItemClick(item) },
                        )
                        if (index != results.lastIndex) {
                            SettingsRootDivider(startPadding = 14.dp)
                        }
                    }
                }
            } else {
                results.forEachIndexed { index, item ->
                    SettingsSearchResultRow(
                        item = item,
                        onClick = { onItemClick(item) },
                    )
                    if (index != results.lastIndex) {
                        SettingsRootDivider(startPadding = 14.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSearchResultRow(
    item: SettingsItem,
    onClick: () -> Unit,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (expressive || isIosStyle) {
                    Modifier
                        .heightIn(min = if (isIosStyle) 62.dp else 0.dp)
                        .padding(
                            horizontal = AppLayoutTokens.screenHorizontalPadding,
                            vertical = 10.dp,
                        )
                } else {
                    Modifier.padding(
                        horizontal = AppLayoutTokens.screenHorizontalPadding,
                        vertical = 12.dp,
                    )
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.title.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.breadcrumbs.joinToString(" / "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsRootRow(
    item: SettingsRootItem,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = item.onClick)
            .then(
                if (expressive || isIosStyle) {
                    Modifier
                        .heightIn(min = if (isIosStyle) 62.dp else 0.dp)
                        .padding(
                            horizontal = AppLayoutTokens.screenHorizontalPadding,
                            vertical = 10.dp,
                        )
                } else {
                    Modifier.padding(
                        horizontal = AppLayoutTokens.screenHorizontalPadding,
                        vertical = 12.dp,
                    )
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(if (isIosStyle) 30.dp else 40.dp)
                .then(
                    if (expressive || isIosStyle) {
                        Modifier.background(
                            color = if (isIosStyle) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                            } else {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.64f)
                            },
                            shape = RoundedCornerShape(if (isIosStyle) 8.dp else 14.dp),
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = rememberSafePainter(item.iconRes),
                contentDescription = null,
                tint = if (isIosStyle) {
                    MaterialTheme.colorScheme.primary
                } else if (expressive) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.size(if (isIosStyle) 18.dp else 22.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsRootDivider(
    startPadding: Dp,
) {
    SettingsGroupDivider(startPadding = startPadding)
}
