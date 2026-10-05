package org.skepsun.kototoro.reader.ui.colorfilter

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.reader.domain.ReaderColorFilter
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionDivider
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionGroup
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionSwitchRow

/** The colour-correction labels (Android's `color_correction`, `invert_colors`, ... strings). */
@Immutable
data class ReaderColorCorrectionLabels(
    val title: String,
    val reset: String,
    val invert: String,
    val grayscale: String,
    val brightness: String,
    val contrast: String,
    val bookEffect: String,
)

/** Invert, grayscale, brightness and contrast (−1..1, shown as 0–200%) and the book effect, in one option group. */
@Composable
fun ReaderColorCorrectionControls(
    colorFilter: ReaderColorFilter?,
    isLoading: Boolean,
    onColorFilterChange: (ReaderColorFilter?) -> Unit,
    onReset: () -> Unit,
    labels: ReaderColorCorrectionLabels,
    modifier: Modifier = Modifier,
) {
    ReaderOptionGroup(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 12.dp, end = 4.dp),
        ) {
            Text(
                text = labels.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReset, enabled = !isLoading, modifier = Modifier.testTag("reader-color-reset")) {
                Text(labels.reset)
            }
        }
        ReaderOptionDivider()
        ReaderOptionSwitchRow(
            label = labels.invert,
            checked = colorFilter?.isInverted == true,
            enabled = !isLoading,
            onCheckedChange = {
                onColorFilterChange(colorFilter.update { copy(isInverted = it) })
            },
            modifier = Modifier.testTag("reader-color-invert"),
        )
        ReaderOptionDivider()
        ReaderOptionSwitchRow(
            label = labels.grayscale,
            checked = colorFilter?.isGrayscale == true,
            enabled = !isLoading,
            onCheckedChange = {
                onColorFilterChange(colorFilter.update { copy(isGrayscale = it) })
            },
            modifier = Modifier.testTag("reader-color-grayscale"),
        )
        ReaderOptionDivider()
        ColorFilterSlider(
            label = labels.brightness,
            value = colorFilter?.brightness ?: 0f,
            enabled = !isLoading,
            onValueChange = {
                onColorFilterChange(colorFilter.update { copy(brightness = it) })
            },
            modifier = Modifier.testTag("reader-color-brightness"),
        )
        ReaderOptionDivider()
        ColorFilterSlider(
            label = labels.contrast,
            value = colorFilter?.contrast ?: 0f,
            enabled = !isLoading,
            onValueChange = {
                onColorFilterChange(colorFilter.update { copy(contrast = it) })
            },
            modifier = Modifier.testTag("reader-color-contrast"),
        )
        ReaderOptionDivider()
        ReaderOptionSwitchRow(
            label = labels.bookEffect,
            checked = colorFilter?.isBookBackground == true,
            enabled = !isLoading,
            onCheckedChange = {
                onColorFilterChange(colorFilter.update { copy(isBookBackground = it) })
            },
            modifier = Modifier.testTag("reader-color-book"),
        )
    }
}

@Composable
private fun ColorFilterSlider(
    label: String,
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    modifier: Modifier,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${((value + 1f) * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(-1f, 1f),
            onValueChange = onValueChange,
            valueRange = -1f..1f,
            enabled = enabled,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

private inline fun ReaderColorFilter?.update(
    transform: ReaderColorFilter.() -> ReaderColorFilter,
): ReaderColorFilter? = (this ?: ReaderColorFilter.EMPTY).transform().takeUnless { it.isEmpty }
