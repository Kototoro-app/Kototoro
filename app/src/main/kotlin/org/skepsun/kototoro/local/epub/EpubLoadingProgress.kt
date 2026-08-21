package org.skepsun.kototoro.local.epub

/**
 * Callback interface for EPUB loading progress.
 *
 * Used to report progress when processing large EPUB files (>50MB).
 *
 * Requirements: 11.4 - Add progress indicators for large file processing
 */
interface EpubLoadingProgressListener {
    /**
     * Called when loading starts.
     *
     * @param fileName The name of the EPUB file being loaded
     * @param fileSize The size of the file in bytes
     */
    fun onLoadingStarted(fileName: String, fileSize: Long)

    /**
     * Called periodically during loading.
     *
     * @param progress Progress percentage (0-100)
     * @param message Optional status message
     */
    fun onLoadingProgress(progress: Int, message: String? = null)

    /**
     * Called when loading completes successfully.
     *
     * @param chapterCount Number of chapters extracted
     */
    fun onLoadingCompleted(chapterCount: Int)

    /**
     * Called when loading fails.
     *
     * @param error The error that occurred
     */
    fun onLoadingFailed(error: Throwable)
}

/**
 * Helper class to track EPUB loading progress.
 */
class EpubLoadingProgressTracker(
    private val listener: EpubLoadingProgressListener?
) {

    /**
     * Reports that loading has started.
     */
    fun reportStarted(fileName: String, fileSize: Long) {
        listener?.onLoadingStarted(fileName, fileSize)
    }

    /**
     * Reports loading progress.
     */
    fun reportProgress(progress: Int, message: String? = null) {
        listener?.onLoadingProgress(progress, message)
    }

    /**
     * Reports successful completion.
     */
    fun reportCompleted(chapterCount: Int) {
        listener?.onLoadingCompleted(chapterCount)
    }

    /**
     * Reports failure.
     */
    fun reportFailed(error: Throwable) {
        listener?.onLoadingFailed(error)
    }

    companion object {
        /**
         * Threshold for showing progress indicator (50MB).
         * Files larger than this will show progress updates.
         */
        const val LARGE_FILE_THRESHOLD = 50 * 1024 * 1024L // 50MB

        /**
         * Checks if a file is considered large.
         */
        fun isLargeFile(fileSize: Long): Boolean {
            return fileSize > LARGE_FILE_THRESHOLD
        }
    }
}
