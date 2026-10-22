package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale

/** Bytes on disk, split by whether a saved chapter still needs the blob. */
data class DesktopImageCacheReport(
    val usedBytes: Long = 0,
    val protectedBytes: Long = 0,
    /** Zero while the cache is unlimited. */
    val budgetBytes: Long = 0,
    val deletedFiles: Int = 0,
    val deletedBytes: Long = 0,
)

/**
 * Keeps the content-addressed image directory inside Android's total image budget. Saved chapters publish their own
 * manifests that reference the same blobs, so those files are protected: deleting one would break offline reading,
 * while an evicted reader-cache blob only costs a refetch because every hit is verified by size and SHA-256.
 */
internal object DesktopImageBudget {
    const val UNLIMITED_MB = 0
    const val DEFAULT_MB = 2048
    const val GRACE_MILLIS = 2 * 60_000L
    const val SWEEP_INTERVAL_MILLIS = 10L * 60_000L
    private const val CLEAR_GRACE_MILLIS = 5_000L
    private const val STALE_STAGING_MILLIS = 60 * 60_000L
    private const val MEBIBYTE = 1024L * 1024L

    /** The limits the downloads page offers; [UNLIMITED_MB] first would make the default look optional. */
    val choicesMb: List<Int> = listOf(512, 1024, DEFAULT_MB, 4096, 8192, UNLIMITED_MB)

    private data class Blob(val path: Path, val bytes: Long, val modified: Long, val referenced: Boolean)

    /** What the cache holds now; never deletes, so the page can show figures before the user changes anything. */
    fun inspect(directory: Path, referenced: Set<String>, budgetMb: Int): DesktopImageCacheReport {
        val blobs = blobs(directory, referenced)
        val used = blobs.sumOf { it.bytes }
        return DesktopImageCacheReport(
            usedBytes = used,
            protectedBytes = blobs.filter { it.referenced }.sumOf { it.bytes },
            budgetBytes = budgetBytes(budgetMb),
        )
    }

    /**
     * Evicts the least recently used unprotected blobs until the directory fits [budgetMb], or every unprotected blob
     * when [clear] is set. Files touched inside [graceMillis] are skipped so a sweep cannot race a page that is being
     * published or read right now; a blob another process holds open fails to delete and is retried next sweep.
     */
    suspend fun enforce(
        directory: Path,
        referenced: Set<String>,
        budgetMb: Int,
        clear: Boolean = false,
        now: Long = System.currentTimeMillis(),
        graceMillis: Long = if (clear) CLEAR_GRACE_MILLIS else GRACE_MILLIS,
    ): DesktopImageCacheReport {
        removeStaleStaging(directory, now)
        val context = currentCoroutineContext()
        val blobs = blobs(directory, referenced)
        val used = blobs.sumOf { it.bytes }
        val protected = blobs.filter { it.referenced }.sumOf { it.bytes }
        val limit = when {
            clear -> 0L
            budgetMb <= UNLIMITED_MB -> Long.MAX_VALUE
            else -> budgetMb.toLong() * MEBIBYTE
        }
        var remaining = used
        var deletedFiles = 0
        var deletedBytes = 0L
        for (blob in blobs.filter { !it.referenced && now - it.modified >= graceMillis }.sortedBy { it.modified }) {
            if (remaining <= limit) break
            context.ensureActive()
            val removed = try {
                Files.deleteIfExists(blob.path)
            } catch (_: IOException) {
                false
            }
            if (removed) {
                deletedFiles++
                deletedBytes += blob.bytes
                remaining -= blob.bytes
            }
        }
        return DesktopImageCacheReport(remaining, protected, budgetBytes(budgetMb), deletedFiles, deletedBytes)
    }

    private fun budgetBytes(budgetMb: Int): Long =
        if (budgetMb <= UNLIMITED_MB) 0L else budgetMb.toLong() * MEBIBYTE

    private fun blobs(directory: Path, referenced: Set<String>): List<Blob> {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val result = ArrayList<Blob>()
        Files.newDirectoryStream(directory, "*.img").use { stream ->
            for (path in stream) {
                val bytes = try {
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue
                    Files.size(path)
                } catch (_: IOException) {
                    continue
                }
                val modified = try {
                    Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
                } catch (_: IOException) {
                    continue
                }
                result += Blob(path, bytes, modified, path.fileName.toString() in referenced)
            }
        }
        return result
    }

    /** A crash can leave a staging file behind; its process is gone once it is this old, so it is pure garbage. */
    private suspend fun removeStaleStaging(directory: Path, now: Long) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return
        val context = currentCoroutineContext()
        Files.newDirectoryStream(directory, ".image-*").use { stream ->
            for (path in stream) {
                context.ensureActive()
                val modified = try {
                    Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
                } catch (_: IOException) {
                    continue
                }
                if (now - modified < STALE_STAGING_MILLIS) continue
                try {
                    Files.deleteIfExists(path)
                } catch (_: IOException) {
                    // Held open by a live publication or protected by the OS: keep it for the next sweep.
                }
            }
        }
    }
}

/** Sizes as the downloads page prints them; decimal points stay stable because the page is also read by tests. */
internal fun desktopCacheSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0)
    bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
    // A single page is a few kilobytes; printing those as "0.0 MB" made a working cache look empty.
    else -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
}
