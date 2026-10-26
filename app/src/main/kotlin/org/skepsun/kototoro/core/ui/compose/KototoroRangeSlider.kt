package org.skepsun.kototoro.core.ui.compose

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.core.ui.theme.LocalIsIosInterfaceStyle

/**
 * Kept with Android: its thumb interaction-source parameters only exist in androidx Material3 newer than the
 * Compose Multiplatform release core-ui builds against; the thumb itself is the shared one.
 */
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
    val isIosStyle = LocalIsIosInterfaceStyle.current
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
        startThumbInteractionSource = startInteractionSource,
        endThumbInteractionSource = endInteractionSource,
        startThumb = {
            KototoroSliderThumb(
                interactionSource = startInteractionSource,
                color = if (enabled) colors.thumbColor else colors.disabledThumbColor,
                isIosStyle = isIosStyle,
                useExpandedThumb = true, // both interface styles expand the touch target
                compact = false,
                backdrop = backdrop,
            )
        },
        endThumb = {
            KototoroSliderThumb(
                interactionSource = endInteractionSource,
                color = if (enabled) colors.thumbColor else colors.disabledThumbColor,
                isIosStyle = isIosStyle,
                useExpandedThumb = true, // both interface styles expand the touch target
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
