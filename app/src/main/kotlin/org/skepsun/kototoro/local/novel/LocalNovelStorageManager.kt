package org.skepsun.kototoro.local.novel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.skepsun.kototoro.core.util.ext.toFileNameSafe
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 简单的本地小说存储管理器（HTML 版）。
 *
 * 目录结构：
 * files/novel/{mangaDir}/
 *   - index.json (未来扩展用)
 *   - chapter_{chapterId}_{slug}.html
 *   - images/{chapterId}/{index}.{ext}
 *
 * 命名规则复用漫画的安全文件名逻辑，优先使用标题+ID 组合以避免冲突。
 */
class LocalNovelStorageManager(
    private val novelsRoot: File,
) {

    init {
        novelsRoot.mkdirs()
    }

    /**
     * 返回当前漫画的根目录，形如 {id}_{titleSafe}
     */
    fun resolveContentDir(manga: Content): File {
        val titleSafe = manga.title.toFileNameSafe()
        val dirName = if (manga.id != 0L) {
            "${manga.id}_$titleSafe"
        } else {
            titleSafe.ifBlank { "novel" }
        }
        return File(novelsRoot, dirName).also { it.mkdirs() }
    }

    /**
     * 将章节 HTML 写入本地文件，返回文件句柄。
     */
    suspend fun writeChapterHtml(manga: Content, chapter: ContentChapter, html: String): File {
        val mangaDir = resolveContentDir(manga)
        val chapterFile = File(mangaDir, chapterFileName(chapter))
        runInterruptible(Dispatchers.IO) {
            chapterFile.parentFile?.mkdirs()
            chapterFile.writeText(html)
        }
        return chapterFile
    }

    /**
     * 章节图片的本地文件路径（未写入）。
     */
    fun resolveImageFile(manga: Content, chapter: ContentChapter, index: Int, ext: String): File {
        val mangaDir = resolveContentDir(manga)
        val imagesDir = File(mangaDir, "images/${chapter.id}")
        imagesDir.mkdirs()
        val safeExt = ext.removePrefix(".")
        return File(imagesDir, "${index + 1}.$safeExt")
    }

    /**
     * 写入 index.json 供后续本地源读取：包含漫画元数据与章节列表（文件名）。
     */
    suspend fun writeIndex(manga: Content, chapters: List<ContentChapter>) {
        val dir = resolveContentDir(manga)
        val index = org.skepsun.kototoro.local.data.ContentIndex(null)
        index.setContentInfo(manga)
        chapters.forEachIndexed { i, c ->
            index.addChapter(IndexedValue(i, c), chapterFileName(c))
        }
        runInterruptible(Dispatchers.IO) {
            File(dir, "index.json").writeText(index.toString())
        }
    }

    private fun chapterFileName(chapter: ContentChapter): String {
        val slug = chapter.title?.toFileNameSafe().orEmpty()
        val base = buildString {
            append("chapter_")
            append(chapter.id)
            if (slug.isNotBlank()) {
                append('_')
                append(slug.take(32))
            }
        }
        return "$base.html"
    }
}
