package org.skepsun.kototoro.local.epub

import android.content.Context
import android.util.Log
import org.skepsun.kototoro.core.db.MangaDatabase
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages cascade deletion of EPUB files and their associated chapter mappings.
 *
 * When an EPUB file is deleted, this manager ensures that:
 * 1. The file is removed from the file system
 * 2. All chapter mappings are removed from the database
 * 3. The UI is notified to refresh the chapter list
 */
interface EpubDeletionManager {
    /**
     * Deletes an EPUB file and all its associated chapter mappings.
     *
     * @param context Android context
     * @param parentChapterId ID of the parent EPUB download chapter
     * @return True if deletion was successful, false otherwise
     */
    suspend fun deleteEpubAndMappings(context: Context, parentChapterId: Long): Boolean

    /**
     * Deletes only the chapter mappings for a given parent chapter.
     * Useful when the file has already been deleted externally.
     *
     * @param parentChapterId ID of the parent EPUB download chapter
     * @return Number of mappings deleted
     */
    suspend fun deleteMappingsOnly(parentChapterId: Long): Int

    /**
     * Checks if an EPUB file exists and has mappings.
     *
     * @param context Android context
     * @param parentChapterId ID of the parent EPUB download chapter
     * @return True if both file and mappings exist
     */
    suspend fun epubExists(context: Context, parentChapterId: Long): Boolean
}

/**
 * Default implementation of EpubDeletionManager.
 */
@Singleton
class EpubDeletionManagerImpl @Inject constructor(
    private val database: MangaDatabase,
    private val epubFileManager: EpubFileManager
) : EpubDeletionManager {

    companion object {
        private const val TAG = "EpubDeletionManager"
    }

    override suspend fun deleteEpubAndMappings(context: Context, parentChapterId: Long): Boolean {
        return try {
            // Step 1: Find the EPUB file
            val epubFile = epubFileManager.findEpubFile(context, parentChapterId)

            if (epubFile == null) {
                Log.w(TAG, "EPUB file not found for parent chapter $parentChapterId")
                // File doesn't exist, but we should still clean up mappings
                deleteMappingsOnly(parentChapterId)
                return false
            }

            // Step 2: Delete the file from file system
            val fileDeleted = epubFile.delete()

            if (!fileDeleted) {
                Log.e(TAG, "Failed to delete EPUB file: ${epubFile.absolutePath}")
                return false
            }

            Log.d(TAG, "Deleted EPUB file: ${epubFile.absolutePath}")

            // Step 3: Delete all chapter mappings from database
            val mappingsDeleted = deleteMappingsOnly(parentChapterId)

            Log.d(TAG, "Deleted $mappingsDeleted chapter mappings for parent $parentChapterId")

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error during cascade deletion for parent $parentChapterId", e)
            false
        }
    }

    override suspend fun deleteMappingsOnly(parentChapterId: Long): Int {
        return try {
            // Get count before deletion for logging
            val count = database.getEpubChapterMappingDao().countByParentId(parentChapterId)

            // Delete all mappings
            database.getEpubChapterMappingDao().deleteByParentId(parentChapterId)

            count
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting mappings for parent $parentChapterId", e)
            0
        }
    }

    override suspend fun epubExists(context: Context, parentChapterId: Long): Boolean {
        return try {
            // Check if file exists
            val fileExists = epubFileManager.isEpubDownloaded(context, parentChapterId)

            // Check if mappings exist
            val mappingsExist = database.getEpubChapterMappingDao().existsByParentId(parentChapterId)

            fileExists && mappingsExist
        } catch (e: Exception) {
            Log.e(TAG, "Error checking EPUB existence for parent $parentChapterId", e)
            false
        }
    }
}
