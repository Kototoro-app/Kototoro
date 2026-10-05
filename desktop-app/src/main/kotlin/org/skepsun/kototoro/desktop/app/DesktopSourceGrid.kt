package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.ui.source.TabletSourceTile
import org.skepsun.kototoro.explore.ui.compose.sourceQuickAccessMetrics

/** The same source tile as Android quick access, with desktop source selection and icon fallback. */
@Composable
internal fun DesktopSourceGrid(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    var query by remember { mutableStateOf("") }
    val sources = remember(state.sources, query, state.exploreFilter) {
        state.sources.filter {
            (it.displayName.contains(query, true) || it.source.locale.contains(query, true)) && state.exploreFilter.accepts(it)
        }
    }
    val metrics = sourceQuickAccessMetrics(1.15f)
    Column(Modifier.fillMaxSize().testTag("source-pane"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            org.skepsun.kototoro.core.ui.topbar.TopBarTitleBlock("浏览")
            DesktopSearchField(query, { query = it }, "搜索内容源…", modifier = Modifier.weight(1f),
                enabled = enabled, tag = "source-grid-query")
            DesktopSourceFilterControls(state.exploreFilter, state.sources, controller::exploreFilter, enabled, "explore")
            OutlinedButton({ controller.showExtensions() }, enabled = enabled,
                modifier = Modifier.testTag("source-grid-extensions")) { Text("扩展管理") }
        }
        Text("内容源 · ${sources.size}", fontWeight = FontWeight.SemiBold)
        if (state.sources.isEmpty()) {
            Text("安装或导入扩展后，内容源会显示在这里。", color = Muted)
        }
        LazyVerticalGrid(GridCells.Adaptive(metrics.minCardWidth), modifier = Modifier.weight(1f).testTag("source-list"),
            horizontalArrangement = Arrangement.spacedBy(metrics.gridSpacing),
            verticalArrangement = Arrangement.spacedBy(metrics.gridSpacing)) {
            items(sources, key = { it.source.name }) { source ->
                TabletSourceTile(source.displayName, metrics.cardHeight + 18.dp, metrics.iconContainerSize,
                    metrics.iconSize, metrics.titleTextSize,
                    onClick = { controller.selectSource(source) },
                    modifier = Modifier.testTag("source:${source.source.name}"), enabled = enabled,
                    cardBackground = Color.Transparent,
                    supportingText = "${DesktopSourceLabels.ecosystem(source.ecosystem)} · " +
                        "${source.source.locale.ifBlank { "多语言" }} · " +
                        DesktopSourceLabels.contentType(source.source.contentType),
                    icon = { iconModifier ->
                        Box(iconModifier, contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Text(source.displayName.take(1),
                                fontSize = 48.sp, color = Accent)
                        }
                    },
                )
            }
        }
    }
}
