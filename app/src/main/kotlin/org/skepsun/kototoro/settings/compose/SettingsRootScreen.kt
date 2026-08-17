package org.skepsun.kototoro.settings.compose

import androidx.annotation.DrawableRes
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.settings.search.SettingsItem

data class SettingsRootSection(
    val title: String,
    val items: List<SettingsRootItem>,
)

data class SettingsRootItem(
    val key: String,
    @DrawableRes val iconRes: Int,
    val iosIconColor: SettingsRootIconColor,
    val title: String,
    val summary: String,
    val onClick: () -> Unit,
)

enum class SettingsRootIconColor(val color: Color) {
    BLUE(Color(0xFF007AFF)),
    CYAN(Color(0xFF32ADE6)),
    GREEN(Color(0xFF34C759)),
    INDIGO(Color(0xFF5856D6)),
    ORANGE(Color(0xFFFF9500)),
    PURPLE(Color(0xFFAF52DE)),
    RED(Color(0xFFFF3B30)),
    TEAL(Color(0xFF30B0C7)),
    GRAY(Color(0xFF8E8E93)),
}

@Composable
private fun SettingsRootIconColor.containerColor(isIosStyle: Boolean): Color {
    if (isIosStyle) return color
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        SettingsRootIconColor.BLUE -> scheme.primaryContainer
        SettingsRootIconColor.INDIGO -> lerp(scheme.primaryContainer, scheme.tertiaryContainer, 0.35f)
        SettingsRootIconColor.CYAN -> scheme.secondaryContainer
        SettingsRootIconColor.GREEN -> lerp(scheme.secondaryContainer, scheme.tertiaryContainer, 0.28f)
        SettingsRootIconColor.TEAL -> lerp(scheme.secondaryContainer, scheme.primaryContainer, 0.32f)
        SettingsRootIconColor.ORANGE -> scheme.tertiaryContainer
        SettingsRootIconColor.PURPLE -> lerp(scheme.tertiaryContainer, scheme.primaryContainer, 0.30f)
        SettingsRootIconColor.RED -> lerp(scheme.tertiaryContainer, scheme.secondaryContainer, 0.38f)
        SettingsRootIconColor.GRAY -> scheme.surfaceContainerHighest
    }
}

@Composable
private fun SettingsRootIconColor.contentColor(isIosStyle: Boolean): Color {
    if (isIosStyle) return Color.White
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        SettingsRootIconColor.BLUE -> scheme.onPrimaryContainer
        SettingsRootIconColor.INDIGO -> lerp(scheme.onPrimaryContainer, scheme.onTertiaryContainer, 0.35f)
        SettingsRootIconColor.CYAN -> scheme.onSecondaryContainer
        SettingsRootIconColor.GREEN -> lerp(scheme.onSecondaryContainer, scheme.onTertiaryContainer, 0.28f)
        SettingsRootIconColor.TEAL -> lerp(scheme.onSecondaryContainer, scheme.onPrimaryContainer, 0.32f)
        SettingsRootIconColor.ORANGE -> scheme.onTertiaryContainer
        SettingsRootIconColor.PURPLE -> lerp(scheme.onTertiaryContainer, scheme.onPrimaryContainer, 0.30f)
        SettingsRootIconColor.RED -> lerp(scheme.onTertiaryContainer, scheme.onSecondaryContainer, 0.38f)
        SettingsRootIconColor.GRAY -> scheme.onSurfaceVariant
    }
}

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
        color = settingsScreenBackgroundColor(),
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
            verticalArrangement = Arrangement.spacedBy(
                if (LocalInterfaceStyle.current == InterfaceStyle.IOS) 16.dp else 10.dp,
            ),
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
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (section.title.isNotBlank()) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.labelLarge,
                color = settingsSectionLabelColor(),
                modifier = Modifier.padding(
                    horizontal = 16.dp,
                    vertical = 6.dp,
                ),
            )
        }
        SettingsItemGroup(itemCount = section.items.size) { index ->
            SettingsRootRow(item = section.items[index])
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
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SettingsItemGroup(itemCount = results.size) { index ->
                val item = results[index]
                SettingsSearchResultRow(
                    item = item,
                    onClick = { onItemClick(item) },
                )
            }
        }
    }
}

@Composable
private fun SettingsSearchResultRow(
    item: SettingsItem,
    onClick: () -> Unit,
) {
    val tokens = LocalInterfaceStyleTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = tokens.settingsItemMinHeight)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.title.toString(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.breadcrumbs.joinToString(" / "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = settingsChevronColor(),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SettingsRootRow(
    item: SettingsRootItem,
) {
    val tokens = LocalInterfaceStyleTokens.current
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = item.onClick)
            .heightIn(min = tokens.settingsItemMinHeight)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(tokens.settingsItemIconContainerSize)
                .settingsIconBackground(
                    baseColor = item.iosIconColor.containerColor(isIosStyle),
                    shape = RoundedCornerShape(if (isIosStyle) 9.dp else 12.dp),
                    isIosStyle = isIosStyle,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = rememberSafePainter(item.iconRes),
                contentDescription = null,
                tint = item.iosIconColor.contentColor(isIosStyle),
                modifier = Modifier.size(if (isIosStyle) 18.dp else 22.dp),
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = settingsChevronColor(),
            modifier = Modifier.size(20.dp),
        )
    }
}
