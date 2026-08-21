package org.skepsun.kototoro.local.epub

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Use case for migrating legacy .cbz EPUB files to the new .epub format.
 *
 * This handles:
 * - Detection of legacy .cbz files with EPUB content
 * - Conversion from .cbz to .epub format
 * - Migration of old chapter IDs in history
 * - UI indication for files needing migration
 */
@Singleton
class MigrateLegacyEpubUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val legacyEpubMigration: LegacyEpubMigration,
    private val epubFileManager: EpubFileManager
) {

    companion object {
        private const val TAG = "MigrateLegacyEpubUseCase"
    }

    /**
     * Scans for legacy .cbz files and returns information about them.
     *
     * @return List of legacy files that need migration
     */
    suspend fun scanForLegacyFiles(): List<LegacyEpubFile> = withContext(Dispatchers.IO) {
        try {
            val cbzFiles = legacyEpubMigration.detectLegacyCbzFiles(context)

            cbzFiles.map { file ->
                LegacyEpubFile(
                    file = file,
                    needsMigration = true,
                    estimatedChapterCount = 0 // Could be enhanced to read chapter count
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning for legacy files", e)
            emptyList()
        }
    }

    /**
     * Migrates a single legacy .cbz file to .epub format.
     *
     * @param legacyFile The legacy file to migrate
     * @return True if migration was successful
     */
    suspend fun migrateSingleFile(legacyFile: LegacyEpubFile): Boolean = withContext(Dispatchers.IO) {
        try {
            val epubFile = legacyEpubMigration.convertCbzToEpub(legacyFile.file)
            epubFile != null
        } catch (e: Exception) {
            Log.e(TAG, "Error migrating file: ${legacyFile.file.name}", e)
            false
        }
    }

    /**
     * Migrates all legacy files found in the system.
     *
     * @return Number of files successfully migrated
     */
    suspend fun migrateAllLegacyFiles(): Int = withContext(Dispatchers.IO) {
        var migratedCount = 0

        try {
            val legacyFiles = scanForLegacyFiles()

            for (legacyFile in legacyFiles) {
                if (migrateSingleFile(legacyFile)) {
                    migratedCount++
                }
            }

            // Also migrate history chapter IDs
            val historyMigrated = legacyEpubMigration.migrateHistoryChapterIds(context)
            Log.i(TAG, "Migrated $migratedCount files and $historyMigrated history entries")

        } catch (e: Exception) {
            Log.e(TAG, "Error during bulk migration", e)
        }

        migratedCount
    }

    /**
     * Checks if there are any legacy files that need migration.
     *
     * @return True if legacy files exist
     */
    suspend fun hasLegacyFiles(): Boolean = withContext(Dispatchers.IO) {
        try {
            val legacyFiles = legacyEpubMigration.detectLegacyCbzFiles(context)
            legacyFiles.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for legacy files", e)
            false
        }
    }
}
