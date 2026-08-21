package org.skepsun.kototoro.local.epub

import android.content.Context
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages EPUB file operations including storage location, file retrieval, and existence checks.
 */
interface EpubFileManager {
    /**
     * Gets the directory where EPUB files are stored.
     * Falls back to internal storage if external storage is unavailable.
     *
     * @param context Android context
     * @return Directory for EPUB storage
     */
    fun getEpubDirectory(context: Context): File

    /**
     * Gets the file path for a specific EPUB.
     *
     * @param context Android context
     * @param fileName EPUB filename
     * @return File object for the EPUB
     */
    fun getEpubFile(context: Context, fileName: String): File

    /**
     * Checks if an EPUB is downloaded by searching for files matching the pattern.
     * Pattern: "chapter_{chapterId}_*.epub"
     *
     * @param context Android context
     * @param chapterId Parent chapter ID
     * @return True if EPUB file exists
     */
    fun isEpubDownloaded(context: Context, chapterId: Long): Boolean

    /**
     * Finds EPUB file by parent chapter ID.
     *
     * @param context Android context
     * @param parentChapterId Parent chapter ID
     * @return File object if found, null otherwise
     */
    fun findEpubFile(context: Context, parentChapterId: Long): File?
}

/**
 * Default implementation of EpubFileManager.
 */
@Singleton
class EpubFileManagerImpl @Inject constructor() : EpubFileManager {

    override fun getEpubDirectory(context: Context): File {
        // Try external storage first
        val externalDir = context.getExternalFilesDir("epub")
        if (externalDir != null && (externalDir.exists() || externalDir.mkdirs())) {
            return externalDir
        }

        // Fallback to internal storage
        val internalDir = File(context.filesDir, "epub")
        if (!internalDir.exists()) {
            internalDir.mkdirs()
        }
        return internalDir
    }

    override fun getEpubFile(context: Context, fileName: String): File {
        val epubDir = getEpubDirectory(context)
        return File(epubDir, fileName)
    }

    override fun isEpubDownloaded(context: Context, chapterId: Long): Boolean {
        return findEpubFile(context, chapterId) != null
    }

    override fun findEpubFile(context: Context, parentChapterId: Long): File? {
        val epubDir = getEpubDirectory(context)
        if (!epubDir.exists()) {
            return null
        }

        // Pattern: chapter_{chapterId}_*.epub
        val pattern = "chapter_${parentChapterId}_"

        return epubDir.listFiles { file ->
            file.isFile &&
            file.extension == "epub" &&
            file.nameWithoutExtension.startsWith(pattern)
        }?.firstOrNull()
    }
}
