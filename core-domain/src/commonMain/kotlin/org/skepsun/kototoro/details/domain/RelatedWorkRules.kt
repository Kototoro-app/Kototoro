package org.skepsun.kototoro.details.domain

import org.skepsun.kototoro.core.model.ContentIdentityKeys
import org.skepsun.kototoro.tracking.domain.TitleKeys

/**
 * Android's `filterCurrentWorkFromRelated`: a source's related list must never show the work that asked for it, nor a
 * second copy of that work the source relists under another URL. Both hosts hand in their own content objects and a
 * projection of the fields the rule looks at, so the rule itself exists once.
 *
 * Three kinds of copy are dropped, all of them only within the seed's own source: the same id, the same content
 * identity (URL or public URL), and the same strict title over a shared cover resource. A candidate from another
 * source is always kept — the seed's id and URLs say nothing about a different site.
 */
object RelatedWorkRules {

    /** [filterCurrentWork] without a "same entry twice" pass; callers keep their own order and shape. */
    fun <T> filterCurrentWork(
        seed: RelatedWorkKey,
        candidates: List<T>,
        key: (T) -> RelatedWorkKey,
    ): List<T> = candidates.filterNot { candidate -> key(candidate).isCopyOf(seed) }
}

/** The fields the related-work rule reads, so it needs neither host's content model. */
data class RelatedWorkKey(
    val sourceName: String,
    val id: Long,
    val url: String,
    val publicUrl: String,
    val title: String,
    val coverUrl: String? = null,
    val largeCoverUrl: String? = null,
)

private fun RelatedWorkKey.isCopyOf(seed: RelatedWorkKey): Boolean {
    if (sourceName != seed.sourceName) return false
    return id == seed.id ||
        ContentIdentityKeys.hasSameIdentity(
            source = seed.sourceName,
            url = seed.url,
            publicUrl = seed.publicUrl,
            otherSource = sourceName,
            otherUrl = url,
            otherPublicUrl = publicUrl,
        ) ||
        hasSameTitleAndCover(seed)
}

private fun RelatedWorkKey.hasSameTitleAndCover(other: RelatedWorkKey): Boolean {
    val titleKey = TitleKeys.normalize(title, listOf(sourceName))
    if (titleKey.isBlank() || titleKey != TitleKeys.normalize(other.title, listOf(other.sourceName))) {
        return false
    }
    val otherKeys = listOfNotNull(other.coverUrl, other.largeCoverUrl)
        .mapNotNull(::coverResourceKey)
        .toSet()
    return listOfNotNull(coverUrl, largeCoverUrl)
        .mapNotNull(::coverResourceKey)
        .any(otherKeys::contains)
}

/** The path of an http(s) cover, so the same artwork served from two hosts still counts as one image. */
private fun coverResourceKey(value: String?): String? {
    val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (!raw.startsWith("http://", ignoreCase = true) && !raw.startsWith("https://", ignoreCase = true)) {
        return raw
    }
    // `commonMain` has no `java.net.URI`, and the path is all this needs: drop scheme and host, then query/fragment.
    val path = raw.substringAfter("://").substringAfter('/', missingDelimiterValue = "")
    if (path.isEmpty()) {
        return raw
    }
    return "/" + path.substringBefore('?').substringBefore('#')
}
