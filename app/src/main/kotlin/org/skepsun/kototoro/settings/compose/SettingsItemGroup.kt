package org.skepsun.kototoro.settings.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalBackgroundStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled

internal enum class SettingsGroupItemPosition {
    SINGLE,
    FIRST,
    MIDDLE,
    LAST,
}

internal fun resolveSettingsGroupItemPosition(index: Int, total: Int): SettingsGroupItemPosition {
    require(total > 0) { "A settings group must contain at least one item" }
    require(index in 0 until total) { "Item index $index is outside a group of $total items" }
    return when {
        total == 1 -> SettingsGroupItemPosition.SINGLE
        index == 0 -> SettingsGroupItemPosition.FIRST
        index == total - 1 -> SettingsGroupItemPosition.LAST
        else -> SettingsGroupItemPosition.MIDDLE
    }
}

class SettingsItemGroupScope internal constructor() {
    internal val items = mutableListOf<@Composable () -> Unit>()

    fun item(content: @Composable () -> Unit) {
        items += content
    }
}

// Keep the builder non-composable so state changes invalidate and rebuild the complete item structure.
@Composable
fun SettingsPreferenceGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: SettingsItemGroupScope.() -> Unit,
) {
    val scope = SettingsItemGroupScope()
    scope.content()
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        if (title.isNotBlank()) {
            androidx.compose.material3.Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = settingsSectionLabelColor(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        SettingsItemGroup(itemCount = scope.items.size) { index ->
            scope.items[index]()
        }
    }
}

@Composable
fun SettingsItemGroup(
    itemCount: Int,
    modifier: Modifier = Modifier,
    itemContent: @Composable (index: Int) -> Unit,
) {
    require(itemCount >= 0) { "Settings item count cannot be negative" }
    if (itemCount == 0) return

    val tokens = LocalInterfaceStyleTokens.current
    val containerColor = settingsGroupItemContainerColor(
        interfaceStyle = LocalInterfaceStyle.current,
        backgroundStyle = LocalBackgroundStyle.current,
        surfaceContainerLow = MaterialTheme.colorScheme.surfaceContainerLow,
        surfaceContainer = MaterialTheme.colorScheme.surfaceContainer,
    )
    if (LocalInterfaceStyle.current == InterfaceStyle.IOS) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(tokens.settingsGroupOuterCornerRadius),
            color = containerColor,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                repeat(itemCount) { index ->
                    itemContent(index)
                    if (index != itemCount - 1) {
                        // iOS groups use one continuous surface with inset row separators.
                        SettingsGroupDivider(startPadding = 70.dp, endPadding = 0.dp)
                    }
                }
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(tokens.settingsItemGap),
        ) {
            repeat(itemCount) { index ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = settingsGroupItemShape(
                        position = resolveSettingsGroupItemPosition(index, itemCount),
                        outerCornerRadius = tokens.settingsGroupOuterCornerRadius,
                        innerCornerRadius = tokens.settingsGroupInnerCornerRadius,
                    ),
                    color = containerColor,
                ) {
                    itemContent(index)
                }
            }
        }
    }
}

private fun settingsGroupItemShape(
    position: SettingsGroupItemPosition,
    outerCornerRadius: Dp,
    innerCornerRadius: Dp,
): Shape = when (position) {
    SettingsGroupItemPosition.SINGLE -> RoundedCornerShape(outerCornerRadius)
    SettingsGroupItemPosition.FIRST -> RoundedCornerShape(
        topStart = outerCornerRadius,
        topEnd = outerCornerRadius,
        bottomStart = innerCornerRadius,
        bottomEnd = innerCornerRadius,
    )
    SettingsGroupItemPosition.MIDDLE -> RoundedCornerShape(innerCornerRadius)
    SettingsGroupItemPosition.LAST -> RoundedCornerShape(
        topStart = innerCornerRadius,
        topEnd = innerCornerRadius,
        bottomStart = outerCornerRadius,
        bottomEnd = outerCornerRadius,
    )
}

@Composable
fun SettingsCollapsiblePreferenceGroup(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    initiallyExpanded: Boolean = false,
    enabled: Boolean = true,
    content: SettingsItemGroupScope.() -> Unit,
) {
    val scope = SettingsItemGroupScope()
    scope.content()
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

    val tokens = LocalInterfaceStyleTokens.current
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val horizontalPadding = if (expressive || isIosStyle) 16.dp else 20.dp
    val containerColor = settingsGroupItemContainerColor(
        interfaceStyle = LocalInterfaceStyle.current,
        backgroundStyle = LocalBackgroundStyle.current,
        surfaceContainerLow = MaterialTheme.colorScheme.surfaceContainerLow,
        surfaceContainer = MaterialTheme.colorScheme.surfaceContainer,
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(tokens.settingsGroupOuterCornerRadius),
        color = containerColor,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { expanded = !expanded }
                    .heightIn(min = tokens.settingsItemMinHeight)
                    .padding(horizontal = horizontalPadding, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (summary != null) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsGroupDivider()
                    scope.items.forEachIndexed { index, itemContent ->
                        itemContent()
                        if (index != scope.items.lastIndex) {
                            SettingsGroupDivider()
                        }
                    }
                }
            }
        }
    }
}
