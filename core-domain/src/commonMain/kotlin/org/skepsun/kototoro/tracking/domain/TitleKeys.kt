package org.skepsun.kototoro.tracking.domain

/**
 * Android's strict title key (`titleSimilarity`'s `normalizeStrictTitleKey`), shared so Windows can compare two works
 * by title the way Android compares a related candidate with the work that asked for it.
 *
 * The key is punctuation- and case-insensitive and whitespace-collapsed: "【中文】某 作 品!!" and "某作品" do not
 * match, but "某 作品" and "某作品!" keep their word boundary and their letters. Fuzzy scoring stays Android-only
 * (it needs `java.text.Normalizer`); only the strict form belongs here.
 */
object TitleKeys {

    /** Trailing `(Source Name)` on a title, e.g. `某作品（Kototoro）`. */
    private val TRAILING_SOURCE_SUFFIX = Regex("""^(.+?)\s*[\(（]([^\(\)（）]+)[\)）]\s*$""")

    private val PUNCTUATION = Regex("[\\p{P}\\p{S}]+")
    private val WHITESPACE = Regex("\\s+")

    fun normalize(value: String): String = value.trim()
        .lowercase()
        .replace(PUNCTUATION, " ")
        .replace(WHITESPACE, " ")
        .trim()

    /** [normalize] after dropping a trailing bracketed source name that repeats one of [sourceNames]. */
    fun normalize(value: String, sourceNames: Iterable<String>): String =
        normalize(stripTrailingSourceSuffix(value, sourceNames))

    /**
     * Drops a trailing bracketed suffix when it matches one of [sourceNames] (compared through [normalize]); any other
     * bracketed suffix is part of the title and stays.
     */
    fun stripTrailingSourceSuffix(value: String, sourceNames: Iterable<String>): String {
        val title = value.trim()
        val match = TRAILING_SOURCE_SUFFIX.matchEntire(title) ?: return title
        val suffixKey = normalize(match.groupValues[2])
        if (suffixKey.isBlank()) {
            return title
        }
        val sourceKeys = sourceNames
            .mapTo(LinkedHashSet()) { normalize(it) }
            .filterTo(LinkedHashSet()) { it.isNotBlank() }
        return if (suffixKey in sourceKeys) match.groupValues[1].trim() else title
    }
}
