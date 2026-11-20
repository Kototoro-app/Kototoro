package org.skepsun.kototoro.community.domain

/**
 * When a tracking site's search result may stand for a local work (community plan §3.5). A wrong id merges two works'
 * ratings on every device for good, while a missing one only costs reach, so this accepts nothing it is unsure of:
 *
 * - the hit is the same medium as the work;
 * - one of its names folds to exactly one of the work's title keys, and that key is long enough to mean something;
 * - it is the only hit on that site that does both.
 *
 * Site-specific shapes come in through lambdas so the search protocol in core-net stays independent of this module.
 */
object CommunityExternalMatch {

    /** Latin-only keys shorter than this are words, not titles ("love", "gate"); CJK keys carry more per character. */
    const val MIN_LATIN_KEY = 5
    const val MIN_OTHER_KEY = 2

    /** What [format] means on [service], or null for things that are no work a reader opens (music, games, artbooks). */
    fun mediumOf(service: CommunityExternalService, format: String?): CommunityMedium? = when (service) {
        CommunityExternalService.ANILIST -> when (format) {
            "MANGA", "ONE_SHOT" -> CommunityMedium.COMIC
            "NOVEL" -> CommunityMedium.NOVEL
            "TV", "TV_SHORT", "MOVIE", "SPECIAL", "OVA", "ONA" -> CommunityMedium.VIDEO
            else -> null
        }
        CommunityExternalService.BANGUMI -> {
            val type = format?.substringBefore('/')
            val platform = format?.substringAfter('/', "")
            when (type) {
                "1" -> when (platform) {
                    "小说" -> CommunityMedium.NOVEL
                    "画集", "公式书" -> null
                    else -> CommunityMedium.COMIC
                }
                "2", "6" -> CommunityMedium.VIDEO
                else -> null
            }
        }
        else -> null
    }

    /** Whether a title key is specific enough to identify a work by itself. */
    fun isDistinctive(key: String): Boolean {
        val folded = key.substringAfter(CommunityWorkKeys.TITLE_PREFIX).substringAfter(':')
        val latinOnly = folded.all { it < '\u0080' }
        return folded.length >= if (latinOnly) MIN_LATIN_KEY else MIN_OTHER_KEY
    }

    /** Every distinct hit that passes the medium and title tests; more than one on a site means the search is ambiguous. */
    fun <T> matches(
        medium: CommunityMedium,
        titleKeys: Collection<String>,
        hits: Iterable<T>,
        service: (T) -> CommunityExternalService?,
        id: (T) -> Long,
        format: (T) -> String?,
        titles: (T) -> List<String>,
        fold: (String) -> String = String::lowercase,
    ): List<CommunityExternalRef> {
        val keys = titleKeys.filterTo(HashSet(), ::isDistinctive)
        if (keys.isEmpty()) return emptyList()
        return hits.mapNotNull { hit ->
            val site = service(hit) ?: return@mapNotNull null
            if (mediumOf(site, format(hit)) != medium) return@mapNotNull null
            val named = titles(hit).any { CommunityWorkKeys.titleKey(it, medium, fold) in keys }
            if (named) CommunityExternalRef(site, id(hit)).takeIf { it.remoteId > 0L } else null
        }.distinct()
    }

    /** The one hit to bind, or null when none or several qualify. Pass one site's hits at a time. */
    fun <T> accept(
        medium: CommunityMedium,
        titleKeys: Collection<String>,
        hits: Iterable<T>,
        service: (T) -> CommunityExternalService?,
        id: (T) -> Long,
        format: (T) -> String?,
        titles: (T) -> List<String>,
        fold: (String) -> String = String::lowercase,
    ): CommunityExternalRef? = matches(medium, titleKeys, hits, service, id, format, titles, fold).singleOrNull()
}
