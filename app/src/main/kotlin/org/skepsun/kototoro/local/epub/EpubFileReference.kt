package org.skepsun.kototoro.local.epub

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.skepsun.kototoro.parsers.util.longHashCode
import java.io.File

internal data class EpubChapterReference(
    val fileReference: String,
    val chapterIndex: Int,
)

internal fun parseEpubChapterIndex(url: String): Int? {
    val marker = "#chapter/"
    val markerIndex = url.lastIndexOf(marker)
    if (markerIndex <= 0) return null
    return url.substring(markerIndex + marker.length)
        .substringBefore('?')
        .substringBefore('#')
        .toIntOrNull()
        ?.takeIf { it >= 0 }
}

internal fun parseEpubChapterReference(url: String): EpubChapterReference? {
    val marker = "#chapter/"
    val markerIndex = url.lastIndexOf(marker)
    if (markerIndex <= 0) return null
    val chapterIndex = parseEpubChapterIndex(url) ?: return null
    val fileReference = url.substring(0, markerIndex)
    val scheme = fileReference.substringBefore(':', missingDelimiterValue = "").lowercase()
    if (scheme !in SUPPORTED_EPUB_SCHEMES) return null
    return EpubChapterReference(fileReference, chapterIndex)
}

/**
 * Rewrites an encoded `#chapter%2F<n>` fragment to the literal `#chapter/<n>` that every reader of
 * EPUB chapter URLs matches. `Uri.Builder.fragment("chapter/0")` encodes the slash, and local indexes
 * written that way lost their chapter index when read back.
 */
internal fun normalizeEpubChapterUrl(url: String): String {
    val hash = url.lastIndexOf('#')
    if (hash < 0 || !url.regionMatches(hash + 1, ENCODED_CHAPTER_PREFIX, 0, ENCODED_CHAPTER_PREFIX.length, ignoreCase = true)) {
        return url
    }
    return url.substring(0, hash) + "#chapter/" + url.substring(hash + 1 + ENCODED_CHAPTER_PREFIX.length)
}

private const val ENCODED_CHAPTER_PREFIX = "chapter%2F"

internal fun buildEpubChapterUrl(fileReference: String, chapterIndex: Int): String {
    require(chapterIndex >= 0) { "EPUB chapter index must not be negative" }
    val normalizedFileReference = if (fileReference.substringBefore(':', missingDelimiterValue = "").isEmpty()) {
        File(fileReference).toURI().toString()
    } else {
        fileReference
    }
    return "$normalizedFileReference#chapter/$chapterIndex"
}

internal fun resolveEpubFile(context: Context, reference: String): File? {
    val uri = Uri.parse(reference)
    return when (uri.scheme?.lowercase()) {
        null -> File(reference)
        "file", "localepub" -> uri.path?.let(::File)
        "content" -> materializeEpub(context, uri)
        else -> null
    }
}

private fun materializeEpub(context: Context, uri: Uri): File? {
    val sourceUri = uri.buildUpon().fragment(null).build()
    val metadata = DocumentFile.fromSingleUri(context, sourceUri)
    val cacheDir = File(context.cacheDir, "local_saf_epub").apply { mkdirs() }
    val target = File(cacheDir, "${sourceUri.toString().longHashCode()}.epub")
    val sourceLength = metadata?.length() ?: -1L
    val sourceModified = metadata?.lastModified() ?: 0L
    val needsRefresh = !target.isFile ||
        (sourceLength >= 0L && target.length() != sourceLength) ||
        (sourceModified > 0L && target.lastModified() != sourceModified)
    if (needsRefresh) {
        val input = context.contentResolver.openInputStream(sourceUri) ?: return null
        input.use { source -> target.outputStream().use(source::copyTo) }
        if (sourceModified > 0L) target.setLastModified(sourceModified)
    }
    return target.takeIf(File::isFile)
}

private val SUPPORTED_EPUB_SCHEMES = setOf("content", "file", "localepub")
