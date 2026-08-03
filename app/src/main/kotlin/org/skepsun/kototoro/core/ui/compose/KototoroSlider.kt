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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStylePolicy
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.prefs.InterfaceStyle

val LocalLiquidGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }
val LocalLiquidGlassLayerBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

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
    compactThumb: Boolean = false,
) {
    val tokens = LocalInterfaceStyleTokens.current
    val stylePolicy = LocalInterfaceStylePolicy.current
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val backdrop = LocalLiquidGlassBackdrop.current
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
                isIosStyle = isIosStyle,
                useExpandedThumb = stylePolicy.useExpandedTouchTargets,
                compact = compactThumb,
                backdrop = backdrop,
            )
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                modifier = Modifier.height(tokens.sliderTrackHeight),
                enabled = enabled,
                colors = colors,
                thumbTrackGapSize = 0.dp,
                trackInsideCornerSize = tokens.sliderTrackHeight / 2,
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
    val tokens = LocalInterfaceStyleTokens.current
    val stylePolicy = LocalInterfaceStylePolicy.current
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val backdrop = LocalLiquidGlassBackdrop.current
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
                isIosStyle = isIosStyle,
                useExpandedThumb = stylePolicy.useExpandedTouchTargets,
                compact = false,
                backdrop = backdrop,
            )
        },
        endThumb = {
            KototoroSliderThumb(
                interactionSource = endInteractionSource,
                color = if (enabled) colors.thumbColor else colors.disabledThumbColor,
                isIosStyle = isIosStyle,
                useExpandedThumb = stylePolicy.useExpandedTouchTargets,
                compact = false,
                backdrop = backdrop,
            )
        },
        track = { state ->
            SliderDefaults.Track(
                rangeSliderState = state,
                modifier = Modifier.height(tokens.sliderTrackHeight),
                enabled = enabled,
                colors = colors,
                thumbTrackGapSize = 0.dp,
                trackInsideCornerSize = tokens.sliderTrackHeight / 2,
            )
        },
    )
}

@Composable
private fun KototoroSliderThumb(
    interactionSource: MutableInteractionSource,
    color: Color,
    isIosStyle: Boolean,
    useExpandedThumb: Boolean,
    compact: Boolean,
    backdrop: Backdrop?,
) {
    val tokens = LocalInterfaceStyleTokens.current
    val pressed by interactionSource.collectIsPressedAsState()
    val dragged by interactionSource.collectIsDraggedAsState()
    val active = pressed || dragged
    val visualSize = if (compact) {
        if (isIosStyle) 16.dp else 20.dp
    } else {
        tokens.sliderThumbSize
    }
    val width by animateDpAsState(
        targetValue = if (active) {
            if (compact) {
                if (isIosStyle) 20.dp else 24.dp
            } else {
                tokens.sliderPressedThumbWidth
            }
        } else if (isIosStyle || useExpandedThumb) {
            visualSize
        } else {
            6.dp
        },
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "sliderThumbWidth",
    )
    val height by animateDpAsState(
        targetValue = if (active) {
            if (compact) {
                if (isIosStyle) 20.dp else 28.dp
            } else {
                tokens.sliderPressedThumbHeight
            }
        } else {
            visualSize
        },
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "sliderThumbHeight",
    )
    Box(
        modifier = Modifier.size(tokens.sliderThumbSize),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(width)
                .height(height)
                .then(
                    if (isIosStyle && backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { CircleShape },
                            effects = {
                                lens(
                                    refractionHeight = 8.dp.toPx(),
                                    refractionAmount = 10.dp.toPx(),
                                    chromaticAberration = true,
                                )
                            },
                        )
                    } else {
                        Modifier.background(color.copy(alpha = if (active) 0.88f else 1f), CircleShape)
                    },
                )
                .border(1.dp, Color.White.copy(alpha = if (active) 0.28f else 0f), CircleShape),
        )
    }
}
