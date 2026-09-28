package org.skepsun.kototoro.reader.ui.compose.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import kotlin.math.roundToInt

private val ChipShape = RoundedCornerShape(16.dp)
private val TileShape = RoundedCornerShape(16.dp)

/** Centered capsule tabs; [trailing] sits at the end of the row (e.g. a settings button). */
@Composable
fun ReaderPanelTabBar(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = currentReaderPanelColors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        if (trailing != null) Spacer(Modifier.width(40.dp))
        Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .clip(Capsule())
                    .background(colors.card)
                    .padding(3.dp),
            ) {
                labels.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    val background by animateColorAsState(
                        targetValue = if (selected) colors.selectedContainer else Color.Transparent,
                        label = "readerPanelTab",
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(Capsule())
                            .background(background)
                            .tvFocusable(shape = Capsule(), addFocusTarget = false)
                            .selectable(selected = selected, role = Role.Tab, onClick = { onSelected(index) })
                            .heightIn(min = 36.dp)
                            .padding(horizontal = 16.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) colors.content else colors.contentSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (trailing != null) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) { trailing() }
        }
    }
}

/**
 * Equal-width choice chips for 2–4 options. With an icon and more than three options the icon
 * sits above the label so labels are not squeezed.
 */
@Composable
fun ReaderChoiceChips(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable (Int) -> Unit)? = null,
    height: Dp = 56.dp,
    unselectedColor: Color? = null,
) {
    val colors = currentReaderPanelColors()
    val stacked = icon != null && options.size > 3
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Surface(
                shape = ChipShape,
                color = if (selected) colors.selectedContainer else unselectedColor ?: colors.card,
                contentColor = if (selected) colors.content else colors.contentSecondary,
                border = if (selected) BorderStroke(1.dp, colors.accent) else null,
                modifier = Modifier
                    .weight(1f)
                    .height(height)
                    .tvFocusable(shape = ChipShape, addFocusTarget = false)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelected(index) }),
            ) {
                val text: @Composable () -> Unit = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
                if (stacked) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp)) { icon?.invoke(index) }
                        text()
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        if (icon != null) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp)) { icon(index) }
                            Spacer(Modifier.width(8.dp))
                        }
                        text()
                    }
                }
            }
        }
    }
}

/** Icon-only capsule for five or more options; the selected option's name is shown below. */
@Composable
fun ReaderIconChoiceBar(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    icon: @Composable (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = currentReaderPanelColors()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .clip(Capsule())
                .background(colors.card)
                .padding(4.dp),
        ) {
            options.indices.forEach { index ->
                val selected = index == selectedIndex
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(width = 52.dp, height = 44.dp)
                        .clip(Capsule())
                        .background(if (selected) colors.selectedContainer else Color.Transparent)
                        .tvFocusable(shape = Capsule(), addFocusTarget = false)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelected(index) })
                        .semantics { contentDescription = options[index] },
                ) {
                    CompositionLocalProvider(
                        LocalContentColor provides if (selected) colors.content else colors.contentSecondary,
                    ) {
                        icon(index)
                    }
                }
            }
        }
        Text(
            text = options.getOrElse(selectedIndex) { "" },
            style = MaterialTheme.typography.labelMedium,
            color = colors.contentSecondary,
            maxLines = 1,
        )
    }
}

/** A quick action; [toggled] is non-null for on/off actions and is shown by the tile's highlight. */
@Immutable
data class ReaderQuickAction(
    val id: String,
    val iconResId: Int,
    val labelResId: Int,
    val toggled: Boolean? = null,
)

@Composable
fun ReaderQuickActionGrid(
    actions: List<ReaderQuickAction>,
    onClick: (ReaderQuickAction) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 4,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier.fillMaxWidth()) {
        actions.chunked(columns).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { action ->
                    ReaderQuickTile(action = action, onClick = { onClick(action) }, modifier = Modifier.weight(1f))
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun ReaderQuickTile(
    action: ReaderQuickAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = currentReaderPanelColors()
    val active = action.toggled == true
    val interaction = if (action.toggled != null) {
        Modifier.toggleable(value = active, role = Role.Switch, onValueChange = { onClick() })
    } else {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(TileShape)
            .tvFocusable(shape = TileShape, addFocusTarget = false)
            .then(interaction)
            .padding(vertical = 6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (active) colors.accent else colors.card),
        ) {
            Icon(
                painter = painterResource(action.iconResId),
                contentDescription = null,
                tint = if (active) colors.onAccent else colors.content,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = stringResource(action.labelResId),
            style = MaterialTheme.typography.labelSmall,
            color = colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** Small capsule on/off chip, e.g. "Follow system" beside the brightness slider. */
@Composable
fun ReaderPanelToggleChip(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = currentReaderPanelColors()
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(Capsule())
            .background(if (checked) colors.selectedContainer else colors.card)
            .tvFocusable(shape = Capsule(), addFocusTarget = false)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 32.dp)
            .padding(horizontal = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
            color = if (checked) colors.content else colors.contentSecondary,
            maxLines = 1,
        )
    }
}

/** [value] moved by one [step] in [direction] (±1), snapped onto the step grid and kept in [range]. */
internal fun steppedValue(
    value: Float,
    step: Float,
    direction: Int,
    range: ClosedFloatingPointRange<Float>,
): Float {
    val steps = ((value - range.start) / step).roundToInt() + direction
    return (range.start + steps * step).coerceIn(range)
}

/** Label, − value +; tapping the value opens an inline slider for large changes. */
@Composable
fun ReaderStepperRow(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
) {
    val colors = currentReaderPanelColors()
    var sliderVisible by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth().padding(contentPadding)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ReaderStepButton(
                symbol = "−",
                enabled = value > valueRange.start,
                onClick = { onValueChange(steppedValue(value, step, -1, valueRange)) },
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = colors.accent,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(min = 64.dp)
                    .clip(Capsule())
                    .clickable(role = Role.Button) { sliderVisible = !sliderVisible }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            ReaderStepButton(
                symbol = "+",
                enabled = value < valueRange.endInclusive,
                onClick = { onValueChange(steppedValue(value, step, 1, valueRange)) },
            )
        }
        if (sliderVisible) {
            Slider(
                value = value.coerceIn(valueRange),
                onValueChange = onValueChange,
                valueRange = valueRange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ReaderStepButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = currentReaderPanelColors()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(colors.selectedContainer)
            .tvFocusable(shape = CircleShape, addFocusTarget = false)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
    ) {
        Text(text = symbol, style = MaterialTheme.typography.titleMedium, color = colors.content)
    }
}

@Composable
fun ReaderSliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    steps: Int = 0,
    leadingIcon: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
) {
    val colors = currentReaderPanelColors()
    Column(modifier = modifier.fillMaxWidth().padding(contentPadding)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
        ) {
            leadingIcon?.let {
                Icon(
                    painter = painterResource(it),
                    contentDescription = null,
                    tint = colors.contentSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) colors.accent else colors.contentSecondary,
                maxLines = 1,
            )
            trailing?.invoke()
        }
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
