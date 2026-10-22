package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.source.SourceChapterDownload
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a completed chapter to a CBZ next to the user's other files, the desktop equivalent of
 * Android's download folder. Images are copied from the same content-addressed blobs the reader
 * verifies, so an export can never publish bytes whose SHA no longer matches the manifest.
 */
internal class DesktopDownloadExporter(private val directory: Path) {
    /** One file per chapter; the same chapter exported twice replaces the previous file. */
    fun fileName(record: SourceChapterDownload): String =
        "${desktopExportName(record.contentTitle)} - ${desktopExportName(chapterLabel(record))}.cbz"

    suspend fun export(record: SourceChapterDownload, targetDirectory: Path): Path = withContext(Dispatchers.IO) {
        require(record.isComplete) { desktopText("downloads.exportIncomplete") }
        require(Files.isDirectory(targetDirectory)) { desktopText("downloads.exportDirectory") }
        val target = targetDirectory.resolve(fileName(record))
        val temporary = Files.createTempFile(targetDirectory, ".kototoro-export", ".part")
        try {
            ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
                record.pages.forEachIndexed { index, page ->
                    val artifact = page.artifact ?: return@forEachIndexed
                    val source = verifiedImagePath(directory, artifact.relativePath, artifact.sha256, artifact.byteSize)
                        ?: throw IOException(desktopText("downloads.exportMissing"))
                    // One page at a time: STORED entries need the size and CRC up front, and re-compressing
                    // already-compressed images would only cost CPU.
                    val bytes = Files.readAllBytes(source)
                    val entry = ZipEntry("%04d.%s".format(index + 1, desktopImageExtension(artifact.contentType)))
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            target
        } catch (e: Throwable) {
            Files.deleteIfExists(temporary)
            throw e
        }
    }

    private fun chapterLabel(record: SourceChapterDownload): String =
        record.chapter.title?.takeIf(String::isNotBlank)
            ?: desktopText("common.chapterTitle", record.chapter.number)
}

/** Strips the characters Windows rejects in file names and keeps the readable part bounded. */
internal fun desktopExportName(value: String): String {
    val cleaned = value.map { character ->
        if (character.isISOControl() || character in "\\/:*?\"<>|") '_' else character
    }.joinToString("").trim().trimEnd('.')
    val collapsed = cleaned.replace(Regex("\\s+"), " ").ifBlank { "chapter" }
    return if (collapsed.length <= 80) collapsed else collapsed.take(80).trimEnd()
}

internal fun desktopImageExtension(contentType: String): String = when (contentType.substringBefore(';').trim().lowercase()) {
    "image/jpeg", "image/jpg" -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    "image/bmp" -> "bmp"
    "image/avif" -> "avif"
    else -> "img"
}
