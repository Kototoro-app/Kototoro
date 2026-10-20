package org.skepsun.kototoro.local.data

import java.io.File

/**
 * Holds the local copies of SAF books the EPUB parsers need, with a bounded footprint.
 *
 * EPUB parsers take a [File], so a copy cannot be released by the reader the way
 * [LocalSafArchiveCache] releases chapter archives. Retention is bounded instead: the book just
 * materialized always stays, a few recent copies may stay, and anything older is reclaimed on the
 * next materialization (or at startup). Without the cap a local novel library accumulated one full
 * copy per book and never released them - the same defect that once affected SAF chapters (#575).
 */
internal object LocalSafEpubCache {

    const val DIRECTORY_NAME = "local_saf_epub"

    private const val MAX_RETAINED_BYTES = 64L * 1024 * 1024
    private const val MAX_RETAINED_FILES = 4

    /**
     * A copy younger than this may still be open in another coroutine, so trimming never touches it.
     * Materializing a book takes far less than this, which keeps concurrent readers safe.
     */
    private const val MIN_RETAIN_AGE_MS = 60_000L

    fun directory(cacheDir: File): File = File(cacheDir, DIRECTORY_NAME)

    /**
     * Drops every idle copy except [keep]. Copies younger than [MIN_RETAIN_AGE_MS] are left alone so
     * a startup reclaim cannot delete a book that the library scan is already reading.
     */
    fun clearAll(cacheDir: File, keep: File? = null, now: Long = System.currentTimeMillis()) {
        val retained = keep?.absoluteFile
        directory(cacheDir).listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.absoluteFile != retained && now - file.lastModified() > MIN_RETAIN_AGE_MS) {
                file.delete()
            }
        }
    }

    /** Reclaims idle copies beyond the retention budget, always keeping [keep]. */
    fun trim(
        cacheDir: File,
        keep: File?,
        now: Long = System.currentTimeMillis(),
    ) {
        val retained = (keep?.absoluteFile)
        val candidates = directory(cacheDir).listFiles().orEmpty()
            .filter { it.isFile && it.absoluteFile != retained && now - it.lastModified() > MIN_RETAIN_AGE_MS }
            .sortedByDescending { it.lastModified() }
        var count = if (keep != null) 1 else 0
        var bytes = keep?.length() ?: 0L
        for (file in candidates) {
            count++
            bytes += file.length()
            if (count > MAX_RETAINED_FILES || bytes > MAX_RETAINED_BYTES) {
                file.delete()
            }
        }
    }
}
