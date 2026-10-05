package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
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
import org.skepsun.kototoro.core.source.SourceContent
import java.nio.file.Files

@Composable
internal fun DesktopCover(content: SourceContent, cache: DesktopCovers, modifier: Modifier, large: Boolean = false) {
    var revision by remember(content.source.name, content.id, content.coverUrl, content.largeCoverUrl) {
        mutableIntStateOf(0)
    }
    val cover = rememberDesktopCover(content, cache, large, revision)
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = cover.bitmap
        if (image != null) Image(image, "${content.title}封面", contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().testTag("cover:${content.id}"))
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(content.title.take(1), fontSize = 44.sp, color = Accent)
            if (cover.failed) TextButton({ revision++ }, modifier = Modifier.testTag("cover-retry:${content.id}")) {
                Text("重试封面")
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
            val bitmap = withContext(Dispatchers.IO) {
                // The conversion creates a separate Bitmap retained by Compose.
                SkiaImage.makeFromEncoded(Files.readAllBytes(path)).use { it.toComposeImageBitmap() }
            }
            value = DesktopCoverState(bitmap)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { value = DesktopCoverState(failed = true) }
    }
    return cover
}

/** Blur only the artwork itself; controls use the stable themed surfaces above it. */
@Composable
internal fun DesktopArtworkBackground(content: SourceContent, cache: DesktopCovers, modifier: Modifier) {
    val image = rememberDesktopCover(content, cache, large = true).bitmap
    Box(modifier.clipToBounds().background(Canvas).background(desktopCanvasBrush())) {
        if (image != null) {
            Image(image, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(40.dp).testTag("details-artwork"))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                Canvas.copy(alpha = .75f), Canvas.copy(alpha = .94f), Canvas,
            ))))
        }
    }
}
