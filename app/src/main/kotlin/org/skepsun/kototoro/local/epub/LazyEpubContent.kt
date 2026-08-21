package org.skepsun.kototoro.local.epub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lazy-loading wrapper for EPUB content.
 *
 * This class provides lazy loading of EPUB chapters to improve performance
 * when dealing with large EPUB files. Chapters are only parsed when accessed.
 *
 * Requirements: 11.1 - Implement lazy loading for EPUB chapters
 */
class LazyEpubContent(
    val title: String,
    val author: String,
    private val file: File,
    private val epubReader: EpubReader,
    val chapterCount: Int
) {

    // Cache for loaded chapters
    private val loadedChapters = mutableMapOf<Int, EpubChapter>()

    /**
     * Gets a chapter by index, loading it lazily if not already loaded.
     *
     * @param index The chapter index (0-based)
     * @return The chapter, or null if index is out of bounds or loading fails
     */
    suspend fun getChapter(index: Int): EpubChapter? = withContext(Dispatchers.IO) {
        if (index < 0 || index >= chapterCount) {
            return@withContext null
        }

        // Return cached chapter if available
        loadedChapters[index]?.let {
            return@withContext it
        }

        // Load chapter on demand
        val content = epubReader.readEpub(file) ?: return@withContext null

        if (index >= content.chapters.size) {
            return@withContext null
        }

        val chapter = content.chapters[index]
        loadedChapters[index] = chapter

        chapter
    }

    /**
     * Gets multiple chapters at once (for preloading).
     *
     * @param indices The chapter indices to load
     * @return Map of index to chapter
     */
    suspend fun getChapters(indices: List<Int>): Map<Int, EpubChapter> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<Int, EpubChapter>()

        // Filter valid indices
        val validIndices = indices.filter { it in 0 until chapterCount }

        // Return already loaded chapters
        validIndices.forEach { index ->
            loadedChapters[index]?.let { chapter ->
                result[index] = chapter
            }
        }

        // Load remaining chapters
        val toLoad = validIndices.filter { it !in result }
        if (toLoad.isNotEmpty()) {
            val content = epubReader.readEpub(file)
            if (content != null) {
                toLoad.forEach { index ->
                    if (index < content.chapters.size) {
                        val chapter = content.chapters[index]
                        loadedChapters[index] = chapter
                        result[index] = chapter
                    }
                }
            }
        }

        result
    }

    /**
     * Clears loaded chapters from memory.
     */
    fun clearLoadedChapters() {
        loadedChapters.clear()
    }

    /**
     * Gets the number of currently loaded chapters.
     */
    fun getLoadedChapterCount(): Int = loadedChapters.size
}
