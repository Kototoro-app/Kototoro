package org.skepsun.kototoro.list.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.ui.compose.KototoroSlider

/** The grid size range of Android's display options, in percent of the default card width. */
val GridSizeRange: IntRange = 50..200

/** Slider stops between the ends of [GridSizeRange], one every 5%. */
val GridSizeSliderSteps: Int = (GridSizeRange.last - GridSizeRange.first) / 5 - 1

/**
 * Android's display-options mode picker (DisplayOptionsSheet): one connected button group across the sheet. The
 * selected mode is filled, morphs to a capsule and shows its label; the others show only their icon (the label
 * stays their content description) and share the remaining width. Hosts supply the labels and icons from their
 * resources, may offer only the [modes] they can draw, and Android adds its TV focus through [chipModifier].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DisplayModeOptions(
    currentListMode: ListMode,
    onListModeSelected: (ListMode) -> Unit,
    label: (ListMode) -> String,
    icon: @Composable (ListMode) -> Painter,
    modifier: Modifier = Modifier,
    modes: List<ListMode> = ListMode.entries,
    chipModifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        modes.forEachIndexed { index, mode ->
            val selected = currentListMode == mode
            val text = label(mode)
            ToggleButton(
                checked = selected,
                onCheckedChange = { onListModeSelected(mode) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = displayOptionToggleColors(),
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier
                    // The selected button takes the width its label needs; the icon-only ones split the rest.
                    .then(if (selected) Modifier.widthIn(max = SelectedModeMaxWidth) else Modifier.weight(1f))
                    .semantics { contentDescription = text }
                    .then(chipModifier)
                    .testTag("display-mode:${mode.name}"),
            ) {
                Icon(icon(mode), contentDescription = null, modifier = Modifier.size(ToggleButtonDefaults.IconSize))
                AnimatedVisibility(
                    visible = selected,
                    enter = expandHorizontally() + fadeIn(),
                    exit = shrinkHorizontally() + fadeOut(),
                ) {
                    Text(
                        text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = ToggleButtonDefaults.IconSpacing),
                    )
                }
            }
        }
    }
}

private val SelectedModeMaxWidth = 160.dp

/**
 * Android's grid size slider: 50–200 % in 5 % steps on one line — the title, the slider, and the value with an optional
 * column hint stacked on the right. The steps still snap but draw no tick marks (thirty dots read as noise). Android
 * adds its D-pad handling through [sliderModifier], which sees the live value and the commit callback.
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
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        val range = GridSizeRange.first.toFloat()..GridSizeRange.last.toFloat()
        val commit: (Float) -> Unit = {
            sliderValue = it
            onValueChange(it.toInt().coerceIn(GridSizeRange))
        }
        KototoroSlider(value = sliderValue, onValueChange = commit, valueRange = range, steps = GridSizeSliderSteps,
            colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
            modifier = Modifier.weight(1f).then(sliderModifier(sliderValue, commit)).testTag("grid-size-slider"))
        // Fixed width so the slider does not jump as the value gains or loses a digit.
        Column(Modifier.widthIn(min = 44.dp), horizontalAlignment = Alignment.End) {
            Text("$currentValue%", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface)
            if (columnsHint != null) Text(columnsHint, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/**
 * Colours of the display-options connected button groups: a light tint for the unchecked buttons (the default
 * surfaceContainer disappears into the glass sheets) and Material's filled primary for the checked one.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun displayOptionToggleColors(): ToggleButtonColors = ToggleButtonDefaults.toggleButtonColors(
    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
)
