package org.skepsun.kototoro.local.epub

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.io.IOException
import javax.inject.Inject

/**
 * Use case for deleting EPUB files with cascade deletion of chapter mappings.
 *
 * This use case ensures that when an EPUB file is deleted:
 * 1. The file is removed from the file system
 * 2. All associated chapter mappings are removed from the database
 * 3. The operation is atomic (both succeed or both fail)
 *
 * Validates: Requirements 5.4 (Cascade Deletion)
 */
class DeleteEpubUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val epubDeletionManager: EpubDeletionManager
) {

    companion object {
        private const val TAG = "DeleteEpubUseCase"
    }

    /**
     * Deletes an EPUB file and all its associated chapter mappings.
     *
     * @param parentChapterId ID of the parent EPUB download chapter
     * @throws IOException if deletion fails
     */
    suspend operator fun invoke(parentChapterId: Long) {
        Log.d(TAG, "Deleting EPUB for parent chapter $parentChapterId")

        val success = epubDeletionManager.deleteEpubAndMappings(context, parentChapterId)

        if (!success) {
            throw IOException("Failed to delete EPUB file for parent chapter $parentChapterId")
        }

        Log.d(TAG, "Successfully deleted EPUB and mappings for parent chapter $parentChapterId")
    }

    /**
     * Deletes multiple EPUB files and their mappings.
     *
     * @param parentChapterIds Set of parent chapter IDs to delete
     * @return Number of successfully deleted EPUBs
     */
    suspend operator fun invoke(parentChapterIds: Set<Long>): Int {
        var deletedCount = 0

        for (parentChapterId in parentChapterIds) {
            runCatchingCancellable {
                invoke(parentChapterId)
                deletedCount++
            }.onFailure { error ->
                Log.e(TAG, "Failed to delete EPUB for parent $parentChapterId", error)
                error.printStackTraceDebug()
            }
        }

        Log.d(TAG, "Deleted $deletedCount out of ${parentChapterIds.size} EPUBs")
        return deletedCount
    }

    /**
     * Checks if an EPUB file exists for the given parent chapter.
     *
     * @param parentChapterId ID of the parent EPUB download chapter
     * @return True if the EPUB file and mappings exist
     */
    suspend fun exists(parentChapterId: Long): Boolean {
        return epubDeletionManager.epubExists(context, parentChapterId)
    }
}
