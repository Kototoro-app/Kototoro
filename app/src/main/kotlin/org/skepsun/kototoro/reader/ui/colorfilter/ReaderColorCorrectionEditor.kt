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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Precision
import coil3.size.Size
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.ReaderImageScalingQuality
import org.skepsun.kototoro.core.ui.compose.KototoroLoadingIndicator
import org.skepsun.kototoro.reader.domain.ReaderColorFilter
import org.skepsun.kototoro.reader.ui.compose.ReaderLanczosTransformation
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionDivider
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionGroup
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionSwitchRow
import org.skepsun.kototoro.reader.ui.compose.toComposeColorFilter
import org.skepsun.kototoro.reader.ui.compose.toComposeFilterQuality

@Composable
internal fun ReaderColorCorrectionEditor(
    originalPreviewModel: Any?,
    processedPreviewModel: Any? = originalPreviewModel,
    colorFilter: ReaderColorFilter?,
    imageScalingQuality: ReaderImageScalingQuality = ReaderImageScalingQuality.DEFAULT,
    isLoading: Boolean,
    onColorFilterChange: (ReaderColorFilter?) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        ReaderOptionGroup {
            ReaderImageComparisonPreview(
                originalPreviewModel = originalPreviewModel,
                processedPreviewModel = processedPreviewModel,
                colorFilter = colorFilter,
                imageScalingQuality = imageScalingQuality,
                isLoading = isLoading,
                modifier = Modifier.padding(8.dp),
            )
        }
        ReaderColorCorrectionControls(
            colorFilter = colorFilter,
            isLoading = isLoading,
            onColorFilterChange = onColorFilterChange,
            onReset = onReset,
        )
    }
}

@Composable
internal fun ReaderColorCorrectionControls(
    colorFilter: ReaderColorFilter?,
    isLoading: Boolean,
    onColorFilterChange: (ReaderColorFilter?) -> Unit,
    onReset: () -> Unit,
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
                text = stringResource(R.string.color_correction),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReset, enabled = !isLoading) {
                Text(stringResource(R.string.reset))
            }
        }
        ReaderOptionDivider()
        ReaderOptionSwitchRow(
            label = stringResource(R.string.invert_colors),
            checked = colorFilter?.isInverted == true,
            enabled = !isLoading,
            onCheckedChange = {
                onColorFilterChange(colorFilter.update { copy(isInverted = it) })
            },
        )
        ReaderOptionDivider()
        ReaderOptionSwitchRow(
            label = stringResource(R.string.grayscale),
            checked = colorFilter?.isGrayscale == true,
            enabled = !isLoading,
            onCheckedChange = {
                onColorFilterChange(colorFilter.update { copy(isGrayscale = it) })
            },
        )
        ReaderOptionDivider()
        ColorFilterSlider(
            label = stringResource(R.string.brightness),
            value = colorFilter?.brightness ?: 0f,
            enabled = !isLoading,
            onValueChange = {
                onColorFilterChange(colorFilter.update { copy(brightness = it) })
            },
        )
        ReaderOptionDivider()
        ColorFilterSlider(
            label = stringResource(R.string.contrast),
            value = colorFilter?.contrast ?: 0f,
            enabled = !isLoading,
            onValueChange = {
                onColorFilterChange(colorFilter.update { copy(contrast = it) })
            },
        )
        ReaderOptionDivider()
        ReaderOptionSwitchRow(
            label = stringResource(R.string.book_effect),
            checked = colorFilter?.isBookBackground == true,
            enabled = !isLoading,
            onCheckedChange = {
                onColorFilterChange(colorFilter.update { copy(isBookBackground = it) })
            },
        )
    }
}

@Composable
internal fun ReaderImageComparisonPreview(
    originalPreviewModel: Any?,
    processedPreviewModel: Any?,
    colorFilter: ReaderColorFilter?,
    imageScalingQuality: ReaderImageScalingQuality = ReaderImageScalingQuality.DEFAULT,
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
                label = stringResource(R.string.image_post_processing_original),
                colorFilter = null,
                imageScalingQuality = ReaderImageScalingQuality.DEFAULT,
                modifier = Modifier.weight(1f),
            )
            ComparisonImage(
                model = processedPreviewModel,
                label = stringResource(R.string.image_post_processing_result),
                colorFilter = colorFilter,
                imageScalingQuality = imageScalingQuality,
                modifier = Modifier.weight(1f),
            )
        }
        if (isLoading) {
            KototoroLoadingIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun ComparisonImage(
    model: Any?,
    label: String,
    colorFilter: ReaderColorFilter?,
    imageScalingQuality: ReaderImageScalingQuality,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val context = LocalContext.current
    var previewWidth by remember { mutableIntStateOf(0) }
    var previewHeight by remember { mutableIntStateOf(0) }
    val previewModel = remember(model, imageScalingQuality, previewWidth, previewHeight) {
        if (model != null && previewWidth > 0 && previewHeight > 0) {
            ImageRequest.Builder(context)
                .data(model)
                .size(Size(previewWidth * PREVIEW_SOURCE_SCALE, previewHeight * PREVIEW_SOURCE_SCALE))
                .precision(Precision.INEXACT)
                .apply {
                    if (imageScalingQuality == ReaderImageScalingQuality.LANCZOS) {
                        transformations(ReaderLanczosTransformation(previewWidth, previewHeight))
                    }
                }
                .build()
        } else {
            model
        }
    }
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.heightIn(min = 150.dp, max = 280.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = previewModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = colorFilter.toComposeColorFilter(),
                filterQuality = imageScalingQuality.toComposeFilterQuality(),
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged {
                        previewWidth = it.width
                        previewHeight = it.height
                    }
                    .clip(shape),
            )
            Surface(
                shape = RoundedCornerShape(7.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private const val PREVIEW_SOURCE_SCALE = 2

@Composable
private fun ColorFilterSlider(
    label: String,
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
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
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private inline fun ReaderColorFilter?.update(
    transform: ReaderColorFilter.() -> ReaderColorFilter,
): ReaderColorFilter? = (this ?: ReaderColorFilter.EMPTY).transform().takeUnless { it.isEmpty }
