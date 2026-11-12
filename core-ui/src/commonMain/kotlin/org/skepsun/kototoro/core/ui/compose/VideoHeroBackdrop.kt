package org.skepsun.kototoro.core.ui.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val VideoHeroFadeEnd = 0.98f
private const val VideoHeroFadeRange = 0.68f

internal fun resolveVideoHeroFadeStart(range: Float = VideoHeroFadeRange): Float {
    val clamped = range.coerceIn(0f, 1f)
    return 0.82f - (0.82f - 0.24f) * clamped
}

/**
 * Shared cinema hero backdrop modeled after iOS `VideoHeroBackdrop`:
 * a crisp top poster dissolving early into a softly blurred wash, which in turn fades cleanly into the
 * dominant-color-tinted [backgroundColor] below.
 */
@Composable
fun VideoHeroBackdropLayer(
    height: Dp,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    fullScreen: Boolean = false,
    blurRadius: Dp = 14.dp,
    useRealtimeBlur: Boolean = true,
    scrollLinked: Boolean = true,
    animateMotion: Boolean = false,
    animationSpeedPercent: Int = 100,
    scrollOffsetPxProvider: () -> Float = { 0f },
    fadeDistancePx: Float = 440f,
    diffusedArtwork: @Composable BoxScope.(Modifier) -> Unit,
    sharpArtwork: @Composable BoxScope.(Modifier) -> Unit,
) {
    val fadeStart = if (fullScreen) 0.55f else resolveVideoHeroFadeStart(VideoHeroFadeRange)
    val fadeLength = VideoHeroFadeEnd - fadeStart
    val effectiveScrollLinked = scrollLinked && !fullScreen

    val animationDurations = panoramaAnimationDurations(animationSpeedPercent)
    val animationMotion = panoramaAnimationMotion()
    val horizontalPanPx = with(LocalDensity.current) { animationMotion.horizontalPan.toPx() }
    val infiniteTransition = if (animateMotion) {
        rememberInfiniteTransition(label = "video_hero_backdrop_motion")
    } else {
        null
    }
    val motionScaleState = if (infiniteTransition != null) {
        infiniteTransition.animateFloat(
            initialValue = animationMotion.initialScale,
            targetValue = animationMotion.targetScale,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = animationDurations.scaleMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "video_hero_motion_scale",
        )
    } else {
        null
    }
    val motionTranslationXState = if (infiniteTransition != null) {
        infiniteTransition.animateFloat(
            initialValue = -horizontalPanPx,
            targetValue = horizontalPanPx,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = animationDurations.horizontalPanMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "video_hero_motion_translation_x",
        )
    } else {
        null
    }
    val artworkModifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
            if (motionScaleState != null && motionTranslationXState != null) {
                val scale = motionScaleState.value
                scaleX = scale
                scaleY = scale
                translationX = motionTranslationXState.value
            }
        }

    Box(
        modifier = modifier
            .then(
                if (fullScreen) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxWidth().height(height)
                },
            )
            .clipToBounds()
            .graphicsLayer {
                val offset = scrollOffsetPxProvider().coerceAtLeast(0f)
                translationY = if (effectiveScrollLinked) -offset else 0f
                alpha = if (!effectiveScrollLinked && fadeDistancePx > 0f) {
                    (1f - offset / fadeDistancePx).coerceIn(0f, 1f)
                } else {
                    1f
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (useRealtimeBlur && blurRadius > 0.dp) {
                        Modifier.blur(radius = blurRadius, edgeTreatment = BlurredEdgeTreatment.Rectangle)
                    } else {
                        Modifier
                    },
                )
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val mask = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black,
                            fadeStart to Color.Black,
                            (fadeStart + fadeLength * 0.28f) to Color.Black.copy(alpha = 0.86f),
                            (fadeStart + fadeLength * 0.56f) to Color.Black.copy(alpha = 0.54f),
                            (fadeStart + fadeLength * 0.80f) to Color.Black.copy(alpha = 0.22f),
                            VideoHeroFadeEnd to Color.Transparent,
                            1f to Color.Transparent,
                        ),
                    )
                    onDrawWithContent {
                        drawContent()
                        drawRect(mask, blendMode = BlendMode.DstIn)
                    }
                },
        ) {
            diffusedArtwork(artworkModifier)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val sharpMask = if (fullScreen) {
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Black,
                                0.20f to Color.Black,
                                0.28f to Color.Black.copy(alpha = 0.84f),
                                0.36f to Color.Black.copy(alpha = 0.56f),
                                0.44f to Color.Black.copy(alpha = 0.24f),
                                0.52f to Color.Transparent,
                                1f to Color.Transparent,
                            ),
                        )
                    } else {
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Black,
                                0.38f to Color.Black,
                                0.48f to Color.Black.copy(alpha = 0.88f),
                                0.58f to Color.Black.copy(alpha = 0.64f),
                                0.68f to Color.Black.copy(alpha = 0.34f),
                                0.76f to Color.Black.copy(alpha = 0.12f),
                                0.84f to Color.Transparent,
                                1f to Color.Transparent,
                            ),
                        )
                    }
                    onDrawWithContent {
                        drawContent()
                        drawRect(sharpMask, blendMode = BlendMode.DstIn)
                    }
                },
        ) {
            sharpArtwork(artworkModifier)
        }

        // Subtle top vignette so translucent top-bar controls stay legible on bright posters.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(120.dp)
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = 0.20f),
                            0.5f to Color.Black.copy(alpha = 0.07f),
                            1f to Color.Transparent,
                        ),
                    ),
                ),
        )

        // Subtle bottom tie-in to the page background color.
        if (fullScreen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                0.26f to backgroundColor.copy(alpha = 0.12f),
                                0.50f to backgroundColor.copy(alpha = 0.46f),
                                0.78f to backgroundColor.copy(alpha = 0.78f),
                                1f to backgroundColor,
                            ),
                        ),
                    ),
            )
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, backgroundColor),
                        ),
                    ),
            )
        }
    }
}
