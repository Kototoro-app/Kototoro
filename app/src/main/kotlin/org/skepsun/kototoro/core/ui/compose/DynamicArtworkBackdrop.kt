package org.skepsun.kototoro.core.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import org.skepsun.kototoro.core.ui.theme.ArtworkBackdropPresets
import org.skepsun.kototoro.core.ui.theme.LocalBackgroundStyle
import org.skepsun.kototoro.core.ui.theme.isDarkTheme
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.parsers.model.Content

internal val DynamicArtworkRequestSize = Size(width = 1280, height = 1280)

@Composable
fun DynamicArtworkBackdrop(
    content: Content?,
    imageUri: String? = null,
    imageOpacity: Float = 1f,
    overlayStrength: Float = 1f,
    blurRadius: Float = 35f,
    modifier: Modifier = Modifier,
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (isArtworkBackground) MaterialTheme.colorScheme.background else Color.Transparent),
    ) {
        if (isArtworkBackground && !cover.isNullOrEmpty()) {
            Image(
                painter = rememberAsyncImagePainter(
                    model = imageRequest,
                ),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = backdropColorFilter,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = imageOpacity.coerceIn(0f, 1f)
                        renderEffect = blurRadius.coerceAtLeast(0f).takeIf { it > 0f }?.let {
                            BlurEffect(it, it)
                        }
                    },
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(overlayBrush),
            )
        }
        children()
    }
}
