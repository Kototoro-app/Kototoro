package org.skepsun.kototoro.reader.novel

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.db.dao.EpubChapterMappingDao
import org.skepsun.kototoro.local.epub.EpubContent
import org.skepsun.kototoro.local.epub.EpubContentCache
import org.skepsun.kototoro.local.epub.EpubError
import org.skepsun.kototoro.local.epub.EpubErrorHandler
import org.skepsun.kototoro.local.epub.EpubFileManager
import org.skepsun.kototoro.local.epub.EpubReaderImpl
import org.skepsun.kototoro.local.epub.parseEpubChapterReference
import org.skepsun.kototoro.local.epub.resolveEpubFile
import org.skepsun.kototoro.parsers.model.ContentChapter
import java.io.File

/**
 * Result of loading an EPUB internal chapter
 */
data class EpubChapterLoadResult(
    val content: String,
    val epubFile: File,
    val chapterHref: String?,  // Chapter path in EPUB (e.g., "OEBPS/Text/content_1.html")
)

/**
 * Handles loading of EPUB internal chapters.
 *
 * Responsibilities:
 * - Extract chapter index from URL
 * - Locate EPUB file using parent chapter ID
 * - Read specific chapter at extracted index
 * - Handle errors for missing files
 *
 * Performance optimizations:
 * - Uses LRU cache for parsed content (Requirement 11.2)
 * - All operations run on background threads (Requirement 11.3)
 * - Supports lazy loading (Requirement 11.1)
 *
 * Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 11.1, 11.2, 11.3
 */
class EpubInternalChapterLoader(
    private val context: Context,
    private val epubFileManager: EpubFileManager,
    private val epubChapterMappingDao: EpubChapterMappingDao,
    private val epubContentCache: EpubContentCache = EpubContentCache.getInstance()
) {

    private val epubReader = EpubReaderImpl(epubContentCache)

    /**
     * Loads an EPUB internal chapter.
     *
     * @param chapter The chapter to load (must have URL format: ...#chapter/N)
     * @return Result containing the chapter content, EPUB file, and chapter path
     *
     * Performance: Runs on IO dispatcher (Requirement 11.3)
     */
    suspend fun loadEpubInternalChapter(chapter: ContentChapter): Result<EpubChapterLoadResult> = withContext(Dispatchers.IO) {
        try {
            // Extract chapter index from URL (Requirement 6.4)
            val chapterIndex = extractChapterIndexFromUrl(chapter.url)
            if (chapterIndex == null) {
                val error = EpubError.ChapterLoadError.InvalidUrl(chapter.url)
                return@withContext EpubErrorHandler.createFailure(error, "loadEpubInternalChapter")
            }

            // Locate EPUB file using parent chapter ID (Requirement 6.5)
            val epubFile = findEpubFileForChapter(chapter)
            if (epubFile == null) {
                val error = EpubError.ChapterLoadError.ChapterNotFound(chapter.id)
                return@withContext EpubErrorHandler.createFailure(error, "loadEpubInternalChapter")
            }

            // Check if file exists (Requirement 6.7, 10.4)
            if (!epubFile.exists()) {
                val error = EpubError.FileSystemError.FileNotFound(epubFile.name)
                return@withContext EpubErrorHandler.createFailure(error, "loadEpubInternalChapter")
            }

            // Read EPUB content (with caching)
            val epubContent = loadEpubContent(epubFile)
            if (epubContent == null) {
                val error = EpubError.ParseError.CorruptedFile(epubFile.name)
                return@withContext EpubErrorHandler.createFailure(error, "loadEpubInternalChapter")
            }

            // Read specific chapter at extracted index (Requirement 6.6, 10.3)
            if (chapterIndex < 0 || chapterIndex >= epubContent.chapters.size) {
                val error = EpubError.ChapterLoadError.IndexOutOfBounds(
                    chapterIndex,
                    epubContent.chapters.size
                )
                return@withContext EpubErrorHandler.createFailure(error, "loadEpubInternalChapter")
            }

            val epubChapter = epubContent.chapters[chapterIndex]

            // Format the content
            val content = buildString {
                append("【${epubChapter.title}】\n\n")
                append(epubChapter.content)
            }

            Result.success(
                EpubChapterLoadResult(
                    content = content,
                    epubFile = epubFile,
                    chapterHref = epubChapter.href
                )
            )
        } catch (e: Exception) {
            val error = EpubError.ChapterLoadError.LoadFailed(chapter.id, e)
            EpubErrorHandler.createFailure(error, "loadEpubInternalChapter")
        }
    }

    /**
     * Extracts chapter index from URL fragment.
     * URL format: file:///path/to/file.epub#chapter/N
     *
     * @param url The chapter URL
     * @return The chapter index, or null if extraction fails
     */
    private fun extractChapterIndexFromUrl(url: String): Int? {
        return parseEpubChapterReference(url)?.chapterIndex
    }

    /**
     * Finds the EPUB file for a given chapter.
     *
     * Strategy:
     * 1. Try to find by parent chapter ID in database mapping
     * 2. Try to extract file path from URL
     * 3. Try to find in epub directory by pattern matching
     *
     * @param chapter The chapter
     * @return The EPUB file, or null if not found
     */
    private suspend fun findEpubFileForChapter(chapter: ContentChapter): File? {
        // Strategy 1: Look up in database mapping
        val mapping = epubChapterMappingDao.getById(chapter.id)
        if (mapping != null) {
            val file = resolveEpubFile(context, mapping.epubFilePath)
            if (file?.exists() == true) {
                return file
            }
        }

        // Strategy 2: Extract file path from URL
        parseEpubChapterReference(chapter.url)?.let { reference ->
            val file = resolveEpubFile(context, reference.fileReference)
            if (file?.exists() == true) {
                return file
            }
        }

        // Strategy 3: Try to find by parent chapter ID using file manager
        // Extract parent ID from chapter ID (if it follows the pattern)
        val parentId = extractParentIdFromChapterId(chapter.id)
        if (parentId != null) {
            val file = epubFileManager.findEpubFile(context, parentId)
            if (file != null && file.exists()) {
                return file
            }
        }

        return null
    }

    /**
     * Extracts parent chapter ID from internal chapter ID.
     * Uses the formula: internalChapterId = parentChapterId + (chapterIndex * 1000000) + 1
     *
     * @param internalChapterId The internal chapter ID
     * @return The parent chapter ID, or null if extraction fails
     */
    private fun extractParentIdFromChapterId(internalChapterId: Long): Long? {
        // If the ID is less than 1,000,000, it might be a direct parent ID
        if (internalChapterId < 1_000_000) {
            return internalChapterId
        }

        // Otherwise, extract using the formula
        val adjusted = internalChapterId - 1
        return adjusted % 1_000_000
    }

    /**
     * Loads EPUB content from file with caching.
     *
     * @param file The EPUB file
     * @return The EPUB content, or null if parsing fails
     *
     * Performance: Uses LRU cache (Requirement 11.2)
     */
    private suspend fun loadEpubContent(file: File): EpubContent? {
        // The cache is now handled by EpubReaderImpl
        return epubReader.readEpub(file)
    }

    /**
     * Clears the EPUB content cache.
     */
    fun clearCache() {
        epubContentCache.clear()
    }

    /**
     * Gets the cache instance for external management.
     */
    fun getCache(): EpubContentCache = epubContentCache

    companion object {
        private const val TAG = "EpubInternalChapterLoader"
    }
}
