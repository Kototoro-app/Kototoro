package org.skepsun.kototoro.core.ui.compose

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.IntSize
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import org.skepsun.kototoro.core.ui.image.PageArtworkBlur
import org.skepsun.kototoro.core.ui.theme.ArtworkBackdropPresets
import org.skepsun.kototoro.core.ui.theme.forStyle
import org.skepsun.kototoro.core.ui.theme.LocalBackgroundStyle
import org.skepsun.kototoro.core.ui.theme.isDarkTheme
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import kotlinx.coroutines.delay
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

internal val DynamicArtworkRequestSize = Size(width = 1280, height = 1280)
private const val ArtworkBlurSettleMillis = 300L

@Composable
fun DynamicArtworkBackdrop(
    content: Content?,
    imageUri: String? = null,
    imageOpacity: Float = 1f,
    overlayStrength: Float = 1f,
    blurRadius: Float = 35f,
    modifier: Modifier = Modifier,
    prepareStaticBlur: Boolean = true,
    children: @Composable BoxScope.() -> Unit,
) {
    val backgroundStyle = LocalBackgroundStyle.current
    val isArtworkBackground = backgroundStyle.usesArtworkBackdrop
    val preset = ArtworkBackdropPresets.forStyle(backgroundStyle)
    val isDark = MaterialTheme.colorScheme.isDarkTheme()
    val cover = imageUri?.takeIf { it.isNotBlank() } ?: content?.coverUrl ?: content?.publicUrl
    val context = LocalContext.current
    val imageRequest = remember(context, content?.id, content?.source?.name, content?.url, cover) {
        ImageRequest.Builder(context)
            .data(cover)
            .size(DynamicArtworkRequestSize)
            .crossfade(true)
            .mangaExtra(content)
            .build()
    }
    // Desaturate the backdrop so grid covers become the most saturated elements on the
    // page and the image recedes into an atmospheric layer.
    val backdropColorFilter = remember(preset.backdropSaturation) {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(preset.backdropSaturation) })
    }
    // Three-stop vertical gradient instead of a flat overlay: chrome zones (top/bottom)
    // stay strongly veiled for legibility while the mid band lets the artwork breathe.
    //
    // The user-facing strength slider scales only the *variable* part of the veil above
    // a legibility floor, so dragging it down never collapses text contrast the way a
    // pure linear multiply does (a 25% setting used to leave the overlay at 1/4 alpha).
    val overlayBrush = remember(isDark, preset, overlayStrength) {
        val top = if (isDark) preset.overlayTopDark else preset.overlayTopLight
        val mid = if (isDark) preset.overlayMidDark else preset.overlayMidLight
        val bottom = if (isDark) preset.overlayBottomDark else preset.overlayBottomLight
        val base = if (isDark) Color.Black else Color.White
        val s = overlayStrength.coerceIn(0f, 1f)
        // Legibility floor: keep this fraction of each stop's alpha regardless of the
        // slider; the slider only governs the remaining headroom.
        val floor = 0.45f
        fun scaled(alpha: Float): Float = alpha * (floor + (1f - floor) * s)
        Brush.verticalGradient(
            0f to base.copy(alpha = scaled(top)),
            0.45f to base.copy(alpha = scaled(mid)),
            1f to base.copy(alpha = scaled(bottom)),
        )
    }

    // Capture only the artwork layer. Route chrome is captured separately by
    // MainShellScene, so content material can read this source without creating
    // a feedback loop through the route-level capture.
    val artworkBackgroundColor = MaterialTheme.colorScheme.background
    val artworkBackdrop = rememberLayerBackdrop {
        drawRect(artworkBackgroundColor)
        drawContent()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (isArtworkBackground) MaterialTheme.colorScheme.background else Color.Transparent),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isArtworkBackground) {
                        Modifier.layerBackdrop(artworkBackdrop)
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (isArtworkBackground && !cover.isNullOrEmpty()) {
                PageArtworkImage(
                    request = imageRequest,
                    blurRadius = blurRadius,
                    opacity = imageOpacity,
                    colorFilter = backdropColorFilter,
                    prepareStaticBlur = prepareStaticBlur,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(overlayBrush),
                )
            }
        }
        CompositionLocalProvider(
            LocalArtworkBackdrop provides artworkBackdrop.takeIf { isArtworkBackground },
        ) {
            children()
        }
    }
}

@Composable
private fun PageArtworkImage(
    request: ImageRequest,
    blurRadius: Float,
    opacity: Float,
    colorFilter: ColorFilter,
    prepareStaticBlur: Boolean,
) {
    // Keep the source painter alive across radius and viewport changes so it can draw the live preview immediately.
    val source = rememberAsyncImagePainter(request)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val prepared = if (
            prepareStaticBlur && Build.VERSION.SDK_INT >= 31 && blurRadius > 0f &&
            constraints.hasBoundedWidth && constraints.hasBoundedHeight &&
            constraints.maxWidth > 0 && constraints.maxHeight > 0
        ) {
            rememberPreparedPageArtwork(source, IntSize(constraints.maxWidth, constraints.maxHeight), blurRadius)
        } else null
        val liveEffect = remember(prepared == null, blurRadius) {
            if (prepared == null && blurRadius > 0f) BlurEffect(blurRadius, blurRadius) else null
        }
        Image(
            painter = prepared ?: source,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = colorFilter,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = opacity.coerceIn(0f, 1f)
                renderEffect = liveEffect
            },
        )
    }
}

@Composable
private fun rememberPreparedPageArtwork(source: AsyncImagePainter, viewport: IntSize, radius: Float): BitmapPainter? {
    val state by source.state.collectAsState()
    val result = (state as? AsyncImagePainter.State.Success)?.result ?: return null
    val bitmap = result.image as? BitmapImage ?: return null // Animated artwork keeps the original painter.
    val graphics = LocalGraphicsContext.current
    val blur = remember(graphics, viewport, radius) { PageArtworkBlur(graphics, viewport, radius) }
    val cache = SingletonImageLoader.get(LocalContext.current).memoryCache
    val key = remember(result, blur) { result.memoryCacheKey?.let(blur::cacheKey) }
    var image by remember(bitmap, cache, key, blur) {
        mutableStateOf(key?.let { cache?.get(it)?.image as? BitmapImage })
    }
    LaunchedEffect(bitmap, cache, key, blur) {
        if (image != null) return@LaunchedEffect
        // A resize can span many frames. Only snapshot the final viewport after it settles.
        delay(ArtworkBlurSettleMillis)
        val snapshot = runCatchingCancellable { blur.render(bitmap.bitmap).asImage() }
            .onFailure { it.printStackTraceDebug() }.getOrNull() ?: return@LaunchedEffect
        if (key != null && cache != null) cache[key] = MemoryCache.Value(snapshot)
        image = snapshot
    }
    return remember(image) { image?.let { BitmapPainter(it.bitmap.asImageBitmap()) } }
}
