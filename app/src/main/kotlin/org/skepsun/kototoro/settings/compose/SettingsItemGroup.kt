package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens

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

internal fun settingsGroupItemContainerColor(
    interfaceStyle: InterfaceStyle,
    surfaceColor: Color,
): Color = if (interfaceStyle == InterfaceStyle.IOS) {
    surfaceColor.copy(alpha = SETTINGS_IOS_CONTAINER_ALPHA)
} else {
    surfaceColor
}

class SettingsItemGroupScope internal constructor() {
    internal val items = mutableListOf<@Composable () -> Unit>()

    fun item(content: @Composable () -> Unit) {
        items += content
    }
}

@Composable
fun SettingsPreferenceGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable SettingsItemGroupScope.() -> Unit,
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
                color = MaterialTheme.colorScheme.primary,
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
        surfaceColor = MaterialTheme.colorScheme.surfaceContainerLow,
    )
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

private const val SETTINGS_IOS_CONTAINER_ALPHA = 0.74f

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
