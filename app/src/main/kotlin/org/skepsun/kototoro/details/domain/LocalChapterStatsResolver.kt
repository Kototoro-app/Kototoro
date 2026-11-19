package org.skepsun.kototoro.details.domain

import android.content.Context
import androidx.collection.LruCache
import com.hippo.unifile.UniFile
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.isManga
import org.skepsun.kototoro.core.util.ext.isFileUri
import org.skepsun.kototoro.core.util.ext.isZipUri
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.local.data.input.LocalContentParser
import org.skepsun.kototoro.reader.novel.NovelContentLoader
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Page count and on-disk size of a downloaded chapter; [bytes] is null when the storage cannot report it and [pages]
 * is null for a video episode, which is a single file. A novel chapter reports only [words].
 */
data class LocalChapterStats(val pages: Int?, val bytes: Long?, val words: Int? = null)

/**
 * Reads the page count and size of a downloaded image chapter, the size of a downloaded video, or the length of a downloaded novel chapter, for the chapter list (#703).
 *
 * Only local storage is consulted, so nothing here reaches a source. Answers are remembered for the session; a failed
 * read is not, so the chapter is measured again the next time it scrolls into view.
 */
@Singleton
class LocalChapterStatsResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val novelContentLoader: NovelContentLoader,
    private val localRepository: LocalMangaRepository,
) {

    private val cache = LruCache<String, LocalChapterStats>(MAX_CACHED_CHAPTERS)
    private val permits = Semaphore(MAX_CONCURRENT_LOOKUPS)

    private fun ContentChapter.isVideo(): Boolean = source.getContentType().let {
        it == ContentType.VIDEO || it == ContentType.HENTAI_VIDEO
    }

    private fun ContentChapter.isNovel(): Boolean = source.getContentType().let {
        it == ContentType.NOVEL || it == ContentType.HENTAI_NOVEL
    }

    fun supports(chapter: ContentChapter): Boolean =
        chapter.isVideo() || chapter.isNovel() || chapter.source.getContentType().isManga()

    suspend fun stats(chapter: ContentChapter): LocalChapterStats? {
        if (!supports(chapter)) return null
        val key = "${chapter.id}:${chapter.url}"
        cache[key]?.let { return it }
        return permits.withPermit {
            runCatchingCancellable {
                withContext(Dispatchers.IO) {
                    if (chapter.isNovel()) return@withContext LocalChapterStats(null, null, novelWords(chapter))
                    if (chapter.isVideo()) return@withContext LocalChapterStats(null, videoSize(chapter.url.toUri()))
                    val pages = LocalContentParser(chapter.url.toUri()).getPages(chapter)
                    LocalChapterStats(pages.size, measure(pages))
                }
            }.getOrNull()
        }?.also { cache.put(key, it) }
    }

    /**
     * Reads the downloaded text through the reader's own loader, with the local repository so a chapter it cannot find
     * on disk ends as "unknown" instead of reaching a source. Text rules are off: the count is of the saved text.
     */
    private suspend fun novelWords(chapter: ContentChapter): Int? {
        val text = novelContentLoader.loadChapterContent(
            repository = localRepository,
            chapter = chapter,
            replaceRulesEnabled = false,
        )
        return countWords(text).takeIf { it > 0 }
    }

    private fun videoSize(uri: android.net.Uri): Long? = when {
        uri.isFileUri() -> File(uri.path ?: return null).takeIf { it.isFile }?.length()
        uri.scheme == "content" -> UniFile.fromUri(context, uri)?.takeIf { it.isFile }?.length()
        else -> null
    }

    /** Sums the files behind [pages]; one unreadable page (SAF, PDF) makes the whole size unknown rather than wrong. */
    private fun measure(pages: List<ContentPage>): Long? {
        var total = 0L
        val archives = HashMap<String, ZipFile>()
        try {
            for (page in pages) {
                val uri = page.url.toUri()
                total += when {
                    uri.isZipUri() -> {
                        val path = uri.path ?: return null
                        val zip = archives.getOrPut(path) { ZipFile(File(path)) }
                        zip.getEntry(uri.fragment.orEmpty().removePrefix("/"))?.size?.takeIf { it >= 0 } ?: return null
                    }

                    uri.isFileUri() -> File(uri.path ?: return null).takeIf { it.isFile }?.length() ?: return null
                    else -> return null
                }
            }
        } finally {
            archives.values.forEach { runCatching { it.close() } }
        }
        return total
    }

    private companion object {

        /** One per CJK character, one per run of other letters or digits, so it reads as characters or words. */
        fun countWords(text: CharSequence): Int {
            var count = 0
            var inWord = false
            var i = 0
            while (i < text.length) {
                val cp = Character.codePointAt(text, i)
                i += Character.charCount(cp)
                when {
                    isCjk(cp) -> {
                        count++
                        inWord = false
                    }

                    Character.isLetterOrDigit(cp) -> {
                        if (!inWord) count++
                        inWord = true
                    }

                    else -> inWord = false
                }
            }
            return count
        }

        private fun isCjk(cp: Int): Boolean = Character.UnicodeScript.of(cp).let {
            it == Character.UnicodeScript.HAN || it == Character.UnicodeScript.HIRAGANA ||
                it == Character.UnicodeScript.KATAKANA || it == Character.UnicodeScript.HANGUL
        }

        const val MAX_CACHED_CHAPTERS = 1024
        const val MAX_CONCURRENT_LOOKUPS = 2
    }
}
