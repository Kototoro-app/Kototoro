package org.skepsun.kototoro.core.image

import android.content.ContentResolver
import android.net.Uri
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.toAndroidUri
import kotlinx.coroutines.runInterruptible
import okio.Path.Companion.toPath
import okio.Buffer
import okio.FileSystem
import okio.openZip
import org.skepsun.kototoro.core.util.MimeTypes
import org.skepsun.kototoro.core.util.ext.isZipUri
import org.skepsun.kototoro.core.util.ext.isContentZipUri
import org.skepsun.kototoro.core.util.ext.toUnderlyingZipUri
import java.io.IOException
import java.util.zip.ZipInputStream
import coil3.Uri as CoilUri

class CbzFetcher(
    private val uri: Uri,
    private val options: Options,
    private val contentResolver: ContentResolver,
) : Fetcher {

    override suspend fun fetch() = runInterruptible {
        val entryName = requireNotNull(uri.fragment)
        if (uri.isContentZipUri()) {
            val source = Buffer()
            val input = checkNotNull(contentResolver.openInputStream(uri.toUnderlyingZipUri())) {
                "Cannot open $uri"
            }
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && entry.name != entryName) {
                    entry = zip.nextEntry
                }
                if (entry == null) throw IOException("ZIP entry not found: $entryName")
                source.write(zip.readBytes())
            }
            return@runInterruptible SourceFetchResult(
                source = ImageSource(source, FileSystem.SYSTEM),
                mimeType = MimeTypes.getMimeTypeFromExtension(entryName)?.toString(),
                dataSource = DataSource.DISK,
            )
        }
        val filePath = uri.schemeSpecificPart.toPath()
        val fs = options.fileSystem.openZip(filePath)
        SourceFetchResult(
            source = ImageSource(entryName.toPath(), fs),
            mimeType = MimeTypes.getMimeTypeFromExtension(entryName)?.toString(),
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<CoilUri> {

        override fun create(
            data: CoilUri,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher? {
            val androidUri = data.toAndroidUri()
            return if (androidUri.isZipUri() || androidUri.isContentZipUri()) {
                CbzFetcher(androidUri, options, options.context.contentResolver)
            } else {
                null
            }
        }
    }
}
