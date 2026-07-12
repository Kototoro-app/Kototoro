package org.skepsun.kototoro.core.ui.compose

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val KototoroSliderTrackHeight = 8.dp
private val KototoroSliderThumbContainerWidth = 24.dp
private val KototoroSliderThumbContainerHeight = 32.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KototoroSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
) {
    val interactionSource = remember { MutableInteractionSource() }
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interactionSource,
        thumb = {
            KototoroSliderThumb(
                interactionSource = interactionSource,
                color = if (enabled) colors.thumbColor else colors.disabledThumbColor,
            )
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                modifier = Modifier.height(KototoroSliderTrackHeight),
                enabled = enabled,
                colors = colors,
                thumbTrackGapSize = 0.dp,
                trackInsideCornerSize = KototoroSliderTrackHeight / 2,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KototoroRangeSlider(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
) {
    val startInteractionSource = remember { MutableInteractionSource() }
    val endInteractionSource = remember { MutableInteractionSource() }
    RangeSlider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        startInteractionSource = startInteractionSource,
        endInteractionSource = endInteractionSource,
        startThumb = {
            KototoroSliderThumb(
                interactionSource = startInteractionSource,
                color = if (enabled) colors.thumbColor else colors.disabledThumbColor,
            )
        },
        endThumb = {
            KototoroSliderThumb(
                interactionSource = endInteractionSource,
                color = if (enabled) colors.thumbColor else colors.disabledThumbColor,
            )
        },
        track = { state ->
            SliderDefaults.Track(
                rangeSliderState = state,
                modifier = Modifier.height(KototoroSliderTrackHeight),
                enabled = enabled,
                colors = colors,
                thumbTrackGapSize = 0.dp,
                trackInsideCornerSize = KototoroSliderTrackHeight / 2,
            )
        },
    )
}

@Composable
private fun KototoroSliderThumb(
    interactionSource: MutableInteractionSource,
    color: Color,
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val dragged by interactionSource.collectIsDraggedAsState()
    val active = pressed || dragged
    val width by animateDpAsState(
        targetValue = if (active) 24.dp else 6.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "sliderThumbWidth",
    )
    val height by animateDpAsState(
        targetValue = if (active) 32.dp else 24.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "sliderThumbHeight",
    )
    Box(
        modifier = Modifier.size(KototoroSliderThumbContainerWidth, KototoroSliderThumbContainerHeight),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(width)
                .height(height)
                .background(color.copy(alpha = if (active) 0.88f else 1f), CircleShape)
                .border(1.dp, Color.White.copy(alpha = if (active) 0.28f else 0f), CircleShape),
        )
    }
}
