package org.skepsun.kototoro.community.domain

import org.skepsun.kototoro.tracking.domain.TitleKeys

/**
 * What a community work is compared as. Coarser than a content type on purpose: one source files a series as MANHWA
 * and another as MANGA, one tags it adult and another does not, and the ratings of both readers belong together.
 */
enum class CommunityMedium(val wire: String) {
    COMIC("comic"),
    NOVEL("novel"),
    VIDEO("video"),
    OTHER("other"),
    ;

    companion object {

        /** From a content type name; unknown and missing types compare as [OTHER]. */
        fun of(contentType: String?): CommunityMedium = when (contentType) {
            "MANGA", "MANHWA", "MANHUA", "COMICS", "ONE_SHOT", "DOUJINSHI", "HENTAI_MANGA", "IMAGE_SET" -> COMIC
            "NOVEL", "HENTAI_NOVEL" -> NOVEL
            "VIDEO", "HENTAI_VIDEO" -> VIDEO
            else -> OTHER
        }
    }
}

/** The tracking sites whose ids name a work on every device. Names match the app's scrobbler and MALSync services. */
enum class CommunityExternalService {
    ANILIST,
    MAL,
    KITSU,
    SHIKIMORI,
    BANGUMI,
    MANGAUPDATES,
    ;

    companion object {
        fun of(name: String?): CommunityExternalService? = entries.firstOrNull { it.name == name }
    }
}

data class CommunityExternalRef(
    val service: CommunityExternalService,
    val remoteId: Long,
)

/**
 * The identifiers a community event carries for one work (see docs/architecture/community-nostr-plan-2026-11.md §3).
 *
 * Strong keys are canonical tracking-site URLs, NIP-73's `web` form, and name one work wherever they appear. Title keys
 * (`kototoro:t:<medium>:<folded title>`) are the fallback every work has; they stay readable rather than hashed so the
 * indexer can improve matching later and regroup everything already published.
 */
object CommunityWorkKeys {

    const val TITLE_PREFIX = "kototoro:t:"
    const val MAX_TITLE_KEYS = 8
    const val MAX_TITLE_LENGTH = 160

    private val STRONG_KEY = Regex("""^(https://[^\s]+/)(\d+)$""")

    /**
     * Where one title field holds a list of names: a comma with no space after it ("Name,Other Name"), or a spaced
     * slash or bar. A comma followed by a space, `、` and `，` are left alone; they belong inside real titles.
     */
    private val LIST_SEPARATOR = Regex(""",(?!\s)|\s+[/|｜]\s+""")
    private val LABEL_PREFIX = Regex("""^\s*(?:other\s+names?|alt(?:ernative)?\s+titles?|aka)\s*(?:\(s\))?\s*[:：]\s*""", RegexOption.IGNORE_CASE)
    private val LEADING_TAG = Regex("""^\s*[\[【][^\]】]*[\]】]\s*""")
    private val TRAILING_TAG = Regex("""\s*[\[【][^\]】]*[\]】]\s*$""")
    private val TRAILING_NOISE = Regex(
        """(?:\s*[@＠]\s*(?:comic|ＣＯＭＩＣ|ｃｏｍｉｃ)|\s*[(（]\s*(?:raw|official)\s*[)）]|\s+(?:raw|official))\s*$""",
        RegexOption.IGNORE_CASE,
    )

    fun strongKey(ref: CommunityExternalRef, medium: CommunityMedium): String? {
        if (ref.remoteId <= 0L) {
            return null
        }
        val path = when (medium) {
            CommunityMedium.COMIC, CommunityMedium.NOVEL -> when (ref.service) {
                CommunityExternalService.ANILIST -> "https://anilist.co/manga/"
                CommunityExternalService.MAL -> "https://myanimelist.net/manga/"
                CommunityExternalService.KITSU -> "https://kitsu.app/manga/"
                CommunityExternalService.SHIKIMORI ->
                    if (medium == CommunityMedium.NOVEL) "https://shikimori.one/ranobe/" else "https://shikimori.one/mangas/"
                CommunityExternalService.BANGUMI -> "https://bgm.tv/subject/"
                CommunityExternalService.MANGAUPDATES -> "https://www.mangaupdates.com/series/"
            }
            CommunityMedium.VIDEO -> when (ref.service) {
                CommunityExternalService.ANILIST -> "https://anilist.co/anime/"
                CommunityExternalService.MAL -> "https://myanimelist.net/anime/"
                CommunityExternalService.KITSU -> "https://kitsu.app/anime/"
                CommunityExternalService.SHIKIMORI -> "https://shikimori.one/animes/"
                CommunityExternalService.BANGUMI -> "https://bgm.tv/subject/"
                CommunityExternalService.MANGAUPDATES -> null
            }
            CommunityMedium.OTHER -> null
        }
        return path?.let { it + ref.remoteId }
    }

    /**
     * [fold] is the platform's case and form folding: NFKC, invariant lowercase and traditional to simplified Chinese
     * where the platform has a converter. Everything but letters and digits is dropped after it, so spacing,
     * punctuation and brackets never split a work; a bare number or a single letter names nothing. Surrogate pairs are
     * kept whole, since common code cannot ask whether a supplementary character (CJK Extension B) is a letter.
     */
    fun titleKey(
        title: String,
        medium: CommunityMedium,
        fold: (String) -> String = String::lowercase,
        sourceNames: Iterable<String> = emptyList(),
    ): String? {
        val folded = fold(stripNoise(TitleKeys.stripTrailingSourceSuffix(title, sourceNames)))
        var compact = buildString(folded.length) {
            var wordStart = 0
            for (char in folded) {
                if (char.isLetterOrDigit() || char.isSurrogate()) {
                    append(char)
                } else {
                    dropParticleW(wordStart)
                    wordStart = length
                }
            }
            dropParticleW(wordStart)
        }
        if (compact.length > MAX_TITLE_LENGTH) {
            compact = compact.take(MAX_TITLE_LENGTH).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
        }
        if (compact.length < 2 || compact.all { it.isDigit() }) {
            return null
        }
        return "$TITLE_PREFIX${medium.wire}:$compact"
    }

    /** Strong keys sorted, then title keys in the order the titles came, without repeats and at most [MAX_TITLE_KEYS]. */
    fun keys(
        medium: CommunityMedium,
        titles: Iterable<String>,
        refs: Iterable<CommunityExternalRef> = emptyList(),
        fold: (String) -> String = String::lowercase,
        sourceNames: Iterable<String> = emptyList(),
    ): List<String> {
        val strong = refs.mapNotNull { strongKey(it, medium) }.distinct().sorted()
        val weak = searchTitles(titles).mapNotNull { titleKey(it, medium, fold, sourceNames) }.distinct().take(MAX_TITLE_KEYS)
        return strong + weak
    }

    /** The romaji object particle is written `wo` by some and `o` by others ("Shiro wo Moratta", "Shiro o Moratta"). */
    private fun StringBuilder.dropParticleW(wordStart: Int) {
        if (length - wordStart == 2 && this[wordStart] == 'w' && this[wordStart + 1] == 'o') {
            deleteAt(wordStart)
        }
    }

    /** The names to search a site with and to key: list fields split into their names, labels and group tags removed. */
    fun searchTitles(titles: Iterable<String>): List<String> = titles
        .flatMap { title -> title.split(LIST_SEPARATOR) }
        .map(::stripNoise)
        .filter { it.isNotBlank() }
        .distinct()

    /**
     * Drops what sources and scanlators add around a name: a leading "Other name(s):" label, bracketed tags at either
     * end (`[Greed Scans]`, `【连载】`), a trailing `@COMIC` and a raw/official marker. A title that is nothing but a
     * bracketed tag keeps it.
     */
    fun stripNoise(title: String): String {
        var result = title.replace(LABEL_PREFIX, "")
        while (true) {
            val next = result.replace(TRAILING_NOISE, "").replace(TRAILING_TAG, "").replace(LEADING_TAG, "")
            if (next == result || next.isBlank()) break
            result = next
        }
        return result.trim()
    }

    fun isStrong(key: String): Boolean = STRONG_KEY.matches(key)

    /** The part of a strong key a work can only have one id in, e.g. `https://anilist.co/manga/`. */
    fun namespace(key: String): String? = STRONG_KEY.matchEntire(key)?.groupValues?.get(1)
}
