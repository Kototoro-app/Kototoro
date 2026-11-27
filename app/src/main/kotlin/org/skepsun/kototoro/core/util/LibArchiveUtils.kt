package org.skepsun.kototoro.core.util

import android.system.OsConstants.S_ISDIR
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import okio.Buffer
import org.skepsun.kototoro.lib.icu4j.CharsetDetector
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

object LibArchiveUtils {

    private const val DEFAULT_BUFFER_SIZE = 8192

    /**
     * Upper bound for a single entry decompressed into memory. Guards against zip bombs and
     * oversized entries (see #738).
     */
    const val DEFAULT_MAX_ENTRY_BYTES: Long = 64L * 1024 * 1024

    @Throws(ArchiveException::class)
    private fun openArchive(inputStream: InputStream): Long {
        val archive = Archive.readNew()
        var successful = false
        try {
            Archive.setCharset(archive, StandardCharsets.UTF_8.name().toByteArray())
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            Archive.readSetCallbackData(archive, null)
            val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
            Archive.readSetReadCallback<Any?>(archive) { _, _ ->
                buffer.clear()
                val bytesRead = inputStream.read(buffer.array())
                if (bytesRead != -1) {
                    buffer.limit(bytesRead)
                    buffer
                } else {
                    null
                }
            }
            Archive.readSetSkipCallback<Any?>(archive) { _, _, request ->
                inputStream.skip(request)
            }
            Archive.readOpen1(archive)
            successful = true
            return archive
        } finally {
            if (!successful) {
                Archive.free(archive)
            }
        }
    }

    fun unArchive(
        inputStream: InputStream,
        destDir: File,
        filter: ((String) -> Boolean)? = null,
    ): List<File> {
        return unArchive(openArchive(inputStream), destDir, filter)
    }

    @Throws(IOException::class)
    fun getByteArrayContent(
        inputStream: InputStream,
        path: String,
        maxBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
    ): ByteArray? {
        val archive = openArchive(inputStream)
        try {
            var entry: Long
            while (Archive.readNextHeader(archive).also { entry = it } != 0L) {
                val entryName = getEntryString(
                    ArchiveEntry.pathnameUtf8(entry),
                    ArchiveEntry.pathname(entry),
                ) ?: continue
                if (ArchiveEntry.stat(entry).isDir()) continue
                if (entryName != path) continue
                if (ArchiveEntry.sizeIsSet(entry)) {
                    checkEntrySizeLimit(path, ArchiveEntry.size(entry), maxBytes)
                }
                val content = EntryBuffer(path, maxBytes)
                val byteBuffer = ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE)
                while (true) {
                    Archive.readData(archive, byteBuffer)
                    byteBuffer.flip()
                    if (!byteBuffer.hasRemaining()) {
                        return content.toByteArray()
                    }
                    content.write(byteBuffer)
                    byteBuffer.clear()
                }
            }
        } finally {
            Archive.free(archive)
        }
        return null
    }

    /** Rejects an entry whose declared size already exceeds [maxBytes], before any data is read. */
    internal fun checkEntrySizeLimit(path: String, declaredSize: Long, maxBytes: Long) {
        if (declaredSize > maxBytes) {
            throw entryTooLarge(path, maxBytes)
        }
    }

    private fun entryTooLarge(path: String, maxBytes: Long) = IOException(
        "Archive entry too large: $path (limit $maxBytes bytes)",
    )

    /**
     * Collects one entry's bytes, failing as soon as [maxBytes] would be exceeded instead of
     * buffering an unbounded amount of decompressed data in memory.
     */
    internal class EntryBuffer(
        private val path: String,
        private val maxBytes: Long,
    ) {

        private val buffer = Buffer()

        fun write(source: ByteBuffer) {
            val count = source.remaining()
            if (count == 0) return
            if (buffer.size + count > maxBytes) {
                throw entryTooLarge(path, maxBytes)
            }
            buffer.write(source)
        }

        fun toByteArray(): ByteArray = buffer.readByteArray()
    }

    private fun unArchive(
        archive: Long,
        destDir: File,
        filter: ((String) -> Boolean)? = null,
    ): List<File> {
        val files = arrayListOf<File>()
        try {
            var entry: Long
            while (Archive.readNextHeader(archive).also { entry = it } != 0L) {
                val entryName = getEntryString(
                    ArchiveEntry.pathnameUtf8(entry),
                    ArchiveEntry.pathname(entry),
                ) ?: continue
                val entryFile = archiveEntryFile(destDir, entryName)
                val stat = ArchiveEntry.stat(entry)
                if (stat.isDir()) {
                    if (!entryFile.exists()) entryFile.mkdirs()
                    continue
                }
                if (filter != null && !filter(entryName)) continue
                entryFile.parentFile?.mkdirs()
                if (!entryFile.exists()) {
                    entryFile.createNewFile()
                }
                entryFile.outputStream().buffered().use { output ->
                    val byteBuffer = ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        Archive.readData(archive, byteBuffer)
                        byteBuffer.flip()
                        if (!byteBuffer.hasRemaining()) break
                        val bytes = ByteArray(byteBuffer.remaining())
                        byteBuffer.get(bytes)
                        output.write(bytes)
                        byteBuffer.clear()
                    }
                }
                files += entryFile
            }
        } finally {
            Archive.free(archive)
        }
        return files
    }

    private fun ArchiveEntry.StructStat.isDir(): Boolean = S_ISDIR(stMode)

    private fun getEntryString(utf8: String?, bytes: ByteArray?): String? {
        return utf8 ?: bytes?.let(::decodeBytes)
    }

    private fun decodeBytes(bytes: ByteArray): String {
        val detected = runCatching {
            CharsetDetector().setText(bytes).detectAll().firstOrNull()?.name
        }.getOrNull()
        val charset = runCatching {
            Charset.forName(detected ?: "UTF-8")
        }.getOrDefault(Charsets.UTF_8)
        return String(bytes, charset)
    }
}
