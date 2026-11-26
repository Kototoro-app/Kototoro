package org.skepsun.kototoro.suggestions.domain

import org.skepsun.kototoro.core.model.TagTaxonomy


/*
 * Suggestions come in directions: more of what the reader likes, what is popular, what is new, and something they
 * have not tried. Each source is asked for a couple of them per generation, and every stored suggestion remembers
 * which one found it, so the reader can switch between them instead of scrolling one undifferentiated list.
 */

/** Why a work is suggested. Stored by [name]; never rename a value. */
enum class SuggestionDirection {
    /** Popular in a genre the reader keeps coming back to; the reason is that genre's tag key. */
    TASTE,

    /** Popular on a source, whatever it is about. */
    POPULAR,

    /** Only just listed by a source. */
    NEW,

    /** Popular in a genre the reader has not read; no longer generated, kept for suggestions stored before. */
    EXPLORE,

    COMMUNITY,
    ;

    companion object {
        /** Unknown and legacy values read as [TASTE], which is what every suggestion was before directions. */
        fun of(name: String?): SuggestionDirection = entries.find { it.name == name } ?: TASTE
    }
}

/** One list to ask a source for, and what its answers are suggested as. */
data class SuggestionQuery<T>(
    val direction: SuggestionDirection,
    /** Sort order name, as the source offers it. */
    val order: String,
    /** The source's own tag to filter by, null for its whole list. */
    val tag: T? = null,
    /** The tag key (see [suggestionTagKeys]) the answers are suggested for, null when no tag was asked for. */
    val reason: String? = null,
)

/** One answer of a source: where on which list it stood. */
data class SuggestionCandidate<T>(
    val item: T,
    val direction: SuggestionDirection,
    val reason: String?,
    /** Position on the source's list, 0 first. */
    val rank: Int,
)

private val PopularOrders = listOf("POPULARITY_MONTH", "POPULARITY_WEEK", "POPULARITY", "POPULARITY_YEAR", "RATING")
private val NewOrders = listOf("NEWEST", "ADDED", "UPDATED")

/**
 * How many of the reader's tags a source is looked through for. A source offers its own few tags, so what it can be
 * asked for is the best of the reader's tags it has, not the best of their first ten: a library full of very specific
 * tags would otherwise have nothing in common with the plain genres most sources list, and ask them nothing.
 */
private const val QUERY_TAG_POOL = 50

/**
 * What to ask one source: a list of a genre the reader likes when the source offers one (otherwise its plain popular
 * list), and its newest list. Which of the reader's genres a source is asked for depends on the source and the [seed]
 * only, so between them the sources cover what the reader likes and the same seed asks the same thing every time.
 */
fun <T> SuggestionProfile.planQueries(
    sourceName: String,
    seed: Long,
    availableOrders: Collection<String>,
    availableTags: Collection<T>,
    title: (T) -> String,
    isBlacklisted: (T) -> Boolean,
    matches: SuggestionTagMatcher,
    limits: SuggestionLimits = SuggestionLimits(),
): List<SuggestionQuery<T>> {
    val pick = suggestionQueryPick(sourceName, seed)
    val popular = PopularOrders.firstOrNull { it in availableOrders }
    val newest = NewOrders.firstOrNull { it in availableOrders }
    val order = popular ?: pickSuggestionSortOrder(availableOrders)
    val taste = pickQuery(availableTags, title, isBlacklisted, matches, pick, QUERY_TAG_POOL)
        ?.let { (tag, key) -> SuggestionQuery(SuggestionDirection.TASTE, order, tag, key) }
    // A source that only lists what is new has nothing popular to offer: its one list is the new one.
    val broad = SuggestionQuery<T>(if (popular == null && order in NewOrders) SuggestionDirection.NEW else SuggestionDirection.POPULAR, order)
    val fresh = newest?.takeIf { it != broad.order }?.let { SuggestionQuery<T>(SuggestionDirection.NEW, it) }
    return listOfNotNull(taste ?: broad, fresh ?: broad.takeIf { taste != null })
}

/** A title for a suggestion's reason: the catalogue's label in the reader's language, or the tag as it was spelled. */
fun suggestionReasonLabel(reason: String, language: String, traditional: Boolean = false): String =
    TagTaxonomy.find(reason)?.localizedLabel(language, traditional)
        ?: reason.removePrefix(org.skepsun.kototoro.core.model.TagTitleBlacklist.RAW_TAG_PREFIX)
