package org.skepsun.kototoro.explore.ui.compose


import androidx.compose.foundation.layout.size
import coil3.request.ImageRequest.Builder
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.skepsun.kototoro.core.ui.image.panoramaBlur
import org.skepsun.kototoro.core.util.ext.mangaExtra

internal fun buildExploreCoverRequest(
    context: android.content.Context,
    coverUrl: String?,
    content: org.skepsun.kototoro.parsers.model.Content,
    size: Int? = null,
    blurPercent: Int = 0,
    sharedMemoryCacheKey: String? = null,
    crossfadeEnabled: Boolean = true,
): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .data(normalizeExploreCoverUrl(coverUrl))
        .mangaExtra(content)
        .crossfade(crossfadeEnabled)
        .panoramaBlur(blurPercent)
    if (sharedMemoryCacheKey != null) {
        builder.memoryCacheKey(sharedMemoryCacheKey)
        builder.diskCacheKey(sharedMemoryCacheKey)
    }
    if (size != null) {
        builder.size(size)
    }
    return builder.build()
}

private fun normalizeExploreCoverUrl(url: String?): String? = when {
    url == null -> null
    url.startsWith("//") -> "https:$url"
    else -> url
}

