package org.skepsun.kototoro.suggestions.domain

import kotlin.math.pow

/*
 * Android's suggestion rules, shared with the Windows host: seed tags from what the user reads, one tagged list
 * per source, tag relevance scoring and source-balanced ranking. Fuzzy tag matching is injected because the
 * parser's `almostEquals` is JVM-only; both hosts pass it with [SUGGESTION_TAG_THRESHOLD].
 */

/** Tag titles closer than this (normalised edit distance) count as the same tag. */
const val SUGGESTION_TAG_THRESHOLD = 0.4f

/** Suggestions are rebuilt this often when enabled. */
const val SUGGESTIONS_INTERVAL_HOURS = 6

/** Tag lists are edited as comma-separated text ("Romance, Action,"); blanks and trailing commas are ignored. */
fun parseSuggestionTags(text: String?): Set<String> {
    val trimmed = text?.trimEnd(' ', ',')
    if (trimmed.isNullOrEmpty()) return emptySet()
    return trimmed.split(',').mapTo(LinkedHashSet()) { it.trim() }
}

/** Fuzzy tag title comparison, e.g. `{ a, b -> a.almostEquals(b, SUGGESTION_TAG_THRESHOLD) }`. */
typealias SuggestionTagMatcher = (String, String) -> Boolean

data class SuggestionLimits(
    /** Suggestions stored after ranking. */
    val maxResults: Int = 160,
    /** Items kept from one source's list. */
    val maxSourceResults: Int = 20,
    /** Items one source may contribute to the ranked list. */
    val maxResultsPerSource: Int = 12,
    /** Seed tags taken from history and favourites. */
    val seedTags: Int = 10,
    /** History entries and favourites used as the seed, each. */
    val seedItems: Int = 20,
)

/** Sort orders tried for a source's list, in preference order. */
val SuggestionPreferredSortOrders = listOf("UPDATED", "NEWEST", "POPULARITY", "RATING")

fun pickSuggestionSortOrder(available: Collection<String>): String =
    SuggestionPreferredSortOrders.firstOrNull { it in available } ?: available.firstOrNull() ?: "UPDATED"

/** The [limit] most frequent values, most frequent first; ties keep first appearance. */
fun <T> List<T>.mostFrequent(limit: Int): List<T> {
    val counts = LinkedHashMap<T, Int>(size)
    for (item in this) counts[item] = (counts[item] ?: 0) + 1
    return counts.entries.sortedByDescending { it.value }.take(limit.coerceAtLeast(0)).map { it.key }
}

/** Whitelisted tags first, then the seed's most frequent tag titles. */
fun suggestionSeedTags(whitelist: List<String>, seedTagTitles: List<String>, limit: Int = SuggestionLimits().seedTags): List<String> =
    (whitelist + seedTagTitles.mostFrequent(limit)).distinct()

/** Blacklisted tag titles; a work matches when any of its tags is close to one. */
class SuggestionTagBlacklist(private val tags: Set<String>, private val matches: SuggestionTagMatcher) {
    fun isNotEmpty(): Boolean = tags.isNotEmpty()

    fun containsTitle(title: String): Boolean = tags.any { matches(it, title) }

    fun containsAny(titles: Collection<String>): Boolean =
        tags.isNotEmpty() && titles.any { title -> tags.any { matches(title, it) } }
}

/** The first seed tag the source offers (and that is not blacklisted), used to filter its list. */
fun <T> pickSuggestionTag(
    seedTags: List<String>,
    availableTags: Collection<T>,
    title: (T) -> String,
    isBlacklisted: (T) -> Boolean,
    matches: SuggestionTagMatcher,
): T? = seedTags.firstNotNullOfOrNull { seed ->
    availableTags.find { tag -> !isBlacklisted(tag) && matches(title(tag), seed) }
}

/**
 * Relevance in 0..1: tags earlier in [seedTags] weigh more, normalised by the best possible score for this many
 * tags and squared to favour strong matches.
 */
fun suggestionRelevance(tagTitles: Collection<String>, seedTags: List<String>, matches: SuggestionTagMatcher): Float {
    if (tagTitles.isEmpty() || seedTags.isEmpty()) return 0f
    val maxWeight = (seedTags.size + seedTags.size + 1 - tagTitles.size) * tagTitles.size / 2.0
    val weight = tagTitles.sumOf { tag ->
        val index = seedTags.indexOfFirst { matches(it, tag) }
        if (index < 0) 0 else seedTags.size - index
    }
    return (weight / maxWeight).pow(2.0).toFloat()
}

/** A source's list as Android keeps it: titled, addressable, allowed, shuffled and capped. */
fun <T> cleanSuggestionList(
    items: List<T>,
    title: (T) -> String,
    hasAddress: (T) -> Boolean,
    excluded: (T) -> Boolean,
    limits: SuggestionLimits = SuggestionLimits(),
    shuffle: (MutableList<T>) -> Unit = { it.shuffle() },
): List<T> {
    val list = items.filterTo(ArrayList()) { title(it).isNotBlank() && hasAddress(it) && !excluded(it) }
    shuffle(list)
    return list.take(limits.maxSourceResults)
}

data class RankedSuggestion<T>(val item: T, val relevance: Float)

/**
 * Sorts by relevance, drops duplicates, balances sources (preferred first) and re-encodes the final rank as the
 * stored relevance, since the table is read back ordered by relevance.
 */
fun <T> rankSuggestions(
    items: List<T>,
    relevance: (T) -> Float,
    id: (T) -> Long,
    source: (T) -> String,
    preferredSources: Set<String> = emptySet(),
    limits: SuggestionLimits = SuggestionLimits(),
): List<RankedSuggestion<T>> = items
    .map { RankedSuggestion(it, relevance(it)) }
    .sortedByDescending { it.relevance }
    .distinctBy { id(it.item) }
    .selectBalancedByPreferredSource(limits.maxResults, limits.maxResultsPerSource, preferredSources) { source(it.item) }
    .mapIndexed { index, ranked -> ranked.copy(relevance = (limits.maxResults - index).toFloat() / limits.maxResults) }
