package org.skepsun.kototoro.desktop.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import org.skepsun.kototoro.core.ui.compose.DetailsMediaStyle
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.ui.compose.VideoHeroBackdropLayer
import org.skepsun.kototoro.core.ui.compose.detailsMediaStylePolicy
import org.skepsun.kototoro.core.ui.theme.CoverDominantColor
import java.nio.file.Files

@Composable
internal fun DesktopCover(content: SourceContent, cache: DesktopCovers, modifier: Modifier, large: Boolean = false) {
    var revision by remember(content.source.name, content.id, content.coverUrl, content.largeCoverUrl) {
        mutableIntStateOf(0)
    }
    val cover = rememberDesktopCover(content, cache, large, revision)
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = cover.bitmap
        if (image != null) Image(image, desktopText("details.cover", content.title), contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().testTag("cover:${content.id}"))
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(content.title.take(1), fontSize = 44.sp, color = Accent)
            if (cover.failed) TextButton({ revision++ }, modifier = Modifier.testTag("cover-retry:${content.id}")) {
                Text(desktopText("error.coverRetry"))
            }
        }
    }
}

private data class DesktopCoverState(val bitmap: ImageBitmap? = null, val failed: Boolean = false)

@Composable
private fun rememberDesktopCover(content: SourceContent, cache: DesktopCovers, large: Boolean,
    revision: Int = 0): DesktopCoverState {
    val url = if (large) content.largeCoverUrl ?: content.coverUrl else content.coverUrl
    val cover by produceState(DesktopCoverState(), cache, content.source, content.id, url, revision) {
        value = DesktopCoverState()
        if (!url.isNullOrBlank()) try {
            val path = cache.load(content, large, refresh = revision > 0)
            val bitmap = withContext(DesktopDispatchers.decode) {
                // The conversion creates a separate Bitmap retained by Compose.
                SkiaImage.makeFromEncoded(Files.readAllBytes(path)).use { it.toComposeImageBitmap() }
            }
            value = DesktopCoverState(bitmap)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { value = DesktopCoverState(failed = true) }
    }
    return cover
}

/**
 * Extracts and caches the cover's dominant hue using the shared CMP extractor and adapts the backdrop style
 * per media family (crisp cinema hero for video vs soft blurred artwork wash for manga/novel).
 */
@Composable
internal fun DesktopArtworkBackground(
    content: SourceContent,
    cache: DesktopCovers,
    modifier: Modifier,
    styleOverride: DetailsMediaStyle? = null,
) {
    val image = rememberDesktopCover(content, cache, large = true).bitmap
    val mediaStylePolicy = remember(content.source.contentType, styleOverride) {
        detailsMediaStylePolicy(content.source.contentType, styleOverride)
    }
    val dominantKey = remember(content.source.name, content.url, content.coverUrl, content.largeCoverUrl) {
        "${content.source.name}\n${content.url}\n${content.largeCoverUrl ?: content.coverUrl.orEmpty()}"
    }
    var dominantColor by remember(dominantKey) {
        mutableStateOf(CoverDominantColor.cached(dominantKey))
    }
    LaunchedEffect(dominantKey, image) {
        if (image == null) return@LaunchedEffect
        CoverDominantColor.cached(dominantKey)?.let { cached ->
            dominantColor = cached
            return@LaunchedEffect
        }
        val extracted = withContext(DesktopDispatchers.decode) {
            CoverDominantColor.extract(image)
        }
        if (extracted != null) {
            CoverDominantColor.putCached(dominantKey, extracted)
        }
        dominantColor = extracted
    }
    val isDark = LocalDesktopDark.current
    val baseCanvas = Canvas
    val targetCanvas = remember(baseCanvas, dominantColor, isDark, mediaStylePolicy) {
        CoverDominantColor.resolvePageBackground(
            baseColor = baseCanvas,
            dominantColor = dominantColor,
            isDarkTheme = isDark,
            strength = mediaStylePolicy.dominantBackgroundStrength,
        )
    }
    val tintedCanvas by animateColorAsState(
        targetValue = targetCanvas,
        animationSpec = tween(durationMillis = 350),
        label = "desktopDominantCanvas",
    )

    Box(modifier.clipToBounds().background(tintedCanvas).background(desktopCanvasBrush())) {
        if (image != null) {
            if (mediaStylePolicy.useVideoHeroBackdrop) {
                VideoHeroBackdropLayer(
                    height = 420.dp,
                    backgroundColor = tintedCanvas,
                    blurRadius = 18.dp,
                    useRealtimeBlur = true,
                    diffusedArtwork = { m ->
                        Image(image, null, contentScale = ContentScale.Crop, modifier = m)
                    },
                    sharpArtwork = { m ->
                        Image(image, null, contentScale = ContentScale.Crop, modifier = m.testTag("details-artwork"))
                    },
                )
            } else {
                Image(image, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(40.dp).testTag("details-artwork"))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                    tintedCanvas.copy(alpha = .72f), tintedCanvas.copy(alpha = .92f), tintedCanvas,
                ))))
            }
        }
    }
}
