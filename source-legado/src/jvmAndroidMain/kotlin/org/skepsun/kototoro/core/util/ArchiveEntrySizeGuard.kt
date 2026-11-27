package org.skepsun.kototoro.core.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Upper bound for a single archive entry decompressed into memory, guarding against zip bombs and
 * oversized entries (#738). Mirrors `LibArchiveUtils.DEFAULT_MAX_ENTRY_BYTES` from #747 so every
 * archive entry read in the app shares the same limit.
 */
const val DEFAULT_MAX_ARCHIVE_ENTRY_BYTES: Long = 64L * 1024 * 1024

private const val COPY_BUFFER_SIZE = 8192

/**
 * Creates the error thrown for an oversized entry. The message names the entry path and the limit,
 * matching the wording used by `LibArchiveUtils` since #747.
 */
fun archiveEntryTooLarge(path: String, maxBytes: Long): IOException =
    IOException("Archive entry too large: $path (limit $maxBytes bytes)")

/**
 * Rejects an entry whose declared size already exceeds [maxBytes], before any data is read.
 * Archives with data descriptors report `-1` (or `0`) as the declared size, so callers must still
 * guard the stream with [copyEntryTo] / [readEntryBytes].
 */
@Throws(IOException::class)
fun checkArchiveEntryDeclaredSize(
    path: String,
    declaredSize: Long,
    maxBytes: Long = DEFAULT_MAX_ARCHIVE_ENTRY_BYTES,
) {
    if (declaredSize > maxBytes) {
        throw archiveEntryTooLarge(path, maxBytes)
    }
}

/**
 * Copies this archive entry's bytes into [output], failing as soon as [maxBytes] would be crossed
 * instead of buffering an unbounded amount of decompressed data. Returns the number of bytes written;
 * content below the limit is copied unchanged.
 */
@Throws(IOException::class)
fun InputStream.copyEntryTo(
    output: OutputStream,
    path: String,
    maxBytes: Long = DEFAULT_MAX_ARCHIVE_ENTRY_BYTES,
): Long {
    val buffer = ByteArray(COPY_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read == -1) return total
        total += read
        if (total > maxBytes) throw archiveEntryTooLarge(path, maxBytes)
        output.write(buffer, 0, read)
    }
}

/** Reads this archive entry into memory with the [maxBytes] bound of [copyEntryTo]. */
@Throws(IOException::class)
fun InputStream.readEntryBytes(
    path: String,
    maxBytes: Long = DEFAULT_MAX_ARCHIVE_ENTRY_BYTES,
): ByteArray {
    val output = ByteArrayOutputStream()
    copyEntryTo(output, path, maxBytes)
    return output.toByteArray()
}
