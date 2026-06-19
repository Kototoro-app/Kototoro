package org.skepsun.kototoro.details.ui.compose

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.Image
import coil3.asDrawable
import coil3.compose.AsyncImage
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade
import dagger.hilt.android.EntryPointAccessors
import org.skepsun.kototoro.core.BaseApp
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.compose.HeroCoverSnapshotStore
import org.skepsun.kototoro.core.ui.compose.rememberDrawablePainter
import org.skepsun.kototoro.core.ui.image.rememberPanoramaRequestSize
import org.skepsun.kototoro.core.ui.image.panoramaBlur
import org.skepsun.kototoro.core.ui.compose.panoramaAnimationDurations
import org.skepsun.kototoro.core.ui.compose.panoramaAnimationMotion
import org.skepsun.kototoro.core.util.ext.takeIfUsableImageUri

@Immutable
data class PanoramaBackdropPrefs(
    val isEnabled: Boolean,
    val blurPercent: Int,
    val bottomGradientAlphaPercent: Int,
    val isAnimationEnabled: Boolean,
    val isScrollLinkedEnabled: Boolean,
    val animationSpeedPercent: Int,
    val extraHeight: Int,
    val downsampleEnabled: Boolean,
    val limitToInfoCardMidpoint: Boolean,
)

@Composable
fun rememberPanoramaBackdropPrefs(settings: AppSettings): PanoramaBackdropPrefs {
    val supportsRealtimeEffects = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val prefs by settings.observeAsState(
        AppSettings.KEY_PANORAMA_ENABLED,
        AppSettings.KEY_PANORAMA_BLUR,
        AppSettings.KEY_PANORAMA_BOTTOM_GRADIENT_ALPHA,
        AppSettings.KEY_PANORAMA_ANIMATION_ENABLED,
        AppSettings.KEY_PANORAMA_ANIMATION_SPEED,
        AppSettings.KEY_PANORAMA_EXTRA_HEIGHT,
        AppSettings.KEY_PANORAMA_DOWNSAMPLE,
        AppSettings.KEY_DETAILS_PANORAMA_LIMIT_TO_INFO_CARD_MIDPOINT,
        AppSettings.KEY_DETAILS_PANORAMA_SCROLL_LINKED,
        AppSettings.KEY_REDUCED_VISUAL_EFFECTS,
    ) {
        PanoramaBackdropPrefs(
            isEnabled = isPanoramaCoverEnabled,
            blurPercent = panoramaCoverBlur,
            bottomGradientAlphaPercent = panoramaBottomGradientAlpha,
            isAnimationEnabled = supportsRealtimeEffects &&
                isPanoramaCoverAnimationEnabled &&
                !isReducedVisualEffectsEnabled,
            isScrollLinkedEnabled = isDetailsPanoramaScrollLinkedEnabled,
            animationSpeedPercent = panoramaAnimationSpeed,
            extraHeight = panoramaCoverExtraHeight,
            downsampleEnabled = isPanoramaDownsampleEnabled,
            limitToInfoCardMidpoint = isDetailsPanoramaLimitedToInfoCardMidpoint,
        )
    }
    return prefs
}

@Composable
fun AnimatedPanoramaBackdrop(
    prefs: PanoramaBackdropPrefs,
    model: Any?,
    placeholderMemoryCacheKey: String? = null,
    snapshotKey: String? = null,
    contentAlpha: Float,
    contentAlphaProvider: (() -> Float)? = null,
    backgroundColor: Color,
    crossfadeEnabled: Boolean = false,
    onLoadError: (() -> Unit)? = null,
    fullOpacityAtY: Float? = null,
    fullOpacityFadeDistancePx: Float = 0f,
    scrollLinkedTranslationYPx: Float = 0f,
    modifier: Modifier = Modifier,
) {
    if (!prefs.isEnabled) return
    val normalizedModel = (model as? String)?.takeIfUsableImageUri() ?: model.takeUnless { it is String }

    val panoramaGradientAlphaFactor = (prefs.bottomGradientAlphaPercent / 100f).coerceIn(0f, 1f)
    val animationDurations = panoramaAnimationDurations(prefs.animationSpeedPercent)
    val animationMotion = panoramaAnimationMotion()
    val density = LocalDensity.current
    val horizontalPanPx = with(density) { animationMotion.horizontalPan.toPx() }

    val infiniteTransition = if (prefs.isAnimationEnabled) {
        rememberInfiniteTransition(label = "details_panorama_background")
    } else {
        null
    }
    val backgroundScaleState = if (infiniteTransition != null) {
        infiniteTransition.animateFloat(
            initialValue = animationMotion.initialScale,
            targetValue = animationMotion.targetScale,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = animationDurations.scaleMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "details_panorama_background_scale",
        )
    } else {
        null
    }
    val backgroundTranslationXState = if (infiniteTransition != null) {
        infiniteTransition.animateFloat(
            initialValue = -horizontalPanPx,
            targetValue = horizontalPanPx,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = animationDurations.horizontalPanMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "details_panorama_background_translation_x",
        )
    } else {
        null
    }
    val context = LocalContext.current
    val useRealtimeBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && prefs.blurPercent > 0
    val realtimeBlurRadius = ((prefs.blurPercent.coerceIn(0, 100) / 100f) * 18f).dp
    val imageLoader = remember(context.applicationContext) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            BaseApp.BaseAppEntryPoint::class.java,
        ).imageLoader()
    }
    val panoramaRequestSize = rememberPanoramaRequestSize(
        minWidthPx = 1280,
        minHeightPx = 1280,
        maxWidthPx = 2560,
        maxHeightPx = 2200,
        widthOverscan = 1.42f,
        heightOverscan = 1.0f,
        downsample = prefs.downsampleEnabled,
    )
    val backgroundRequest = androidx.compose.runtime.remember(
        normalizedModel,
        context,
        crossfadeEnabled,
        prefs.blurPercent,
        panoramaRequestSize,
        useRealtimeBlur,
    ) {
        when (normalizedModel) {
            is ImageRequest -> normalizedModel.newBuilder()
                .size(panoramaRequestSize)
                .crossfade(crossfadeEnabled)
                .apply {
                    if (!useRealtimeBlur) {
                        panoramaBlur(prefs.blurPercent)
                    }
                }
                .build()
            else -> ImageRequest.Builder(context)
                .data(normalizedModel)
                .size(panoramaRequestSize)
                .crossfade(crossfadeEnabled)
                .apply {
                    if (!useRealtimeBlur) {
                        panoramaBlur(prefs.blurPercent)
                    }
                }
                .build()
        }
    }
    val placeholderImage = remember(imageLoader, backgroundRequest, placeholderMemoryCacheKey, snapshotKey) {
        val memoryCache = imageLoader.memoryCache
        val primaryPlaceholder = backgroundRequest.memoryCacheKey?.let { key ->
            memoryCache?.get(MemoryCache.Key(key))?.image
        }
        primaryPlaceholder
            ?: placeholderMemoryCacheKey?.let { key ->
                memoryCache?.get(MemoryCache.Key(key))?.image
            }
            ?: snapshotKey?.let(HeroCoverSnapshotStore::get)
    }
    var lastResolvedImage by remember { mutableStateOf<Image?>(null) }
    val stablePlaceholderImage = placeholderImage ?: lastResolvedImage
    val placeholderPainter = rememberDrawablePainter(stablePlaceholderImage?.asDrawable(context.resources))
    var hasResolvedBackground by remember(backgroundRequest) { mutableStateOf(false) }
    val backgroundModifier = modifier
        .fillMaxSize()
        .then(
            if (useRealtimeBlur) {
                Modifier.blur(
                    radius = realtimeBlurRadius,
                    edgeTreatment = BlurredEdgeTreatment.Rectangle,
                )
            } else {
                Modifier
            },
        )
        .graphicsLayer {
            val backgroundScale = backgroundScaleState?.value ?: 1f
            scaleX = backgroundScale
            scaleY = backgroundScale
            translationX = backgroundTranslationXState?.value ?: 0f
            translationY = scrollLinkedTranslationYPx
            alpha = (contentAlphaProvider?.invoke() ?: contentAlpha).coerceIn(0f, 1f)
        }

    if (!hasResolvedBackground && stablePlaceholderImage != null) {
        Image(
            painter = placeholderPainter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = backgroundModifier,
        )
    }

    if (normalizedModel != null) {
        AsyncImage(
            model = backgroundRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = backgroundModifier,
            onSuccess = { state ->
                lastResolvedImage = state.result.image
                hasResolvedBackground = true
            },
            onError = {
                // Keep an already-resolved placeholder visible when the panorama request fails,
                // otherwise the backdrop flashes once and then collapses to a blank surface.
                hasResolvedBackground = stablePlaceholderImage == null
                onLoadError?.invoke()
            },
        )
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                if (fullOpacityAtY != null && fullOpacityAtY.isFinite()) {
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.55f to backgroundColor.copy(alpha = (panoramaGradientAlphaFactor * 0.45f).coerceIn(0f, 1f)),
                            0.82f to backgroundColor.copy(alpha = (0.72f + (panoramaGradientAlphaFactor * 0.28f)).coerceIn(0f, 1f)),
                            1f to backgroundColor,
                        ),
                        startY = (fullOpacityAtY - fullOpacityFadeDistancePx).coerceAtLeast(0f),
                        endY = fullOpacityAtY.coerceAtLeast(0f),
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            backgroundColor.copy(alpha = panoramaGradientAlphaFactor),
                        ),
                    )
                },
            ),
    )
}
