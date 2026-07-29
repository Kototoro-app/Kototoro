package org.skepsun.kototoro.reader.ui.colorfilter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.skepsun.kototoro.R
import org.skepsun.kototoro.reader.domain.ReaderColorFilter
import org.skepsun.kototoro.reader.ui.compose.toComposeColorFilter

@Composable
internal fun ReaderColorCorrectionEditor(
    originalPreviewModel: Any?,
    processedPreviewModel: Any? = originalPreviewModel,
    colorFilter: ReaderColorFilter?,
    isLoading: Boolean,
    onColorFilterChange: (ReaderColorFilter?) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        ReaderImageComparisonPreview(
            originalPreviewModel = originalPreviewModel,
            processedPreviewModel = processedPreviewModel,
            colorFilter = colorFilter,
            isLoading = isLoading,
        )
        ColorFilterToggle(
            label = stringResource(R.string.invert_colors),
            checked = colorFilter?.isInverted == true,
            enabled = !isLoading,
            onCheckedChange = {
                onColorFilterChange(colorFilter.update { copy(isInverted = it) })
            },
        )
        ColorFilterToggle(
            label = stringResource(R.string.grayscale),
            checked = colorFilter?.isGrayscale == true,
            enabled = !isLoading,
            onCheckedChange = {
                onColorFilterChange(colorFilter.update { copy(isGrayscale = it) })
            },
        )
        ColorFilterSlider(
            label = stringResource(R.string.brightness),
            value = colorFilter?.brightness ?: 0f,
            enabled = !isLoading,
            onValueChange = {
                onColorFilterChange(colorFilter.update { copy(brightness = it) })
            },
        )
        ColorFilterSlider(
            label = stringResource(R.string.contrast),
            value = colorFilter?.contrast ?: 0f,
            enabled = !isLoading,
            onValueChange = {
                onColorFilterChange(colorFilter.update { copy(contrast = it) })
            },
        )
        ColorFilterToggle(
            label = stringResource(R.string.book_effect),
            checked = colorFilter?.isBookBackground == true,
            enabled = !isLoading,
            onCheckedChange = {
                onColorFilterChange(colorFilter.update { copy(isBookBackground = it) })
            },
        )
        OutlinedButton(
            onClick = onReset,
            enabled = !isLoading,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(R.string.reset))
        }
    }
}

@Composable
internal fun ReaderImageComparisonPreview(
    originalPreviewModel: Any?,
    processedPreviewModel: Any?,
    colorFilter: ReaderColorFilter?,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ComparisonImage(
                model = originalPreviewModel,
                colorFilter = null,
                modifier = Modifier.weight(1f),
            )
            ComparisonImage(
                model = processedPreviewModel,
                colorFilter = colorFilter,
                modifier = Modifier.weight(1f),
            )
        }
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun ComparisonImage(
    model: Any?,
    colorFilter: ReaderColorFilter?,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.heightIn(min = 150.dp, max = 280.dp),
    ) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = colorFilter.toComposeColorFilter(),
            modifier = Modifier.fillMaxSize().clip(shape),
        )
    }
}

@Composable
private fun ColorFilterToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun ColorFilterSlider(
    label: String,
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Text(
            text = "$label: ${((value + 1f) * 100).toInt()}%",
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = value.coerceIn(-1f, 1f),
            onValueChange = onValueChange,
            valueRange = -1f..1f,
            enabled = enabled,
        )
    }
}

private inline fun ReaderColorFilter?.update(
    transform: ReaderColorFilter.() -> ReaderColorFilter,
): ReaderColorFilter? = (this ?: ReaderColorFilter.EMPTY).transform().takeUnless { it.isEmpty }
