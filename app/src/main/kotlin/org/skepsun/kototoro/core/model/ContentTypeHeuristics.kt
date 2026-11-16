package org.skepsun.kototoro.core.model

import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import java.util.Locale

/**
 * Url-shape guessing is only trustworthy when the source does not state its own kind: local
 * entries (legacy imports are still [LocalMangaSource]) and sources of unknown type. A remote
 * parser that declares a concrete type stays authoritative, otherwise its plain site-relative
 * routes (`/photos-index-aid-1.html`, `/video/123`) would be read as file names.
 */
private fun ContentSource.mayInferTypeFromUrl(): Boolean = isLocal || getContentType() == ContentType.OTHER

private val videoExtensions = setOf(
    ".m3u8",
    ".mp4",
    ".mkv",
    ".webm",
    ".ts",
    ".avi",
    ".mov",
    ".flv",
    ".wmv",
)

/**
 * True when the url points at a media file rather than at a page. Such a url is stronger
 * evidence than the declared type, because a gallery source can host images and videos alike.
 */
fun String?.looksLikeVideoFileUrl(): Boolean {
    val normalized = this?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (normalized.isBlank()) {
        return false
    }
    return videoExtensions.any { normalized.endsWith(it) || normalized.contains("$it?") || normalized.contains("$it#") }
}

fun String?.looksLikeVideoUrl(): Boolean {
    val normalized = this?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (normalized.isBlank()) {
        return false
    }
    if (normalized.contains("/video/")) {
        return true
    }
    return normalized.looksLikeVideoFileUrl()
}

fun ContentChapter.looksLikeVideoChapter(): Boolean {
    val declaredType = source.getContentType()
    if (declaredType == ContentType.VIDEO || declaredType == ContentType.HENTAI_VIDEO) {
        return true
    }
    if (url.looksLikeVideoFileUrl()) {
        return true
    }
    return source.mayInferTypeFromUrl() && url.looksLikeVideoUrl()
}

fun Content.looksLikeLocalVideoContent(): Boolean {
    val declaredType = source.getContentType()
    if (declaredType == ContentType.VIDEO || declaredType == ContentType.HENTAI_VIDEO) {
        return true
    }
    if (url.looksLikeVideoFileUrl() || publicUrl.looksLikeVideoFileUrl()) {
        return true
    }
    if (source.mayInferTypeFromUrl() && (url.looksLikeVideoUrl() || publicUrl.looksLikeVideoUrl())) {
        return true
    }
    return chapters?.any { it.looksLikeVideoChapter() } == true
}

private val localNovelSchemes = setOf("file:", "content:", "zip:", "file+zip:", "content+zip:", "epub:", "localepub:")
private val localNovelExtensions = setOf(".epub", ".txt", ".html", ".xhtml")

fun String?.looksLikeNovelUrl(): Boolean {
    val normalized = this?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (normalized.isBlank()) {
        return false
    }
    if (normalized.startsWith("localepub:") || normalized.startsWith("epub:")) {
        return true
    }
    if (!normalized.startsWith("/") && !localNovelSchemes.any(normalized::startsWith)) {
        return false
    }
    if (
        normalized.contains("#chapter/") ||
        normalized.contains("#chapter%2f") ||
        normalized.endsWith("#text")
    ) {
        return true
    }
    val withoutQuery = normalized.substringBefore('?')
    val fragment = withoutQuery.substringAfter('#', "")
    val base = withoutQuery.substringBefore('#')
    return localNovelExtensions.any { base.endsWith(it) || fragment.endsWith(it) }
}

fun ContentChapter.looksLikeNovelChapter(): Boolean {
    val declaredType = source.getContentType()
    if (declaredType == ContentType.NOVEL || declaredType == ContentType.HENTAI_NOVEL) {
        return true
    }
    return source.mayInferTypeFromUrl() && url.looksLikeNovelUrl()
}

fun Content.looksLikeLocalNovelContent(): Boolean {
    val declaredType = source.getContentType()
    if (declaredType == ContentType.NOVEL || declaredType == ContentType.HENTAI_NOVEL) {
        return true
    }
    // Media file urls are handled by [looksLikeLocalVideoContent]; here the ".html" suffix of a
    // parser route is not evidence at all, so a declared type always wins.
    if (!source.mayInferTypeFromUrl()) {
        return false
    }
    if (url.looksLikeNovelUrl() || publicUrl.looksLikeNovelUrl()) {
        return true
    }
    return chapters?.any { it.looksLikeNovelChapter() } == true
}

fun isLikelyLocalNovelContent(content: Content): Boolean = content.looksLikeLocalNovelContent()

/**
 * Replacement for the former Work-level content-type rule.
 *
 * A details page may only expose works that belong to the same content-type
 * family (manga / novel / video / other) as the selected one. Unknown types are
 * rejected so legacy data cannot widen the result set.
 */
fun ContentType?.isSameContentFamilyAs(other: ContentType?): Boolean {
    if (this == null || other == null) {
        return false
    }
    return contentFamily() == other.contentFamily()
}

fun ContentType.contentFamily(): ContentTypeFamily = when (this) {
    ContentType.MANGA,
    ContentType.MANHWA,
    ContentType.MANHUA,
    ContentType.HENTAI_MANGA,
    ContentType.COMICS,
    ContentType.ONE_SHOT,
    ContentType.DOUJINSHI,
    ContentType.IMAGE_SET,
    ContentType.ARTIST_CG,
    ContentType.GAME_CG,
        -> ContentTypeFamily.MANGA

    ContentType.NOVEL,
    ContentType.HENTAI_NOVEL,
        -> ContentTypeFamily.NOVEL

    ContentType.VIDEO,
    ContentType.HENTAI_VIDEO,
        -> ContentTypeFamily.VIDEO

    ContentType.OTHER -> ContentTypeFamily.OTHER
}

enum class ContentTypeFamily {
    MANGA,
    NOVEL,
    VIDEO,
    OTHER,
}
