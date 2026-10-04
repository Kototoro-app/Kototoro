package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/** Validate immutable image blobs before publishing a persisted cache hit. */
internal suspend fun verifiedImagePath(directory: Path, relativePath: String, sha256: String, byteSize: Long): Path? {
    if (!sha256.matches(Regex("[0-9a-f]{64}")) || relativePath != "$sha256.img" || byteSize <= 0) return null
    val path = directory.resolve(relativePath)
    val context = currentCoroutineContext()
    try {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != byteSize) return null
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                context.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        context.ensureActive()
        return path.takeIf { imageHex(digest.digest()) == sha256 }
    } catch (_: IOException) { return null }
}

internal fun imageKey(bytes: ByteArray): String = imageHex(MessageDigest.getInstance("SHA-256").digest(bytes))
internal fun imageHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
