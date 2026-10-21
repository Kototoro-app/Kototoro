package org.skepsun.kototoro.core.util

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import okio.FileHandle
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import okio.buffer
import okio.openZip
import org.jetbrains.annotations.Blocking
import org.skepsun.kototoro.core.util.ext.toUnderlyingZipUri
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.ZipInputStream

/**
 * Random access to a ZIP archive behind a `content://` URI.
 *
 * SAF hands out a real file descriptor for documents on device storage, and a ZIP reader needs
 * nothing more than positional reads on it: neither a copy of the archive in the cache nor a
 * sequential scan up to the wanted entry. Both were O(volume size) per call, which for comic
 * volumes of a gigabyte or more made a SAF library fill the cache and stall the reader (#575).
 *
 * Providers that stream through a pipe cannot seek; [open] returns null for them and callers keep
 * their sequential fallback.
 */
class ContentZipArchive private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val channel: FileChannel,
) : Closeable {

    /** The archive's entries rooted at `/`, readable until this archive is closed. */
    val fileSystem: FileSystem by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ChannelFileSystem().openZip(ARCHIVE_PATH)
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { descriptor.close() }
    }

    private inner class ChannelFileSystem : FileSystem() {

        override fun openReadOnly(file: Path): FileHandle {
            if (file != ARCHIVE_PATH) throw FileNotFoundException("no such file: $file")
            return ChannelHandle()
        }

        override fun metadataOrNull(path: Path): FileMetadata? = if (path == ARCHIVE_PATH) {
            FileMetadata(isRegularFile = true, size = channel.size())
        } else {
            null
        }

        override fun canonicalize(path: Path): Path = path

        override fun list(dir: Path): List<Path> = throw IOException("not a directory: $dir")

        override fun listOrNull(dir: Path): List<Path>? = null

        override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle = readOnly()

        override fun source(file: Path): Source = openReadOnly(file).source()

        override fun sink(file: Path, mustCreate: Boolean): Sink = readOnly()

        override fun appendingSink(file: Path, mustExist: Boolean): Sink = readOnly()

        override fun createDirectory(dir: Path, mustCreate: Boolean) = readOnly()

        override fun atomicMove(source: Path, target: Path) = readOnly()

        override fun delete(path: Path, mustExist: Boolean) = readOnly()

        override fun createSymlink(source: Path, target: Path) = readOnly()

        private fun readOnly(): Nothing = throw IOException("read-only file system")
    }

    /** Positional reads only, so the handles of concurrent entry reads never move a shared cursor. */
    private inner class ChannelHandle : FileHandle(readWrite = false) {

        override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int =
            channel.read(ByteBuffer.wrap(array, arrayOffset, byteCount), fileOffset)

        override fun protectedSize(): Long = channel.size()

        // The archive owns the descriptor; a handle is one of many views on it.
        override fun protectedClose() = Unit

        override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) =
            throw IOException("read-only")

        override fun protectedFlush() = throw IOException("read-only")

        override fun protectedResize(size: Long) = throw IOException("read-only")
    }

    companion object {

        private val ARCHIVE_PATH = "/archive".toPath()

        /** Opens [uri] (a document, not a `zip+content` entry URI) for random access, or null when it cannot seek. */
        @Blocking
        fun open(resolver: ContentResolver, uri: Uri): ContentZipArchive? {
            val descriptor = try {
                resolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                // Providers without descriptor support throw a variety of exceptions; the fallback reports real errors.
                null
            } ?: return null
            val channel = FileInputStream(descriptor.fileDescriptor).channel
            val seekable = descriptor.statSize > 0 && runCatching { channel.position(0) }.isSuccess
            if (!seekable) {
                runCatching { channel.close() }
                runCatching { descriptor.close() }
                return null
            }
            return ContentZipArchive(descriptor, channel)
        }

        /** Runs [block] with the stream of the entry a `zip+content` [uri] points at. */
        @Blocking
        inline fun <T> withEntry(resolver: ContentResolver, uri: Uri, block: (InputStream) -> T): T =
            openEntry(resolver, uri).use(block)

        /** The stream of the entry a `zip+content` [uri] points at; closing it releases the archive. */
        @Blocking
        fun openEntry(resolver: ContentResolver, uri: Uri): InputStream {
            val entryName = uri.fragment ?: throw IOException("ZIP URI has no entry name: $uri")
            open(resolver, uri.toUnderlyingZipUri())?.let { archive ->
                val stream = try {
                    archive.fileSystem.source(entryName.toEntryPath()).buffer().inputStream()
                } catch (e: Throwable) {
                    archive.close()
                    throw e
                }
                return object : FilterInputStream(stream) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            archive.close()
                        }
                    }
                }
            }
            val input = resolver.openInputStream(uri.toUnderlyingZipUri()) ?: throw IOException("Cannot open $uri")
            val zip = ZipInputStream(input.buffered())
            try {
                var entry = zip.nextEntry
                while (entry != null && entry.name != entryName) {
                    entry = zip.nextEntry
                }
                if (entry == null) throw IOException("ZIP entry not found: $entryName")
                if (entry.isDirectory) throw IOException("ZIP entry is a directory: $entryName")
            } catch (e: Throwable) {
                zip.close()
                throw e
            }
            return zip
        }

        /** The uncompressed size of the entry a `zip+content` [uri] points at, or null when unknown or missing. */
        @Blocking
        fun entrySize(resolver: ContentResolver, uri: Uri): Long? {
            val entryName = uri.fragment ?: return null
            open(resolver, uri.toUnderlyingZipUri())?.use { archive ->
                return archive.fileSystem.metadataOrNull(entryName.toEntryPath())?.size
            }
            val input = resolver.openInputStream(uri.toUnderlyingZipUri()) ?: return null
            return ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && entry.name != entryName) {
                    entry = zip.nextEntry
                }
                entry?.size
            }
        }

        private fun String.toEntryPath(): Path = if (startsWith(Path.DIRECTORY_SEPARATOR)) {
            this
        } else {
            Path.DIRECTORY_SEPARATOR + this
        }.toPath()
    }
}
