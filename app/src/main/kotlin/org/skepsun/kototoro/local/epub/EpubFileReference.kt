package org.skepsun.kototoro.local.epub

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.skepsun.kototoro.local.data.LocalSafEpubCache
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
    val normalizedFileReference = normalizeLocalEpubFileReference(fileReference)
    return "$normalizedFileReference#chapter/$chapterIndex"
}

internal fun resolveEpubFile(context: Context, reference: String): File? {
    val scheme = extractUriScheme(reference) ?: return File(reference)
    val uri = Uri.parse(reference)
    return when (scheme) {
        "file", "localepub" -> resolveLocalEpubFile(
            reference = reference,
            decodedPath = uri.path.takeIf { uri.fragment == null && '?' !in reference },
        )
        "content" -> materializeEpub(context, uri)
        else -> null
    }
}

internal fun resolveLocalEpubFile(reference: String, decodedPath: String? = null): File? {
    if (extractUriScheme(reference) == null) {
        return File(reference)
    }
    val decodedCandidate = (decodedPath ?: runCatching { File(java.net.URI(reference)) }.getOrNull()?.path)
        ?.let(::File)
    if (decodedCandidate?.exists() == true) {
        return decodedCandidate
    }
    val rawCandidate = extractRawLocalEpubPath(reference)?.let(::File)
    if (rawCandidate?.exists() == true) {
        return rawCandidate
    }
    return decodedCandidate ?: rawCandidate
}

private fun normalizeLocalEpubFileReference(fileReference: String): String {
    return when (extractUriScheme(fileReference)) {
        null -> File(fileReference).toURI().toString()
        "localepub" -> resolveLocalEpubFile(fileReference)?.toURI()?.toString() ?: fileReference
        "file" -> if (hasUnencodedFileUriCharacters(fileReference)) {
            extractRawLocalEpubPath(fileReference)?.let(::File)?.toURI()?.toString() ?: fileReference
        } else {
            fileReference
        }
        else -> fileReference
    }
}

private fun extractUriScheme(reference: String): String? {
    val colon = reference.indexOf(':')
    if (colon <= 0) return null
    if (colon == 1 && (reference.getOrNull(2) == '\\' || reference.getOrNull(2) == '/')) {
        return null
    }
    for (index in 0 until colon) {
        val ch = reference[index]
        val valid = if (index == 0) {
            ch.isLetter()
        } else {
            ch.isLetterOrDigit() || ch == '+' || ch == '-' || ch == '.'
        }
        if (!valid) return null
    }
    return reference.substring(0, colon).lowercase()
}

private fun extractRawLocalEpubPath(reference: String): String? {
    val afterScheme = when {
        reference.startsWith("localepub://", ignoreCase = true) -> reference.substring("localepub://".length)
        reference.startsWith("file://", ignoreCase = true) -> reference.substring("file://".length)
        reference.startsWith("file:", ignoreCase = true) -> reference.substring("file:".length)
        else -> return null
    }
    if (afterScheme.isEmpty()) return null
    return if (afterScheme.startsWith('/')) {
        "/" + afterScheme.trimStart('/')
    } else {
        afterScheme
    }
}

private fun hasUnencodedFileUriCharacters(reference: String): Boolean {
    return reference.any { ch ->
        ch == ' ' || ch == '#' || ch == '?' || ch == '[' || ch == ']' ||
            ch == '{' || ch == '}' || ch == '|' || ch == '\\' || ch == '^' || ch == '`'
    }
}

private fun materializeEpub(context: Context, uri: Uri): File? {
    val sourceUri = uri.buildUpon().fragment(null).build()
    val metadata = DocumentFile.fromSingleUri(context, sourceUri)
    val directory = LocalSafEpubCache.directory(context.cacheDir).apply { mkdirs() }
    val target = File(directory, "${sourceUri.toString().longHashCode()}.epub")
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
    // The parsers only ever hand us a File, so the copy cannot be released by its reader; keep the
    // folder bounded instead so a local novel library cannot grow by one full book per title. The
    // copy is marked in use first, so this trim (or a concurrent one) never deletes the book being read.
    LocalSafEpubCache.markInUse(target)
    LocalSafEpubCache.trim(context.cacheDir, keep = target)
    return target.takeIf(File::isFile)
}

private val SUPPORTED_EPUB_SCHEMES = setOf("content", "file", "localepub")
