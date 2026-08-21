package org.skepsun.kototoro.local.epub

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.db.dao.EpubChapterMappingDao
import org.skepsun.kototoro.history.data.HistoryDao
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles migration of legacy .cbz files that contain EPUB content to the new .epub format.
 * Also migrates old chapter IDs in history to new chapter IDs.
 */
interface LegacyEpubMigration {
    /**
     * Detects .cbz files that contain EPUB content.
     *
     * @param context Android context
     * @return List of .cbz files that are actually EPUBs
     */
    suspend fun detectLegacyCbzFiles(context: Context): List<File>

    /**
     * Converts a .cbz file to .epub format.
     *
     * @param cbzFile The .cbz file to convert
     * @return The converted .epub file, or null if conversion failed
     */
    suspend fun convertCbzToEpub(cbzFile: File): File?

    /**
     * Migrates old chapter IDs in history to new chapter IDs.
     * Attempts to find equivalent chapters in the new mapping system.
     *
     * @param context Android context
     * @return Number of history entries migrated
     */
    suspend fun migrateHistoryChapterIds(context: Context): Int

    /**
     * Checks if a file needs migration (is a .cbz with EPUB content).
     *
     * @param file File to check
     * @return True if file needs migration
     */
    suspend fun needsMigration(file: File): Boolean
}

/**
 * Default implementation of LegacyEpubMigration.
 */
@Singleton
class LegacyEpubMigrationImpl @Inject constructor(
    private val epubFileManager: EpubFileManager,
    private val epubChapterMappingDao: EpubChapterMappingDao,
    private val historyDao: HistoryDao,
    private val chapterIdGenerator: ChapterIdGenerator
) : LegacyEpubMigration {

    companion object {
        private const val TAG = "LegacyEpubMigration"
        private const val EPUB_MIME_TYPE = "application/epub+zip"
        private const val EPUB_CONTAINER_PATH = "META-INF/container.xml"
    }

    override suspend fun detectLegacyCbzFiles(context: Context): List<File> = withContext(Dispatchers.IO) {
        val legacyFiles = mutableListOf<File>()

        // Check downloads directory for .cbz files
        val downloadDir = context.getExternalFilesDir("downloads")
        if (downloadDir?.exists() == true) {
            downloadDir.listFiles { file ->
                file.isFile && file.extension == "cbz"
            }?.forEach { cbzFile ->
                if (isEpubContent(cbzFile)) {
                    legacyFiles.add(cbzFile)
                }
            }
        }

        legacyFiles
    }

    override suspend fun convertCbzToEpub(cbzFile: File): File? = withContext(Dispatchers.IO) {
        try {
            // Verify it's actually EPUB content
            if (!isEpubContent(cbzFile)) {
                Log.w(TAG, "File is not EPUB content: ${cbzFile.name}")
                return@withContext null
            }

            // Create new .epub file in EPUB directory
            val epubFileName = cbzFile.nameWithoutExtension + ".epub"
            val epubFile = File(cbzFile.parent, epubFileName)

            // Simply rename the file (CBZ and EPUB are both ZIP formats)
            val success = cbzFile.renameTo(epubFile)

            if (success) {
                Log.i(TAG, "Successfully converted ${cbzFile.name} to ${epubFile.name}")
                epubFile
            } else {
                Log.e(TAG, "Failed to rename ${cbzFile.name} to ${epubFile.name}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting CBZ to EPUB: ${cbzFile.name}", e)
            null
        }
    }

    override suspend fun migrateHistoryChapterIds(context: Context): Int = withContext(Dispatchers.IO) {
        var migratedCount = 0

        try {
            // Get all history entries
            val historyEntries = historyDao.findAll(0, Int.MAX_VALUE)

            for (historyWithContent in historyEntries) {
                val history = historyWithContent.history
                val oldChapterId = history.chapterId

                // Try to find mapping for this chapter ID
                val mapping = epubChapterMappingDao.getById(oldChapterId)

                if (mapping == null) {
                    // This might be an old chapter ID that needs migration
                    // Try to find equivalent chapter by looking for similar parent IDs
                    val potentialParentId = extractPotentialParentId(oldChapterId)

                    if (potentialParentId != null) {
                        // Find mappings for this parent
                        val mappings = epubChapterMappingDao.getByParentId(potentialParentId)

                        if (mappings.isNotEmpty()) {
                            // Use the first chapter as fallback
                            val newChapterId = mappings.first().internalChapterId

                            // Update history with new chapter ID
                            historyDao.update(
                                mangaId = history.mangaId,
                                page = history.page,
                                chapterId = newChapterId,
                                scroll = history.scroll,
                                percent = history.percent,
                                chapters = history.chaptersCount,
                                updatedAt = history.updatedAt,
                                parentChapterId = potentialParentId
                            )

                            migratedCount++
                            Log.i(TAG, "Migrated chapter ID $oldChapterId -> $newChapterId for manga ${history.mangaId}")
                        }
                    }
                }
            }

            Log.i(TAG, "Migration complete: $migratedCount history entries migrated")
        } catch (e: Exception) {
            Log.e(TAG, "Error migrating history chapter IDs", e)
        }

        migratedCount
    }

    override suspend fun needsMigration(file: File): Boolean = withContext(Dispatchers.IO) {
        file.extension == "cbz" && isEpubContent(file)
    }

    /**
     * Checks if a file contains EPUB content by looking for the EPUB container.xml file.
     */
    private fun isEpubContent(file: File): Boolean {
        if (!file.exists() || !file.isFile) {
            return false
        }

        return try {
            ZipFile(file).use { zip ->
                // EPUB files must have META-INF/container.xml
                zip.getEntry(EPUB_CONTAINER_PATH) != null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking if file is EPUB: ${file.name}", e)
            false
        }
    }

    /**
     * Attempts to extract a potential parent chapter ID from an old chapter ID.
     * Old IDs might have been generated differently, so we try to reverse-engineer them.
     */
    private fun extractPotentialParentId(oldChapterId: Long): Long? {
        // Try to extract parent ID using the current formula
        // If the old ID was generated with a similar formula, this might work
        return try {
            val index = chapterIdGenerator.extractChapterIndex(oldChapterId)
            val parentId = chapterIdGenerator.extractParentId(oldChapterId)

            // Verify this makes sense (index should be reasonable)
            if (index in 0..999) {
                parentId
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Represents a legacy file that needs migration.
 */
data class LegacyEpubFile(
    val file: File,
    val needsMigration: Boolean,
    val estimatedChapterCount: Int = 0
)
