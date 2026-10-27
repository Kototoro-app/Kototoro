package org.skepsun.kototoro.core.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons
import kotlin.math.roundToInt

/**
 * The settings vocabulary Android's `SettingsPreferenceComponents` draws, shared with the Windows host.
 *
 * Android builds its settings screens from androidx `Preference` objects and Windows from the shared
 * `SourcePreferenceNode` tree, but both show the same thing: a labelled section holding a card of rows, each row a
 * title with an optional summary on the left and a control — a switch, or the current value and a chevron that opens a
 * dialog — on the right. Keeping the rhythm (16dp insets, 12dp row padding, one divider between rows) here means the
 * two hosts do not drift apart, and a third host (iOS) gets the same rows for free.
 */

/** Section label above a [SettingsGroup]; Android shows the same label for category titles and its default section. */
@Composable
fun SettingsSectionHeader(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
    )
}

/** The card holding one section's rows; hosts put a [SettingsRowDivider] between consecutive rows. */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content)
    }
}

@Composable
fun SettingsRowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .1f),
    )
}

/**
 * Toggle row. The whole row toggles the value (Android's `SettingsSwitchPreference` behaves the same), so the switch
 * itself is a display-only leaf and keeps a single accessibility node with a `Role.Switch` state.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    leadingIcon: Painter? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (leadingIcon != null) {
            Icon(
                painter = leadingIcon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        SettingsRowText(title, summary, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * Row whose control lives in a dialog or runs an action: the current value sits in front of a chevron on the right.
 * Android's choice, multi-choice, text and action preferences all share this layout.
 *
 * [leadingIcon] is the platform's icon slot: Android hands in `canvasPainter(R.drawable.ic_…)`, the Windows host hands
 * in a bundled vector, and neither needs the shared module to know about resources.
 */
@Composable
fun SettingsValueRow(
    title: String,
    summary: String?,
    value: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    leadingIcon: Painter? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leadingIcon != null) {
            Icon(
                painter = leadingIcon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        SettingsRowText(title, summary, Modifier.weight(1f))
        if (!value.isNullOrBlank()) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 260.dp),
            )
        }
        Icon(
            imageVector = ReaderPanelIcons.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) .6f else .25f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Slider row, Android's `SettingsSliderPreference`: the title and summary on top, the current value at the right, and
 * the track below them so a wide host window does not squeeze the slider into the chevron's slot. [valueText] renders
 * the number ("90%"), which keeps locale formatting with the host.
 *
 * Dragging only moves a local draft; [onValueChange] runs once when the drag ends, so a host whose preference store
 * writes to disk is not hit with one write per pixel. [value] and [valueRange] are integers stepped by [step]; hosts
 * that store a fraction convert on their side.
 */
@Composable
fun SettingsSliderRow(
    title: String,
    summary: String?,
    value: Int,
    valueRange: IntRange,
    valueText: (Int) -> String,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 1,
    testTag: String? = null,
    leadingIcon: Painter? = null,
) {
    val steps = ((valueRange.last - valueRange.first) / step - 1).coerceAtLeast(0)
    var draft by remember(value) { mutableStateOf(value.coerceIn(valueRange.first, valueRange.last)) }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (leadingIcon != null) {
                Icon(
                    painter = leadingIcon,
                    contentDescription = null,
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp),
                )
            }
            SettingsRowText(title, summary, Modifier.weight(1f))
            Text(
                text = valueText(draft),
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = draft.toFloat(),
            onValueChange = { raw ->
                val notches = (raw - valueRange.first).div(step).roundToInt()
                draft = (valueRange.first + notches * step).coerceIn(valueRange.first, valueRange.last)
            },
            onValueChangeFinished = { onValueChange(draft) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = steps,
            enabled = enabled,
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
        )
    }
}

/**
 * Android's `SettingsSplitSwitchPreference`: the title opens the screen behind the switch, the switch alone changes the
 * stored flag. Its reading-statistics row uses exactly this shape — the title opens the statistics screen, the switch
 * decides whether the reader records anything at all — so the two halves must stay separately clickable, each with its
 * own test tag.
 */
@Composable
fun SettingsSplitSwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    switchTag: String? = null,
    leadingIcon: Painter? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (leadingIcon != null) {
                Icon(
                    painter = leadingIcon,
                    contentDescription = null,
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp),
                )
            }
            SettingsRowText(title, summary, Modifier.weight(1f))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = if (switchTag != null) Modifier.testTag(switchTag) else Modifier,
        )
    }
}

/** Read-only row: what a source reports about itself, or a control Windows cannot draw. */
@Composable
fun SettingsInfoRow(title: String, summary: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsRowText(title, summary, Modifier.weight(1f))
    }
}

/**
 * Sub-label inside a section: Android draws one for every preference group nested in another group
 * (`SettingsGroupLabel`), so a source whose settings nest several groups keeps its structure instead of collapsing
 * into one flat card of rows.
 */
@Composable
fun SettingsGroupLabelRow(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/**
 * Row whose control is not a switch or a chevron — two action buttons for an installed extension, for instance. The
 * title and summary keep the same typography and insets as every other row, so a section can mix row kinds.
 */
@Composable
fun SettingsActionRow(
    title: String,
    summary: String?,
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 12.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingsRowText(title, summary, Modifier.weight(1f))
        content()
    }
}

@Composable
private fun SettingsRowText(title: String, summary: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!summary.isNullOrBlank()) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
