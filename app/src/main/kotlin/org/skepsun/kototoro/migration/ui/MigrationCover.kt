package org.skepsun.kototoro.migration.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource

/** Cover request carrying the source, so covers that need source headers or cookies load. */
@Composable
fun rememberCoverRequest(content: Content): ImageRequest {
    val context = LocalContext.current
    return remember(content.id, content.coverUrl, content.source) {
        ImageRequest.Builder(context).data(content.coverUrl).mangaExtra(content).crossfade(true).build()
    }
}

@Composable
fun rememberCoverRequest(url: String?, source: ContentSource): ImageRequest {
    val context = LocalContext.current
    return remember(url, source) {
        ImageRequest.Builder(context).data(url).mangaSourceExtra(source).crossfade(true).build()
    }
}
