package org.skepsun.kototoro.core.model

import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentType
import java.util.Locale

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

fun String?.looksLikeVideoUrl(): Boolean {
    val normalized = this?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (normalized.isBlank()) {
        return false
    }
    if (normalized.contains("/video/")) {
        return true
    }
    return videoExtensions.any { normalized.endsWith(it) || normalized.contains("$it?") || normalized.contains("$it#") }
}

fun ContentChapter.looksLikeVideoChapter(): Boolean {
    if (source.getContentType().let { it == ContentType.VIDEO || it == ContentType.HENTAI_VIDEO }) {
        return true
    }
    return url.looksLikeVideoUrl()
}

fun Content.looksLikeLocalVideoContent(): Boolean {
    if (source.getContentType().let { it == ContentType.VIDEO || it == ContentType.HENTAI_VIDEO }) {
        return true
    }
    if (url.looksLikeVideoUrl() || publicUrl.looksLikeVideoUrl()) {
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
    if (source.getContentType().let { it == ContentType.NOVEL || it == ContentType.HENTAI_NOVEL }) {
        return true
    }
    return url.looksLikeNovelUrl()
}

fun Content.looksLikeLocalNovelContent(): Boolean {
    if (source.getContentType().let { it == ContentType.NOVEL || it == ContentType.HENTAI_NOVEL }) {
        return true
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
