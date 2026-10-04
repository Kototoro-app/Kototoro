package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
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
    var failed by remember(content.source.name, content.id, content.coverUrl, content.largeCoverUrl, revision) {
        mutableStateOf(false)
    }
    val url = if (large) content.largeCoverUrl ?: content.coverUrl else content.coverUrl
    val bitmap by produceState<ImageBitmap?>(null, cache, content.source, content.id, url, revision) {
        value = null
        if (!url.isNullOrBlank()) try {
            val path = cache.load(content, large, refresh = revision > 0)
            value = withContext(Dispatchers.IO) {
                // The conversion creates a separate Bitmap retained by Compose.
                SkiaImage.makeFromEncoded(Files.readAllBytes(path)).use { it.toComposeImageBitmap() }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { failed = true }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image, "${content.title}封面", contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().testTag("cover:${content.id}"))
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(content.title.take(1), fontSize = 44.sp, color = Color(0xFF217A68))
            if (failed) TextButton({ revision++ }, modifier = Modifier.testTag("cover-retry:${content.id}")) {
                Text("重试封面")
            }
        }
    }
}
