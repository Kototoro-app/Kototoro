package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.source.SourceListing
import org.skepsun.kototoro.core.ui.source.TabletSourceTile
import org.skepsun.kototoro.desktop.runtime.DesktopNsfwTarget
import org.skepsun.kototoro.desktop.runtime.isNsfwSource
import org.skepsun.kototoro.explore.ui.compose.sourceQuickAccessMetrics

/**
 * The same source tile as Android quick access, with desktop source selection and icon fallback. Android's source
 * settings (§141) decide the order, whether this overview is a grid or a list, and the language headers.
 */
@Composable
internal fun DesktopSourceGrid(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    var query by remember { mutableStateOf("") }
    val settings = state.sourceSettings
    val sources = remember(state.enabledSources, query, state.exploreFilter, settings, state.sourceNsfwOverrides) {
        settings.sorted(state.enabledSources.filter {
            (it.displayName.contains(query, true) || it.source.locale.contains(query, true)) &&
                state.exploreFilter.accepts(it) &&
                !(settings.hidesNsfw(DesktopNsfwTarget.SOURCES) && it.isNsfwSource(state.sourceNsfwOverrides))
        })
    }
    val groups = remember(sources, settings) { settings.groups(sources) }
    val metrics = sourceQuickAccessMetrics(1.15f)
    Column(Modifier.fillMaxSize().testTag("source-pane"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            org.skepsun.kototoro.core.ui.topbar.TopBarTitleBlock(desktopText("nav.browse"))
            DesktopSearchField(query, { query = it }, desktopText("sourceGrid.search"), modifier = Modifier.weight(1f),
                enabled = enabled, tag = "source-grid-query")
            DesktopSourceFilterControls(
                state.exploreFilter, state.enabledSources, controller::exploreFilter, enabled, "explore",
            )
            OutlinedButton({ controller.showExtensions() }, enabled = enabled,
                modifier = Modifier.testTag("source-grid-extensions")) { Text(desktopText("sourceGrid.extensions")) }
        }
        Text(desktopText("sourceGrid.sources", sources.size), fontWeight = FontWeight.SemiBold)
        if (state.enabledSources.isEmpty()) {
            Text(desktopText("sourceGrid.empty"), color = Muted)
        }
        if (settings.grid) {
            LazyVerticalGrid(GridCells.Adaptive(metrics.minCardWidth),
                modifier = Modifier.weight(1f).testTag("source-list"),
                horizontalArrangement = Arrangement.spacedBy(metrics.gridSpacing),
                verticalArrangement = Arrangement.spacedBy(metrics.gridSpacing)) {
                groups.forEach { (language, entries) ->
                    if (language.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "header:$language") {
                        SourceGroupHeader(language)
                    }
                    items(entries, key = { it.source.name }) { source ->
                        TabletSourceTile(source.displayName, metrics.cardHeight + 18.dp, metrics.iconContainerSize,
                            metrics.iconSize, metrics.titleTextSize,
                            onClick = { controller.selectSource(source) },
                            modifier = Modifier.testTag("source:${source.source.name}"), enabled = enabled,
                            cardBackground = Color.Transparent,
                            supportingText = source.desktopSupportingText(),
                            icon = { iconModifier ->
                                Box(iconModifier, contentAlignment = Alignment.Center) {
                                    Text(source.displayName.take(1), fontSize = 48.sp, color = Accent)
                                }
                            },
                        )
                    }
                }
            }
        } else {
            // Android's list mode: one source per row instead of a tile grid; the same tap target and tags.
            LazyColumn(Modifier.weight(1f).testTag("source-list-rows"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { (language, entries) ->
                    if (language.isNotEmpty()) item(key = "header:$language") { SourceGroupHeader(language) }
                    items(entries, key = { it.source.name }) { source ->
                        Surface(color = Color.Transparent, shape = RoundedCornerShape(12.dp)) {
                            Row(Modifier.fillMaxWidth().testTag("source:${source.source.name}")
                                .then(if (enabled) Modifier.clickable { controller.selectSource(source) }
                                else Modifier).padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                    Text(source.displayName.take(1), fontSize = 20.sp, color = Accent)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(source.displayName, maxLines = 1, fontWeight = FontWeight.SemiBold)
                                    Text(source.desktopSupportingText(), fontSize = 12.sp, color = Muted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceGroupHeader(language: String) {
    Text(language, fontSize = 12.sp, color = Muted, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.testTag("source-group:$language").padding(top = 8.dp, bottom = 4.dp))
}

private fun SourceListing.desktopSupportingText(): String =
    "${DesktopSourceLabels.ecosystem(ecosystem)} · " +
        "${source.locale.ifBlank { desktopText("common.multilingual") }} · " +
        DesktopSourceLabels.contentType(source.contentType)
