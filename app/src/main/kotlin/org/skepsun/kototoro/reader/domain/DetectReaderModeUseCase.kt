package org.skepsun.kototoro.reader.domain

import android.graphics.BitmapFactory
import android.util.Size
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.network.imageproxy.ImageProxyInterceptor
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.core.util.ext.isFileUri
import org.skepsun.kototoro.core.util.ext.isZipUri
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.reader.ui.ReaderState
import java.io.InputStream
import java.util.zip.ZipFile
import javax.inject.Inject
import kotlin.math.roundToInt
import android.util.Base64
import java.io.ByteArrayInputStream

class DetectReaderModeUseCase @Inject constructor(
    private val dataRepository: ContentDataRepository,
    private val settings: AppSettings,
    private val mangaRepositoryFactory: ContentRepository.Factory,
    @ContentHttpClient private val okHttpClient: OkHttpClient,
    private val imageProxyInterceptor: ImageProxyInterceptor,
) {

    suspend operator fun invoke(manga: Content, state: ReaderState?): ReaderMode {
        dataRepository.getReaderMode(manga.id)?.let { return it }
        val defaultMode = settings.defaultReaderMode
        if (!settings.isReaderModeDetectionEnabled || defaultMode == ReaderMode.WEBTOON) {
            return defaultMode
        }
        val chapter = state?.let { manga.findChapterById(it.chapterId) }
            ?: manga.chapters?.firstOrNull()
            ?: error("There are no chapters in this manga")
        val repo = mangaRepositoryFactory.create(manga.source)
        val pages = repo.getPages(chapter)
        return runCatchingCancellable {
            val isWebtoon = guessContentIsWebtoon(repo, pages)
            if (isWebtoon) ReaderMode.WEBTOON else defaultMode
        }.onSuccess {
            dataRepository.saveReaderMode(manga, it)
        }.onFailure {
            it.printStackTraceDebug()
        }.getOrDefault(defaultMode)
    }

    /**
     * Automatic determine type of manga by page size
     * @return ReaderMode.WEBTOON if page is wide
     */
    private suspend fun guessContentIsWebtoon(repository: ContentRepository, pages: List<ContentPage>): Boolean {
        if (pages.isEmpty()) return false
        val pageIndex = (pages.size * 0.3).roundToInt()
        val page = pages.getOrNull(pageIndex) ?: return false
        val url = runCatching { repository.getPageUrl(page) }.getOrNull() ?: return false
        val uri = url.toUri()

        val size = runCatching {
            when {
                url.startsWith("data:", ignoreCase = true) -> {
                    val base64 = url.substringAfter("base64,", "")
                    val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrDefault(ByteArray(0))
                    if (bytes.isEmpty()) return@runCatching null
                    ByteArrayInputStream(bytes).use { getBitmapSize(it) }
                }
                uri.isZipUri() -> runInterruptible(Dispatchers.IO) {
                    ZipFile(uri.schemeSpecificPart).use { zip ->
                        val entry = zip.getEntry(uri.fragment)
                        zip.getInputStream(entry).use {
                            getBitmapSize(it)
                        }
                    }
                }

                uri.isFileUri() -> runInterruptible(Dispatchers.IO) {
                    uri.toFile().inputStream().use {
                        getBitmapSize(it)
                    }
                }

                else -> {
                    val request = PageLoader.createPageRequest(url, page)
                    imageProxyInterceptor.interceptPageRequest(request, okHttpClient).use {
                        runInterruptible(Dispatchers.IO) {
                            getBitmapSize(it.body?.byteStream())
                        }
                    }
                }
            }
        }.getOrNull() ?: return false
        return size.width * MIN_WEBTOON_RATIO < size.height
    }

    companion object {

        private const val MIN_WEBTOON_RATIO = 1.8

        private fun getBitmapSize(input: InputStream?): Size {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(input, null, options)?.recycle()
            val imageHeight: Int = options.outHeight
            val imageWidth: Int = options.outWidth
            check(imageHeight > 0 && imageWidth > 0)
            return Size(imageWidth, imageHeight)
        }
    }
}
