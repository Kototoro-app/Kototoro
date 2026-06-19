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
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
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
    topInset: Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = WindowInsets.displayCutout
                    .only(WindowInsetsSides.Start)
                    .asPaddingValues()
                    .calculateLeftPadding(layoutDirection) + 16.dp,
                end = WindowInsets.displayCutout
                    .only(WindowInsetsSides.End)
                    .asPaddingValues()
                    .calculateRightPadding(layoutDirection) + 16.dp,
                top = topInset + 8.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (expressive) 30.dp else 24.dp),
        style = if (expressive) {
            GlassDefaults.regularStyle().copy(shadowElevation = 0.dp)
        } else {
            GlassDefaults.subtleStyle()
        },
        allowRuntimeHaze = false,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            section.items.forEachIndexed { index, item ->
                SettingsRootRow(item = item)
                if (index != section.items.lastIndex) {
                    SettingsRootDivider(startPadding = 68.dp)
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
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (expressive) 30.dp else 24.dp),
        style = if (expressive) {
            GlassDefaults.regularStyle().copy(shadowElevation = 0.dp)
        } else {
            GlassDefaults.subtleStyle()
        },
        allowRuntimeHaze = false,
    ) {
        if (results.isEmpty()) {
            Text(
                text = stringResource(R.string.nothing_found),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                results.forEachIndexed { index, item ->
                    SettingsSearchResultRow(
                        item = item,
                        onClick = { onItemClick(item) },
                    )
                    if (index != results.lastIndex) {
                        SettingsRootDivider(startPadding = 20.dp)
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (expressive) {
                    Modifier
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(20.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                } else {
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = item.onClick)
            .then(
                if (expressive) {
                    Modifier
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(20.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                } else {
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .then(
                    if (expressive) {
                        Modifier.background(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.64f),
                            shape = RoundedCornerShape(16.dp),
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
                tint = if (expressive) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
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
    if (LocalMaterialExpressiveComponentsEnabled.current) {
        Spacer(modifier = Modifier.height(2.dp))
    } else {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = startPadding, end = 20.dp)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)),
        )
    }
}
