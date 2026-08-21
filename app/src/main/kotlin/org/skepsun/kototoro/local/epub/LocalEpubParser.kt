package org.skepsun.kototoro.local.epub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.util.longHashCode
import java.io.File
import java.util.zip.ZipFile

/**
 * 本地EPUB文件解析器
 *
 * 功能：
 * 1. 检测CBZ文件是否包含EPUB
 * 2. 解析EPUB并提取章节
 * 3. 生成Content对象供应用使用
 */
class LocalEpubParser(private val cbzFile: File, private val cache: EpubContentCache = EpubContentCache.getInstance()) {

    /**
     * 检查CBZ文件是否包含EPUB
     */
    suspend fun isEpubFile(): Boolean = withContext(Dispatchers.IO) {
        try {
            ZipFile(cbzFile).use { zip ->
                // 检查是否有mimetype文件（EPUB标准）
                val hasMimetype = zip.getEntry("mimetype") != null
                if (hasMimetype) {
                    return@withContext true
                }

                // 检查是否有META-INF/container.xml（EPUB标准）
                val hasContainer = zip.getEntry("META-INF/container.xml") != null
                if (hasContainer) {
                    return@withContext true
                }

                // 检查是否有.opf文件（EPUB内容文件）
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.endsWith(".opf", ignoreCase = true)) {
                        return@withContext true
                    }
                }

                false
            }
        } catch (e: Exception) {
            android.util.Log.e("LocalEpubParser", "Failed to check if file is EPUB", e)
            false
        }
    }

    /**
     * 解析EPUB文件并生成Content对象
     */
    suspend fun parseContent(): Content? = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("LocalEpubParser", "Parsing EPUB file: ${cbzFile.absolutePath}")
            android.util.Log.d("LocalEpubParser", "File exists: ${cbzFile.exists()}, size: ${cbzFile.length()} bytes")

            // 使用EpubReader解析EPUB
            val epubReader = EpubReaderImpl(cache)
            val epubContent = epubReader.readEpub(cbzFile)

            if (epubContent == null) {
                android.util.Log.e("LocalEpubParser", "Failed to read EPUB content from file")
                return@withContext null
            }

            android.util.Log.d("LocalEpubParser", "EPUB parsed successfully")
            android.util.Log.d("LocalEpubParser", "Title: ${epubContent.title}")
            android.util.Log.d("LocalEpubParser", "Author: ${epubContent.author}")
            android.util.Log.d("LocalEpubParser", "Chapters: ${epubContent.chapters.size}")

            // 生成Content对象
            val mangaId = cbzFile.absolutePath.longHashCode()
            val title = epubContent.title
            val author = epubContent.author

            // 生成章节列表
            val chapters = epubContent.chapters.map { epubChapter ->
                ContentChapter(
                    id = "${cbzFile.absolutePath}#chapter${epubChapter.index}".longHashCode(),
                    title = epubChapter.title,
                    number = (epubChapter.index + 1).toFloat(),
                    volume = 0,
                    // URL格式：file:///path/to/file.cbz#chapter/0
                    url = "file://${cbzFile.absolutePath}#chapter/${epubChapter.index}",
                    scanlator = title,
                    uploadDate = cbzFile.lastModified(),
                    branch = null,
                    source = org.skepsun.kototoro.core.model.LocalNovelSource,
                )
            }

            android.util.Log.d("LocalEpubParser", "Generated ${chapters.size} chapters")

            Content(
                id = mangaId,
                title = title,
                altTitles = emptySet(),
                url = "file://${cbzFile.absolutePath}",
                publicUrl = cbzFile.absolutePath,
                rating = -1f,
                contentRating = null,
                coverUrl = "", // EPUB封面可以后续添加
                tags = setOf(
                    ContentTag(
                        key = "epub",
                        title = "EPUB",
                        source = org.skepsun.kototoro.core.model.LocalNovelSource,
                    ),
                ),
                state = ContentState.FINISHED,
                authors = setOf(author),
                largeCoverUrl = null,
                description = "EPUB Ebook: $title",
                chapters = chapters,
                source = org.skepsun.kototoro.core.model.LocalNovelSource,
            )
        } catch (e: Exception) {
            android.util.Log.e("LocalEpubParser", "Failed to parse EPUB", e)
            e.printStackTrace()
            null
        }
    }

    /**
     * 获取EPUB章节内容
     *
     * @param chapterIndex 章节索引
     * @return 章节文本内容
     */
    suspend fun getChapterContent(chapterIndex: Int): String? = withContext(Dispatchers.IO) {
        try {
            val epubReader = EpubReaderImpl(cache)
            val epubContent = epubReader.readEpub(cbzFile) ?: return@withContext null

            epubContent.chapters.getOrNull(chapterIndex)?.content
        } catch (e: Exception) {
            android.util.Log.e("LocalEpubParser", "Failed to get chapter content", e)
            null
        }
    }
}
