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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.KototoroLoadingIndicator
import org.skepsun.kototoro.reader.domain.ReaderColorFilter
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionDivider
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionGroup
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionSwitchRow
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
        ReaderOptionGroup {
            ReaderImageComparisonPreview(
                originalPreviewModel = originalPreviewModel,
                processedPreviewModel = processedPreviewModel,
                colorFilter = colorFilter,
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
    ReaderColorCorrectionControls(
        colorFilter = colorFilter,
        isLoading = isLoading,
        onColorFilterChange = onColorFilterChange,
        onReset = onReset,
        labels = rememberReaderColorCorrectionLabels(),
        modifier = modifier,
    )
}

/** Android's strings for the shared colour-correction controls. */
@Composable
internal fun rememberReaderColorCorrectionLabels() = ReaderColorCorrectionLabels(
    title = stringResource(R.string.color_correction),
    reset = stringResource(R.string.reset),
    invert = stringResource(R.string.invert_colors),
    grayscale = stringResource(R.string.grayscale),
    brightness = stringResource(R.string.brightness),
    contrast = stringResource(R.string.contrast),
    bookEffect = stringResource(R.string.book_effect),
)
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
                label = stringResource(R.string.image_post_processing_original),
                colorFilter = null,
                modifier = Modifier.weight(1f),
            )
            ComparisonImage(
                model = processedPreviewModel,
                label = stringResource(R.string.image_post_processing_result),
                colorFilter = colorFilter,
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
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.heightIn(min = 150.dp, max = 280.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = colorFilter.toComposeColorFilter(),
                modifier = Modifier
                    .fillMaxSize()
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
