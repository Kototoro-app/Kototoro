package org.skepsun.kototoro.core.ui.adaptive

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class TabletNavigationDestination(val id: Int, val title: String)

/** The Android tablet rail. Hosts supply icons/badges and optional resume/header content. */
@Composable
fun TabletNavigationRail(
    destinations: List<TabletNavigationDestination>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
    onReselect: (Int) -> Unit,
    icon: @Composable (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    showLabels: Boolean = true,
    enabled: Boolean = true,
    selectedIconColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    header: (@Composable () -> Unit)? = null,
    resume: (@Composable () -> Unit)? = null,
    itemModifier: (Int) -> Modifier = { Modifier },
) {
    NavigationRail(
        containerColor = Color.Transparent,
        modifier = modifier.padding(contentPadding),
        windowInsets = WindowInsets(0),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxHeight().fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            if (header != null) item {
                header()
                Spacer(Modifier.height(8.dp))
            }
            if (resume != null) item {
                resume()
                Spacer(Modifier.height(8.dp))
            }
            items(destinations, key = { it.id }) { item ->
                val selected = selectedId == item.id
                NavigationRailItem(
                    selected = selected,
                    onClick = { if (selected) onReselect(item.id) else onSelect(item.id) },
                    icon = { icon(item.id, selected) },
                    label = { Text(item.title) },
                    alwaysShowLabel = showLabels,
                    enabled = enabled,
                    modifier = itemModifier(item.id),
                    colors = NavigationRailItemDefaults.colors(
                        indicatorColor = Color.Transparent,
                        selectedIconColor = selectedIconColor,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}
