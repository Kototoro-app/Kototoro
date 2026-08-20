package org.skepsun.kototoro.home.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlin.math.absoluteValue
import org.skepsun.kototoro.core.image.tvboxSearchCoverModel
import org.skepsun.kototoro.core.ui.compose.contentCoverCacheKey
import org.skepsun.kototoro.core.util.ext.takeIfUsableImageUri
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.parsers.model.Content


internal fun Content.hasDistinctLargeCover(): Boolean {
    val largeCover = largeCoverUrl?.takeIfUsableImageUri() ?: return false
    return largeCover != coverUrl?.takeIfUsableImageUri()
}

internal fun homeHeroTonalColor(contentId: Long, darkTheme: Boolean): Color {
    val hue = (contentId.absoluteValue % 360L).toFloat()
    return Color.hsv(
        hue,
        saturation = if (darkTheme) 0.42f else 0.28f,
        value = if (darkTheme) 0.42f else 0.88f,
    )
}

@Composable
internal fun rememberHomeCoverRequest(
    context: android.content.Context,
    content: Content,
    allowCrossfade: Boolean,
    memoryCacheVariant: String,
): ImageRequest? {
    val primaryCoverUrl = content.coverUrl?.takeIfUsableImageUri()
    val fallbackCoverUrl = content.largeCoverUrl?.takeIfUsableImageUri()
    return remember(
        context,
        content.id,
        content.source.name,
        content.url,
        content.publicUrl,
        primaryCoverUrl,
        fallbackCoverUrl,
        allowCrossfade,
        memoryCacheVariant,
    ) {
        val resolvedCoverUrl = primaryCoverUrl ?: fallbackCoverUrl
        if (resolvedCoverUrl != null) {
            val cacheKey = contentCoverCacheKey(content, resolvedCoverUrl)
            return@remember ImageRequest.Builder(context)
                .data(resolvedCoverUrl)
                .memoryCacheKey(cacheKey.withMemoryCacheVariant(memoryCacheVariant))
                .diskCacheKey(cacheKey)
                .crossfade(allowCrossfade)
                .apply { mangaExtra(content) }
                .build()
        }
        if (content.url.startsWith("tvbox://item/")) {
            val fallbackCacheKey = contentCoverCacheKey(content, "tvbox-search-cover:${content.url}")
            return@remember ImageRequest.Builder(context)
                .data(tvboxSearchCoverModel(content))
                .memoryCacheKey(fallbackCacheKey.withMemoryCacheVariant(memoryCacheVariant))
                .diskCacheKey(fallbackCacheKey)
                .crossfade(allowCrossfade)
                .mangaExtra(content)
                .build()
        }
        null
    }
}

internal fun String?.withMemoryCacheVariant(variant: String): String? {
    return this?.let { "$it#$variant" }
}

