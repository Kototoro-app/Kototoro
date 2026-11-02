package org.skepsun.kototoro.local.data

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the local copies of SAF books the EPUB parsers need, with a bounded footprint.
 *
 * EPUB parsers take a [File], so a copy cannot be released by the reader the way
 * [LocalSafArchiveCache] releases chapter archives. Retention is bounded instead: the book just
 * materialized always stays, a few recent copies may stay, and anything older is reclaimed on the
 * next materialization (or at startup). Without the cap a local novel library accumulated one full
 * copy per book and never released them - the same defect that once affected SAF chapters (#575).
 *
 * Copies are tracked in process rather than by modification time: [org.skepsun.kototoro.local.epub]
 * rewrites a copy's timestamp to the source's own so an edited book is detected, which would make
 * every fresh copy look ancient and let a trim delete a book another reader is still holding.
 */
internal object LocalSafEpubCache {

    const val DIRECTORY_NAME = "local_saf_epub"

    private const val MAX_RETAINED_BYTES = 64L * 1024 * 1024
    private const val MAX_RETAINED_FILES = 4

    /**
     * A copy used less than this long ago may still be open in another coroutine, so reclaiming
     * never touches it. Reading a chapter takes far less than this, which keeps readers safe.
     */
    private const val MIN_IDLE_MS = 60_000L

    private val lastUses = ConcurrentHashMap<String, Long>()

    fun directory(cacheDir: File): File = File(cacheDir, DIRECTORY_NAME)

    /** Records that [file] was just created or handed to a reader, so reclaiming leaves it alone. */
    fun markInUse(file: File, now: Long = System.currentTimeMillis()) {
        lastUses[file.absolutePath] = now
    }

    /**
     * Drops every copy except [keep], including the ones a previous process left behind: a copy is
     * reclaimable when this process never touched it or has not touched it for [MIN_IDLE_MS].
     */
    fun clearAll(cacheDir: File, keep: File? = null, now: Long = System.currentTimeMillis()) {
        val retained = keep?.absolutePath
        directory(cacheDir).listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.absolutePath != retained && isIdle(file, now)) file.delete()
        }
    }

    /** Reclaims idle copies beyond the retention budget, always keeping [keep]. */
    fun trim(cacheDir: File, keep: File?, now: Long = System.currentTimeMillis()) {
        val retained = keep?.absolutePath
        val candidates = directory(cacheDir).listFiles().orEmpty()
            .filter { it.isFile && it.absolutePath != retained && isIdle(it, now) }
            .sortedByDescending { lastUses[it.absolutePath] ?: Long.MIN_VALUE }
        var count = if (keep != null) 1 else 0
        var bytes = keep?.length() ?: 0L
        for (file in candidates) {
            count++
            bytes += file.length()
            if (count > MAX_RETAINED_FILES || bytes > MAX_RETAINED_BYTES) {
                file.delete()
                lastUses.remove(file.absolutePath)
            }
        }
    }

    private fun isIdle(file: File, now: Long): Boolean {
        val used = lastUses[file.absolutePath] ?: return true
        return now - used >= MIN_IDLE_MS
    }
}
