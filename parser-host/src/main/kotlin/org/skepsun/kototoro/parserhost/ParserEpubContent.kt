package org.skepsun.kototoro.parserhost

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Path.Companion.toPath
import org.skepsun.kototoro.core.source.SourceChapterContent
import org.skepsun.kototoro.core.source.SourceChapterSection
import org.skepsun.kototoro.core.source.SourceContentImage
import org.skepsun.kototoro.local.shared.LocalBookReader
import org.skepsun.kototoro.local.shared.LocalFormat
import org.skepsun.kototoro.parsers.ContentParser
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * EPUB-only sources publish a whole volume instead of HTML pages. Keep the source's client and identity; the volume's
 * own chapters are its sections.
 */
internal class ParserEpubContent(private val requests: ParserImageRequests, private val assets: Path) {
    suspend fun load(parser: ContentParser, url: String, title: String): SourceChapterContent {
        val context = currentCoroutineContext()
        Files.createDirectories(assets)
        val file = Files.createTempFile(assets, ".epub-", ".part")
        try {
            val request = requests.request(parser, url, mapOf("Accept" to "application/epub+zip,application/octet-stream;q=0.9,*/*;q=0.8"))
            requests.execute(parser, request).use { response ->
                if (!response.isSuccessful) throw IOException("EPUB download failed (HTTP ${response.code})")
                val body = response.body
                if (body.contentLength() > MAXIMUM_BYTES) throw IOException("EPUB exceeds configured limit")
                body.byteStream().use { input ->
                    Files.newOutputStream(file).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            context.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAXIMUM_BYTES) throw IOException("EPUB exceeds configured limit")
                            output.write(buffer, 0, count)
                        }
                        if (total == 0L || body.contentLength().let { it >= 0 && it != total }) {
                            throw IOException("EPUB download is empty or truncated")
                        }
                    }
                }
            }
            context.ensureActive()
            ZipFile(file.toFile()).use { zip ->
                var expanded = 0L
                var count = 0
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    context.ensureActive()
                    val entry = entries.nextElement()
                    if (++count > 10_000 || entry.size !in 0..MAXIMUM_ENTRY_BYTES) {
                        throw IOException("EPUB entry exceeds configured limit")
                    }
                    expanded += entry.size
                    if (expanded > MAXIMUM_BYTES) throw IOException("Expanded EPUB exceeds configured limit")
                }
            }
            val reader = LocalBookReader()
            val path = file.toString().toPath()
            val book = reader.read(path, LocalFormat.EPUB, title)
            val html = StringBuilder()
            val images = linkedMapOf<String, SourceContentImage>()
            // The book's chapters stay addressable, so a download can list them like Android's EPUB volume groups.
            val sections = ArrayList<SourceChapterSection>(book.chapters.size)
            for (chapter in book.chapters) {
                context.ensureActive()
                val content = reader.chapterContent(path, chapter) { _, bytes ->
                    context.ensureActive()
                    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    val target = assets.resolve("$hash.img")
                    val temporary = Files.createTempFile(assets, ".epub-image-", ".part")
                    try {
                        Files.write(temporary, bytes)
                        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } finally { Files.deleteIfExists(temporary) }
                    target.toUri().toASCIIString()
                }
                sections += SourceChapterSection(chapter.title, html.length)
                html.append(content.html).append('\n')
                if (html.length > MAXIMUM_TEXT) throw IOException("EPUB text exceeds configured limit")
                content.images.forEach { images[it.url] = it }
            }
            if (html.isBlank()) throw IOException("EPUB has no readable text")
            return SourceChapterContent(html.toString(), images.values.toList(), sections)
        } finally { Files.deleteIfExists(file) }
    }

    private companion object {
        const val MAXIMUM_BYTES = 64L * 1024 * 1024
        const val MAXIMUM_ENTRY_BYTES = 32L * 1024 * 1024
        const val MAXIMUM_TEXT = 8 * 1024 * 1024
    }
}
