package org.skepsun.kototoro.local.epub

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LocalEpubSource - 独立的EPUB本地源
 *
 * 架构设计：
 * - EPUB文件存储在独立的epub文件夹中
 * - 不依赖manga文件夹和index.json
 * - 直接解析EPUB文件获取章节列表
 * - 使用epub://协议的URL标识章节
 *
 * 存储结构：
 * files/epub/{manga_id}/book.epub
 */
@Singleton
class LocalEpubSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val epubStorageManager: EpubStorageManager,
    private val mangaDatabase: org.skepsun.kototoro.core.db.MangaDatabase,
    private val epubContentCache: EpubContentCache,
) : ContentSource {

    override val name: String = "Local EPUB"
    override val locale: String = ""
    override val contentType: ContentType = ContentType.NOVEL

    /**
     * 获取漫画详情，包括章节列表
     * 从EPUB文件中解析章节
     *
     * 新架构：支持多个EPUB文件，从数据库读取所有内部章节
     */
    suspend fun getDetails(manga: Content): Content = withContext(Dispatchers.IO) {
        val epubDir = epubStorageManager.getEpubDir(manga.id)
        if (!epubDir.exists()) {
            throw IllegalStateException("EPUB directory not found for manga ${manga.id}")
        }

        // 获取所有EPUB文件
        val epubFiles = epubDir.listFiles { file ->
            file.isFile && file.name.endsWith(".epub", ignoreCase = true)
        }

        if (epubFiles.isNullOrEmpty()) {
            throw IllegalStateException("No EPUB files found for manga ${manga.id}")
        }

        android.util.Log.d("LocalEpubSource", "Found ${epubFiles.size} EPUB files for manga ${manga.id}")

        // 从数据库读取所有内部章节
        val epubChapterMappingDao = mangaDatabase.getEpubChapterMappingDao()
        val allMappings = epubChapterMappingDao.findByContentId(manga.id)

        android.util.Log.d("LocalEpubSource", "Found ${allMappings.size} chapter mappings in database")

        if (allMappings.isEmpty()) {
            // 如果数据库中没有映射，尝试解析第一个EPUB文件（向后兼容）
            val epubFile = epubFiles.first()
            android.util.Log.d("LocalEpubSource", "No mappings found, parsing EPUB file: ${epubFile.name}")

            val parser = LocalEpubParser(epubFile, epubContentCache)
            val epubContent = parser.parseContent()
                ?: throw IllegalStateException("Failed to parse EPUB file: ${epubFile.absolutePath}")

            val chapters = epubContent.chapters ?: emptyList()
            val epubChapters = chapters.mapIndexed { index, chapter ->
                ContentChapter(
                    id = generateChapterId(manga.id, index),
                    title = chapter.title ?: "Chapter ${index + 1}",
                    number = index.toFloat(),
                    volume = 0,
                    url = buildEpubUrl(manga.id, index),
                    scanlator = null,
                    uploadDate = 0L,
                    branch = null,
                    source = this@LocalEpubSource,
                )
            }

            return@withContext manga.copy(
                chapters = epubChapters,
                source = this@LocalEpubSource,
            )
        }

        // 从数据库映射生成章节列表
        // 按照parentChapterId和chapterIndex排序，保持EPUB文件的原始顺序
        val epubChapters = allMappings
            .sortedWith(compareBy({ it.parentChapterId }, { it.chapterIndex }))
            .mapIndexed { globalIndex, mapping: org.skepsun.kototoro.core.db.entity.EpubChapterMappingEntity ->
                ContentChapter(
                    id = mapping.internalChapterId,
                    title = mapping.chapterTitle,
                    number = (globalIndex + 1).toFloat(),  // 使用全局索引作为章节号，从1开始
                    volume = 0,
                    url = "epub://${manga.id}/chapter/${globalIndex}",  // 使用全局索引，不是chapterIndex
                    scanlator = mapping.epubFileName,  // 使用epubFileName作为卷名（用于分组）
                    uploadDate = mapping.createdAt,
                    branch = null,
                    source = manga.source,  // 保留原始source
                )
            }

        android.util.Log.d("LocalEpubSource", "Loaded ${epubChapters.size} EPUB chapters from database")

        // 返回更新后的manga，保留原始source
        manga.copy(
            chapters = epubChapters,
        )
    }

    /**
     * 获取漫画详情，但保留原始source信息
     * 用于下载完成后解析章节，避免覆盖原始来源（如NoveliaWenku）
     */
    suspend fun getDetailsPreservingSource(manga: Content): Content = withContext(Dispatchers.IO) {
        val epubFile = epubStorageManager.getEpubFile(manga.id)
            ?: throw IllegalStateException("EPUB file not found for manga ${manga.id}")

        if (!epubFile.exists()) {
            throw IllegalStateException("EPUB file does not exist: ${epubFile.absolutePath}")
        }

        android.util.Log.d("LocalEpubSource", "Loading EPUB from: ${epubFile.absolutePath} (preserving source)")

        // 解析EPUB文件
        val parser = LocalEpubParser(epubFile, epubContentCache)
        val epubContent = parser.parseContent()
            ?: throw IllegalStateException("Failed to parse EPUB file: ${epubFile.absolutePath}")

        val chapters = epubContent.chapters ?: emptyList()
        android.util.Log.d("LocalEpubSource", "Parsed ${chapters.size} chapters from EPUB")

        // 转换章节，使用epub://协议的URL，但保留原始source
        val epubChapters = chapters.mapIndexed { index, chapter ->
            ContentChapter(
                id = generateChapterId(manga.id, index),
                title = chapter.title ?: "Chapter ${index + 1}",
                number = index.toFloat(),
                volume = 0,
                url = buildEpubUrl(manga.id, index),
                scanlator = null,
                uploadDate = 0L,
                branch = null,  // EPUB chapters don't have branches
                source = manga.source,  // 保留原始source，不覆盖为LocalEpubSource
            )
        }

        android.util.Log.d("LocalEpubSource", "Converted to ${epubChapters.size} EPUB chapters (source preserved: ${manga.source.name})")

        // 返回更新后的manga，保留原始source
        manga.copy(
            chapters = epubChapters,
            // 不修改source字段，保留原始来源
        )
    }

    /**
     * 获取章节页面
     * 对于EPUB，返回一个特殊的页面，内容由EpubReader加载
     */
    suspend fun getPages(chapter: ContentChapter): List<ContentPage> = withContext(Dispatchers.IO) {
        val (mangaId, chapterIndex) = parseEpubUrl(chapter.url)

        android.util.Log.d("LocalEpubSource", "Getting pages for chapter: mangaId=$mangaId, index=$chapterIndex")

        // 返回一个特殊的页面，URL指向EPUB章节
        // 实际内容由NovelContentLoader通过EpubReader加载
        listOf(
            ContentPage(
                id = 0,
                url = chapter.url,
                preview = null,
                source = this@LocalEpubSource,
            )
        )
    }

    /**
     * 检查是否有EPUB文件
     * 支持新旧两种格式：book.epub 和 chapter_{id}.epub
     */
    fun hasEpubFile(mangaId: Long): Boolean {
        val epubDir = epubStorageManager.getEpubDir(mangaId)
        if (!epubDir.exists()) {
            return false
        }

        // 检查是否有任何.epub文件
        val epubFiles = epubDir.listFiles { file ->
            file.isFile && file.name.endsWith(".epub", ignoreCase = true)
        }

        return !epubFiles.isNullOrEmpty()
    }

    /**
     * 生成稳定的章节ID
     * 使用manga ID和章节索引生成
     */
    private fun generateChapterId(mangaId: Long, chapterIndex: Int): Long {
        // 使用简单的组合算法生成唯一ID
        // 高32位：manga ID的低32位
        // 低32位：章节索引
        return (mangaId shl 32) or chapterIndex.toLong()
    }

    /**
     * 构建EPUB URL
     * 格式：epub://{manga_id}/chapter/{index}
     */
    private fun buildEpubUrl(mangaId: Long, chapterIndex: Int): String {
        return "epub://$mangaId/chapter/$chapterIndex"
    }

    /**
     * 解析EPUB URL
     * 返回 (mangaId, chapterIndex)
     */
    private fun parseEpubUrl(url: String): Pair<Long, Int> {
        // epub://123456/chapter/0
        val regex = Regex("epub://(\\d+)/chapter/(\\d+)")
        val match = regex.matchEntire(url)
            ?: throw IllegalArgumentException("Invalid EPUB URL: $url")

        val mangaId = match.groupValues[1].toLong()
        val chapterIndex = match.groupValues[2].toInt()

        return mangaId to chapterIndex
    }

    companion object {
        /**
         * 检查URL是否是EPUB URL
         */
        fun isEpubUrl(url: String): Boolean {
            return url.startsWith("epub://")
        }

        /**
         * 检查章节是否是EPUB章节
         */
        fun isEpubChapter(chapter: ContentChapter): Boolean {
            return isEpubUrl(chapter.url)
        }
    }
}
