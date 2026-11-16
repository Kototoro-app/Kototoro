package org.skepsun.kototoro.core.ui.progress

import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import androidx.compose.material3.CircularProgressIndicator as M3CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator as M3LinearProgressIndicator

/** The user's progress indicator treatment (Android: the "loading indicator style" appearance setting). */
enum class ProgressIndicatorStyle(val wavy: Boolean, val thin: Boolean) {
    THICK_STRAIGHT(wavy = false, thin = false),
    THICK_WAVY(wavy = true, thin = false),
    THIN_STRAIGHT(wavy = false, thin = true),
    THIN_WAVY(wavy = true, thin = true),
}

val LocalProgressIndicatorStyle = staticCompositionLocalOf { ProgressIndicatorStyle.THICK_WAVY }

/*
 * Drop-in replacements for the Material 3 progress indicators: same names and parameters, so a screen opts in by
 * importing them from this package instead of androidx.compose.material3. Under the Material 3 Expressive interface
 * style with a wavy [LocalProgressIndicatorStyle] they are the official wavy indicators; they stay the plain ones when
 * the caller shapes the stroke itself (width, cap, gap, stop) or leaves no room for a wave, e.g. a spinner inside a
 * button or a hairline bar.
 */

/** A wave needs about this much room; below it the plain indicator reads better. */
private val CircularWaveMinSize = 36.dp
private val LinearWaveMinHeight = 8.dp
private val ThinStrokeWidth = 2.dp

@Composable
private fun wavyStyleOrNull(customised: Boolean): ProgressIndicatorStyle? {
    if (customised || !LocalMaterialExpressiveComponentsEnabled.current) return null
    return LocalProgressIndicatorStyle.current.takeIf { it.wavy }
}

@Composable
private fun wavyStroke(style: ProgressIndicatorStyle, default: Stroke): Stroke {
    if (!style.thin) return default
    val width = with(LocalDensity.current) { ThinStrokeWidth.toPx() }
    return remember(width) { Stroke(width = width, cap = StrokeCap.Round) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CircularProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    strokeWidth: Dp = Dp.Unspecified,
    trackColor: Color = Color.Unspecified,
    strokeCap: StrokeCap? = null,
    gapSize: Dp = Dp.Unspecified,
) {
    val style = wavyStyleOrNull(customised = strokeWidth.isSpecified || strokeCap != null || gapSize.isSpecified)
    var roomy by remember { mutableStateOf(true) }
    val minSize = with(LocalDensity.current) { CircularWaveMinSize.roundToPx() }
    val probed = modifier.onSizeChanged { roomy = it.width >= minSize && it.height >= minSize }
    if (style != null && roomy) {
        CircularWavyProgressIndicator(
            progress = progress,
            modifier = probed,
            color = if (color.isSpecified) color else WavyProgressIndicatorDefaults.indicatorColor,
            trackColor = if (trackColor.isSpecified) trackColor else WavyProgressIndicatorDefaults.trackColor,
            stroke = wavyStroke(style, WavyProgressIndicatorDefaults.circularIndicatorStroke),
            trackStroke = wavyStroke(style, WavyProgressIndicatorDefaults.circularTrackStroke),
        )
    } else {
        M3CircularProgressIndicator(
            progress = progress,
            modifier = probed,
            color = if (color.isSpecified) color else ProgressIndicatorDefaults.circularColor,
            strokeWidth = if (strokeWidth.isSpecified) strokeWidth else ProgressIndicatorDefaults.CircularStrokeWidth,
            trackColor = if (trackColor.isSpecified) trackColor else ProgressIndicatorDefaults.circularDeterminateTrackColor,
            strokeCap = strokeCap ?: ProgressIndicatorDefaults.CircularDeterminateStrokeCap,
            gapSize = if (gapSize.isSpecified) gapSize else ProgressIndicatorDefaults.CircularIndicatorTrackGapSize,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    strokeWidth: Dp = Dp.Unspecified,
    trackColor: Color = Color.Unspecified,
    strokeCap: StrokeCap? = null,
    gapSize: Dp = Dp.Unspecified,
) {
    val style = wavyStyleOrNull(customised = strokeWidth.isSpecified || strokeCap != null || gapSize.isSpecified)
    var roomy by remember { mutableStateOf(true) }
    val minSize = with(LocalDensity.current) { CircularWaveMinSize.roundToPx() }
    val probed = modifier.onSizeChanged { roomy = it.width >= minSize && it.height >= minSize }
    if (style != null && roomy) {
        CircularWavyProgressIndicator(
            modifier = probed,
            color = if (color.isSpecified) color else WavyProgressIndicatorDefaults.indicatorColor,
            trackColor = if (trackColor.isSpecified) trackColor else WavyProgressIndicatorDefaults.trackColor,
            stroke = wavyStroke(style, WavyProgressIndicatorDefaults.circularIndicatorStroke),
            trackStroke = wavyStroke(style, WavyProgressIndicatorDefaults.circularTrackStroke),
        )
    } else {
        M3CircularProgressIndicator(
            modifier = probed,
            color = if (color.isSpecified) color else ProgressIndicatorDefaults.circularColor,
            strokeWidth = if (strokeWidth.isSpecified) strokeWidth else ProgressIndicatorDefaults.CircularStrokeWidth,
            trackColor = if (trackColor.isSpecified) trackColor else ProgressIndicatorDefaults.circularIndeterminateTrackColor,
            strokeCap = strokeCap ?: ProgressIndicatorDefaults.CircularIndeterminateStrokeCap,
            gapSize = if (gapSize.isSpecified) gapSize else ProgressIndicatorDefaults.CircularIndicatorTrackGapSize,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LinearProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified,
    strokeCap: StrokeCap? = null,
    gapSize: Dp = Dp.Unspecified,
    drawStopIndicator: (DrawScope.() -> Unit)? = null,
) {
    val style = wavyStyleOrNull(customised = strokeCap != null || gapSize.isSpecified || drawStopIndicator != null)
    var roomy by remember { mutableStateOf(true) }
    val minHeight = with(LocalDensity.current) { LinearWaveMinHeight.roundToPx() }
    val probed = modifier.onSizeChanged { roomy = it.height >= minHeight }
    if (style != null && roomy) {
        LinearWavyProgressIndicator(
            progress = progress,
            modifier = probed,
            color = if (color.isSpecified) color else WavyProgressIndicatorDefaults.indicatorColor,
            trackColor = if (trackColor.isSpecified) trackColor else WavyProgressIndicatorDefaults.trackColor,
            stroke = wavyStroke(style, WavyProgressIndicatorDefaults.linearIndicatorStroke),
            trackStroke = wavyStroke(style, WavyProgressIndicatorDefaults.linearTrackStroke),
        )
    } else {
        val resolvedColor = if (color.isSpecified) color else ProgressIndicatorDefaults.linearColor
        val resolvedCap = strokeCap ?: ProgressIndicatorDefaults.LinearStrokeCap
        M3LinearProgressIndicator(
            progress = progress,
            modifier = probed,
            color = resolvedColor,
            trackColor = if (trackColor.isSpecified) trackColor else ProgressIndicatorDefaults.linearTrackColor,
            strokeCap = resolvedCap,
            gapSize = if (gapSize.isSpecified) gapSize else ProgressIndicatorDefaults.LinearIndicatorTrackGapSize,
            drawStopIndicator = drawStopIndicator ?: {
                ProgressIndicatorDefaults.drawStopIndicator(
                    drawScope = this,
                    stopSize = ProgressIndicatorDefaults.LinearTrackStopIndicatorSize,
                    color = resolvedColor,
                    strokeCap = resolvedCap,
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LinearProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified,
    strokeCap: StrokeCap? = null,
    gapSize: Dp = Dp.Unspecified,
) {
    val style = wavyStyleOrNull(customised = strokeCap != null || gapSize.isSpecified)
    var roomy by remember { mutableStateOf(true) }
    val minHeight = with(LocalDensity.current) { LinearWaveMinHeight.roundToPx() }
    val probed = modifier.onSizeChanged { roomy = it.height >= minHeight }
    if (style != null && roomy) {
        LinearWavyProgressIndicator(
            modifier = probed,
            color = if (color.isSpecified) color else WavyProgressIndicatorDefaults.indicatorColor,
            trackColor = if (trackColor.isSpecified) trackColor else WavyProgressIndicatorDefaults.trackColor,
            stroke = wavyStroke(style, WavyProgressIndicatorDefaults.linearIndicatorStroke),
            trackStroke = wavyStroke(style, WavyProgressIndicatorDefaults.linearTrackStroke),
        )
    } else {
        M3LinearProgressIndicator(
            modifier = probed,
            color = if (color.isSpecified) color else ProgressIndicatorDefaults.linearColor,
            trackColor = if (trackColor.isSpecified) trackColor else ProgressIndicatorDefaults.linearTrackColor,
            strokeCap = strokeCap ?: ProgressIndicatorDefaults.LinearStrokeCap,
            gapSize = if (gapSize.isSpecified) gapSize else ProgressIndicatorDefaults.LinearIndicatorTrackGapSize,
        )
    }
}
