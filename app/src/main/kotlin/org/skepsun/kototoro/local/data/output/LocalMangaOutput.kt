package org.skepsun.kototoro.local.data.output

import com.hippo.unifile.UniFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Closeable
import org.skepsun.kototoro.core.prefs.DownloadFormat
import org.skepsun.kototoro.core.util.ext.MimeType
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.toFileNameSafe
import org.skepsun.kototoro.local.data.input.LocalContentParser
import org.skepsun.kototoro.local.data.LocalStorageRoot
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.io.File

sealed class LocalContentOutput(
    val rootFile: UniFile,
    protected val cacheDir: File,
) : Closeable {

    val rootUri get() = rootFile.uri

    abstract suspend fun mergeWithExisting()

    abstract suspend fun addCover(file: File, type: MimeType?)

    abstract suspend fun addPage(chapter: IndexedValue<ContentChapter>, file: File, pageNumber: Int, type: MimeType?)
    
    abstract suspend fun putChapterImages(chapterId: Long, remoteImages: Map<String, String>)

    abstract suspend fun flushChapter(chapter: ContentChapter): Boolean

    abstract suspend fun finish()

    abstract suspend fun cleanup()

    companion object {

        const val ENTRY_NAME_INDEX = "index.json"
        const val SUFFIX_TMP = ".tmp"
        private val mutex = Mutex()

        suspend fun getOrCreate(
            root: LocalStorageRoot,
            manga: Content,
            format: DownloadFormat,
            cacheDir: File,
        ): LocalContentOutput = withContext(Dispatchers.IO) {
            val targetFormat = if (format == DownloadFormat.AUTOMATIC) {
                if (manga.chapters.let { it != null && it.size <= 3 }) {
                    DownloadFormat.SINGLE_CBZ
                } else {
                    DownloadFormat.MULTIPLE_CBZ
                }
            } else {
                format
            }
            checkNotNull(getImpl(root, manga, onlyIfExists = false, format = targetFormat, cacheDir = cacheDir))
        }

        suspend fun get(root: LocalStorageRoot, manga: Content, cacheDir: File): LocalContentOutput? = withContext(Dispatchers.IO) {
            getImpl(root, manga, onlyIfExists = true, format = DownloadFormat.AUTOMATIC, cacheDir = cacheDir)
        }

        private suspend fun getImpl(
            root: LocalStorageRoot,
            manga: Content,
            onlyIfExists: Boolean,
            format: DownloadFormat,
            cacheDir: File,
        ): LocalContentOutput? {
            mutex.withLock {
                var i = 0
                val baseName = manga.title.toFileNameSafe()
                while (true) {
                    val fileName = if (i == 0) baseName else baseName + "_$i"
                    val dir = root.file.findFile(fileName)
                    val zip = root.file.findFile("$fileName.cbz")
                    i++
                    return when {
                        dir?.isDirectory == true -> {
                            if (canWriteTo(dir, manga, cacheDir)) {
                                LocalContentDirOutput(dir, manga, cacheDir)
                            } else {
                                continue
                            }
                        }

                        zip?.isFile == true -> if (canWriteTo(zip, manga, cacheDir)) {
                            LocalContentZipOutput(zip, manga, cacheDir)
                        } else {
                            continue
                        }

                        !onlyIfExists -> when (format) {
                            DownloadFormat.AUTOMATIC -> null
                            DownloadFormat.SINGLE_CBZ -> LocalContentZipOutput(
                                checkNotNull(root.file.createFile("$fileName.cbz")), manga, cacheDir,
                            )
                            DownloadFormat.MULTIPLE_CBZ -> LocalContentDirOutput(
                                checkNotNull(root.file.createDirectory(fileName)), manga, cacheDir,
                            )
                        }

                        else -> null
                    }
                }
            }
        }

        private suspend fun canWriteTo(file: UniFile, manga: Content, cacheDir: File): Boolean {
            val info = runCatchingCancellable {
                LocalContentParser(file, cacheDir).getContentInfo()
            }.onFailure {
                it.printStackTraceDebug()
            }.getOrNull() ?: return false
            return info.id == manga.id
        }
    }
}
