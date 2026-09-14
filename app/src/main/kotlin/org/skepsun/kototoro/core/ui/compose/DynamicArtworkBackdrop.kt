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
import org.skepsun.kototoro.core.prefs.BackgroundStyle
import org.skepsun.kototoro.core.ui.theme.LocalBackgroundStyle
import org.skepsun.kototoro.core.ui.theme.artworkOverlayColor
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
    val isArtworkBackground = LocalBackgroundStyle.current == BackgroundStyle.DYNAMIC_ARTWORK_BLUR
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
                    .background(MaterialTheme.colorScheme.artworkOverlayColor(overlayStrength)),
            )
        }
        children()
    }
}
