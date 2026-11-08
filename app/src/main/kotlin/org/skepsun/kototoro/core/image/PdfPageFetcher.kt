package org.skepsun.kototoro.core.image

import android.content.ContentResolver
import android.net.Uri
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import coil3.toAndroidUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.skepsun.kototoro.local.data.LocalPdfPages
import org.skepsun.kototoro.local.data.isPdfPageUri
import coil3.Uri as CoilUri

class PdfPageFetcher(
    private val uri: Uri,
    private val options: Options,
    private val contentResolver: ContentResolver,
) : Fetcher {

    override suspend fun fetch() = runInterruptible(Dispatchers.IO) {
        val targetDimension = maxOf(
            options.size.width.pxOrElse { DEFAULT_MAX_DIMENSION },
            options.size.height.pxOrElse { DEFAULT_MAX_DIMENSION },
        ).coerceIn(MIN_DIMENSION, DEFAULT_MAX_DIMENSION)
        val bitmap = LocalPdfPages.renderPage(
            contentResolver = contentResolver,
            pageUri = uri,
            maxDimensionPx = targetDimension,
        )
        ImageFetchResult(
            image = bitmap.asImage(),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<CoilUri> {

        override fun create(
            data: CoilUri,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? {
            val androidUri = data.toAndroidUri()
            return if (androidUri.isPdfPageUri()) {
                PdfPageFetcher(androidUri, options, options.context.contentResolver)
            } else {
                null
            }
        }
    }

    private companion object {
        const val MIN_DIMENSION = 256
        const val DEFAULT_MAX_DIMENSION = 2560
    }
}
