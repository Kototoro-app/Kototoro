package org.skepsun.kototoro.source.host

import org.skepsun.kototoro.core.source.SourceImageArtifact
import org.skepsun.kototoro.core.source.SourceRef
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Streams post-interceptor bytes to immutable content-addressed files; no HTTP client or decoder lives here. */
class FileSourceImageStore(root: Path, private val maximumBytes: Long = 64L * 1024 * 1024) : SourceImageStore {
    val directory: Path = root.toAbsolutePath().normalize().also {
        Files.createDirectories(it)
        require(Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS)) { "Image directory must be a real directory" }
    }.toRealPath()

    init { require(maximumBytes > 0) }

    override fun materialize(
        source: SourceRef, pageId: Long, input: InputStream, contentLength: Long, checkpoint: () -> Unit,
    ): SourceImageArtifact {
        checkpoint()
        if (contentLength > maximumBytes) throw IOException("Image exceeds configured limit")
        val temporary = Files.createTempFile(directory, ".image-", ".part")
        var failure: Throwable? = null
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val prefix = ByteArray(64)
            var prefixLength = 0
            var length = 0L
            Files.newOutputStream(temporary).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkpoint()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (count.toLong() > maximumBytes - length) throw IOException("Image exceeds configured limit")
                    val copy = minOf(count, prefix.size - prefixLength)
                    buffer.copyInto(prefix, prefixLength, 0, copy)
                    prefixLength += copy
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    length += count
                }
            }
            checkpoint()
            if (length == 0L || (contentLength >= 0 && contentLength != length)) {
                throw IOException("Empty or truncated image")
            }
            val type = imageType(prefix.copyOf(prefixLength)) ?: throw IOException("Unsupported image signature")
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val target = directory.resolve("$hash.img")
            // Serialize verification/publication in this host, including stores sharing a directory.
            // Windows rejects replacing a blob while another publisher has it open for verification.
            synchronized(publicationLocks[target.hashCode() and (publicationLocks.size - 1)]) {
                checkpoint()
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                        throw IOException("Invalid image artifact")
                    }
                    if (Files.size(target) == length && hash(target, checkpoint) == hash) {
                        checkpoint()
                        return SourceImageArtifact(source, pageId, target.fileName.toString(), hash, length, type)
                    }
                }
                checkpoint()
                // Same-directory atomic publication: unsupported providers fail rather than publish a partial file.
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            return SourceImageArtifact(source, pageId, target.fileName.toString(), hash, length, type)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            // Only this call's generated staging path is removed; published artifacts remain owned by the platform.
            try { Files.deleteIfExists(temporary) } catch (cleanup: Throwable) {
                failure?.addSuppressed(cleanup) ?: throw cleanup
            }
        }
    }

    private fun hash(path: Path, checkpoint: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkpoint()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun imageType(bytes: ByteArray): String? {
        fun starts(vararg signature: Int) = bytes.size >= signature.size && signature.indices.all {
            bytes[it].toInt() and 0xff == signature[it]
        }
        fun text(offset: Int, value: String): Boolean = bytes.size >= offset + value.length && value.indices.all {
            bytes[offset + it].toInt() and 0xff == value[it].code
        }
        return when {
            starts(0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10) -> "image/png"
            starts(0xff, 0xd8, 0xff) -> "image/jpeg"
            text(0, "GIF87a") || text(0, "GIF89a") -> "image/gif"
            text(0, "RIFF") && text(8, "WEBP") -> "image/webp"
            text(0, "BM") -> "image/bmp"
            starts(0x49, 0x49, 0x2a, 0) || starts(0x4d, 0x4d, 0, 0x2a) -> "image/tiff"
            starts(0xff, 0x0a) || starts(0, 0, 0, 12, 0x4a, 0x58, 0x4c, 0x20, 13, 10, 0x87, 10) -> "image/jxl"
            text(4, "ftyp") -> {
                val brands = (8 until bytes.size - 3 step 4).map { offset ->
                    String(bytes, offset, 4, Charsets.US_ASCII)
                }
                when {
                    brands.any { it == "avif" || it == "avis" } -> "image/avif"
                    brands.any { it in setOf("heic", "heix", "hevc", "hevx") } -> "image/heic"
                    brands.any { it == "mif1" || it == "msf1" } -> "image/heif"
                    else -> null
                }
            }
            else -> null
        }
    }

    companion object {
        // Bounded locks avoid retaining every directory/blob name for the process lifetime.
        private val publicationLocks = Array(64) { Any() }
    }
}
