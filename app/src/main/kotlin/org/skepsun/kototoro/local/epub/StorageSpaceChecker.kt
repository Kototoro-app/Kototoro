package org.skepsun.kototoro.local.epub

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * Utility for checking storage space availability.
 *
 * Requirement 10.5: Check storage space before downloads
 */
object StorageSpaceChecker {

    /**
     * Checks if there is sufficient storage space available.
     *
     * @param context Android context
     * @param requiredBytes Number of bytes required
     * @param directory Target directory (defaults to external files dir)
     * @return Result indicating success or error with details
     */
    fun checkStorageSpace(
        context: Context,
        requiredBytes: Long,
        directory: File? = null
    ): Result<Unit> {
        val targetDir = directory ?: context.getExternalFilesDir(null) ?: context.filesDir

        return try {
            val stat = StatFs(targetDir.absolutePath)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong

            // Add 10% buffer for safety
            val requiredWithBuffer = (requiredBytes * 1.1).toLong()

            if (availableBytes < requiredWithBuffer) {
                val error = EpubError.FileSystemError.InsufficientStorage(
                    requiredBytes = requiredBytes,
                    availableBytes = availableBytes
                )
                EpubErrorHandler.createFailure(error, "checkStorageSpace")
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            val error = EpubError.UnexpectedError(
                message = "Failed to check storage space",
                cause = e
            )
            EpubErrorHandler.createFailure(error, "checkStorageSpace")
        }
    }

    /**
     * Gets available storage space in bytes.
     *
     * @param context Android context
     * @param directory Target directory (defaults to external files dir)
     * @return Available bytes, or -1 if check fails
     */
    fun getAvailableSpace(context: Context, directory: File? = null): Long {
        val targetDir = directory ?: context.getExternalFilesDir(null) ?: context.filesDir

        return try {
            val stat = StatFs(targetDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            android.util.Log.e("StorageSpaceChecker", "Failed to get available space", e)
            -1L
        }
    }

    /**
     * Formats bytes to human-readable string.
     *
     * @param bytes Number of bytes
     * @return Formatted string (e.g., "1.5 MB")
     */
    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 0 -> "Unknown"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
