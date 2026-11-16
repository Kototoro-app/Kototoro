package org.skepsun.kototoro.suggestions.domain

import org.skepsun.kototoro.core.model.TagTaxonomy
import org.skepsun.kototoro.core.model.TaxonomyCategory
import kotlin.random.Random

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

    /** Popular in a genre the reader has not read; the reason is that genre's tag key. */
    EXPLORE,
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

/** What a genre the reader has not tried may be: what a work is about, not who it is for or what it warns of. */
private val ExploreCategories = setOf(
    TaxonomyCategory.GENRE, TaxonomyCategory.THEME, TaxonomyCategory.SETTING, TaxonomyCategory.NARRATIVE,
    TaxonomyCategory.ELEMENT,
)

/**
 * What to ask one source this time: a genre the reader likes when the source offers one, and one wider list, which
 * of popular, new and unexplored changing with the source and the [seed]. Two requests at most, so that a source is
 * not asked for every direction every time while all of them are covered between the sources.
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
    val taste = pickQuery(availableTags, title, isBlacklisted, matches, pick, limits.seedTags)
        ?.let { (tag, key) -> SuggestionQuery(SuggestionDirection.TASTE, order, tag, key) }
    val wider = buildList {
        if (popular != null) add(SuggestionQuery<T>(SuggestionDirection.POPULAR, popular))
        if (newest != null) add(SuggestionQuery<T>(SuggestionDirection.NEW, newest))
        val unexplored = availableTags.filterNot(isBlacklisted).mapNotNull { tag ->
            val known = TagTaxonomy.resolve(title(tag))
            val key = known.firstOrNull { it.category in ExploreCategories && it.id !in this@planQueries.taste }?.id
            if (key == null || known.any {
                it.adult || it.sensitive || it.id == "genre.ecchi" || it.category !in ExploreCategories
            }) null
                else tag to key
        }.distinctBy { it.second }
        if (popular != null && unexplored.isNotEmpty()) {
            val (tag, key) = unexplored[Random(seed xor sourceName.hashCode().toLong()).nextInt(unexplored.size)]
            add(SuggestionQuery(SuggestionDirection.EXPLORE, popular, tag, key))
        }
    }
    val broad = if (wider.isEmpty()) SuggestionQuery<T>(SuggestionDirection.POPULAR, order)
        else wider[(pick * wider.size).toInt().coerceAtMost(wider.lastIndex)]
    return listOfNotNull(taste, broad)
}

/** A title for a suggestion's reason: the catalogue's label in the reader's language, or the tag as it was spelled. */
fun suggestionReasonLabel(reason: String, language: String, traditional: Boolean = false): String =
    TagTaxonomy.find(reason)?.localizedLabel(language, traditional)
        ?: reason.removePrefix(org.skepsun.kototoro.core.model.TagTitleBlacklist.RAW_TAG_PREFIX)
