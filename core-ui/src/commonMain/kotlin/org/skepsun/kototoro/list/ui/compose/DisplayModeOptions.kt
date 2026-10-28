package org.skepsun.kototoro.list.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.ui.compose.KototoroSlider

/** The grid size range of Android's display options, in percent of the default card width. */
val GridSizeRange: IntRange = 50..150

/**
 * Android's display-options mode chips (DisplayOptionsSheet), two per row. Hosts supply the labels and icons from their
 * resources, may offer only the [modes] they can draw, and Android adds its TV focus through [chipModifier].
 */
@Composable
fun DisplayModeOptions(
    currentListMode: ListMode,
    onListModeSelected: (ListMode) -> Unit,
    label: (ListMode) -> String,
    icon: @Composable (ListMode) -> Painter,
    checkIcon: Painter,
    modifier: Modifier = Modifier,
    modes: List<ListMode> = ListMode.entries,
    chipModifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        modes.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { mode ->
                    val selected = currentListMode == mode
                    AssistChip(
                        onClick = { onListModeSelected(mode) },
                        label = { Text(label(mode), maxLines = 1) },
                        leadingIcon = { Icon(if (selected) checkIcon else icon(mode), contentDescription = null) },
                        modifier = Modifier.weight(1f).then(chipModifier).testTag("display-mode:${mode.name}"),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                            } else Color.Transparent,
                            labelColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                            leadingIconContentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
                // An odd last row keeps its chip at half width, as Android's two-column layout does.
                if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Android's grid size slider: 50–150 % in 5 % steps, the value and an optional column hint on the right. Android adds
 * its D-pad handling through [sliderModifier], which sees the live value and the commit callback.
 */
@Composable
fun GridSizeOption(
    title: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    columnsHint: String? = null,
    sliderModifier: @Composable (Float, (Float) -> Unit) -> Modifier = { _, _ -> Modifier },
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val currentValue = sliderValue.toInt().coerceIn(GridSizeRange)
    Column(modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Column(horizontalAlignment = Alignment.End) {
                Text("$currentValue%", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (columnsHint != null) Text(columnsHint, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f))
            }
        }
        val range = GridSizeRange.first.toFloat()..GridSizeRange.last.toFloat()
        val commit: (Float) -> Unit = {
            sliderValue = it
            onValueChange(it.toInt().coerceIn(GridSizeRange))
        }
        KototoroSlider(value = sliderValue, onValueChange = commit, valueRange = range, steps = 19,
            modifier = sliderModifier(sliderValue, commit).testTag("grid-size-slider"))
    }
}
