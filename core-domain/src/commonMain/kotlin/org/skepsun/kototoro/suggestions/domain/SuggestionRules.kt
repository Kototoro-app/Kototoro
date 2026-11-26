package org.skepsun.kototoro.suggestions.domain

import kotlin.math.pow

/*
 * The suggestion rules shared by Android, Windows and Apple hosts. Each source is asked for two lists (one tagged by
 * what the reader's [SuggestionProfile] says they like, one of what is new); [rankSuggestions] scores what came back
 * and deals it out source by source, so no source can fill the list. JVM hosts inject the parser's `almostEquals`;
 * Apple uses [suggestionTagsMatch] with the same [SUGGESTION_TAG_THRESHOLD].
 */

/** Tag titles closer than this (normalised edit distance) count as the same tag. */
const val SUGGESTION_TAG_THRESHOLD = 0.4f

/** Suggestions are rebuilt this often when enabled. */
const val SUGGESTIONS_INTERVAL_HOURS = 6

/** Tag lists are edited as comma-separated text ("Romance, Action,"); blanks and trailing commas are ignored. */
fun parseSuggestionTags(text: String?): Set<String> {
    val trimmed = text?.trimEnd(' ', ',')
    if (trimmed.isNullOrEmpty()) return emptySet()
    return trimmed.split(',', '，', ';', '；', '\n', '\r')
        .map(String::trim).filter(String::isNotEmpty).toCollection(LinkedHashSet())
}

/** Fuzzy tag title comparison, e.g. `{ a, b -> a.almostEquals(b, SUGGESTION_TAG_THRESHOLD) }`. */
typealias SuggestionTagMatcher = (String, String) -> Boolean

/** Portable counterpart of the parser's case-insensitive `almostEquals`, using mean tag length. */
fun suggestionTagsMatch(left: String, right: String): Boolean {
    val a = left.lowercase()
    val b = right.lowercase()
    if (a.isEmpty() || b.isEmpty()) return false
    var costs = IntArray(a.length + 1) { it }
    var next = IntArray(a.length + 1)
    for (i in 1..b.length) {
        next[0] = i
        for (j in 1..a.length) {
            next[j] = minOf(costs[j] + 1, next[j - 1] + 1, costs[j - 1] + if (a[j - 1] == b[i - 1]) 0 else 1)
        }
        val previous = costs
        costs = next
        next = previous
    }
    return costs[a.length] / ((left.length + right.length) / 2f) < SUGGESTION_TAG_THRESHOLD
}

data class SuggestionLimits(
    /** Suggestions stored after ranking. */
    val maxResults: Int = 160,
    /** Items kept from one list of one source. */
    val maxSourceResults: Int = 20,
    /** Seed tags taken from history and favourites. */
    val seedTags: Int = 10,
    /** History entries read for the reader's taste, most recent first; favourites are read whole. */
    val libraryHistory: Int = 500,
)

/**
 * Sort orders tried for a source's list, in preference order. What is popular in a genre the reader likes says more
 * about what they would like than what happened to be updated last; works they already have are left out afterwards.
 */
val SuggestionPreferredSortOrders = listOf("POPULARITY_MONTH", "POPULARITY_WEEK", "POPULARITY", "RATING", "UPDATED", "NEWEST")

fun pickSuggestionSortOrder(available: Collection<String>): String =
    SuggestionPreferredSortOrders.firstOrNull { it in available } ?: available.firstOrNull() ?: "UPDATED"

/**
 * Where in the reader's taste one source starts looking, in 0..1: a different place for each source and each [seed],
 * so that between them the sources cover what the reader likes instead of all being asked for the same genre.
 */
fun suggestionQueryPick(sourceName: String, seed: Long): Double =
    kotlin.random.Random(seed xor (sourceName.hashCode().toLong() * 0x9E3779B97F4A7C15uL.toLong())).nextDouble()

/** The [limit] most frequent values, most frequent first; ties keep first appearance. */
fun <T> List<T>.mostFrequent(limit: Int): List<T> {
    val counts = LinkedHashMap<T, Int>(size)
    for (item in this) counts[item] = (counts[item] ?: 0) + 1
    return counts.entries.sortedByDescending { it.value }.take(limit.coerceAtLeast(0)).map { it.key }
}

/**
 * Whitelisted tags first, then the seed's most frequent tag titles. No longer what suggestions are built from (see
 * [SuggestionProfile]); kept as the baseline the hold-out evaluation compares against.
 */
fun suggestionSeedTags(whitelist: List<String>, seedTagTitles: List<String>, limit: Int = SuggestionLimits().seedTags): List<String> =
    (whitelist + seedTagTitles.mostFrequent(limit)).distinct()

/** Blacklisted tag titles; a work matches when any of its tags is close to one. */
class SuggestionTagBlacklist(private val tags: Set<String>, private val matches: SuggestionTagMatcher) {
    private val canonical = org.skepsun.kototoro.core.model.TagTitleBlacklist(tags)
    private val freeForm = tags.filter {
        org.skepsun.kototoro.core.model.TagTaxonomy.find(it) == null &&
            org.skepsun.kototoro.core.model.TagTaxonomy.resolve(it).isEmpty() && !it.startsWith("raw:")
    }
    fun isNotEmpty(): Boolean = tags.isNotEmpty()

    fun containsTitle(title: String): Boolean = canonical.containsTagTitle(title) || freeForm.any { matches(it, title) }

    fun containsAny(titles: Collection<String>): Boolean =
        tags.isNotEmpty() && titles.any(::containsTitle)
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
 * tags and squared to favour strong matches. The baseline of the hold-out evaluation, like [suggestionSeedTags].
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

/**
 * A source's list as it is kept: titled, addressable, allowed and capped. When a list is longer than the cap, what
 * stays is what fits the reader best ([fit], ties in the source's own order) rather than whatever a shuffle left.
 */
fun <T> cleanSuggestionList(
    items: List<T>,
    title: (T) -> String,
    hasAddress: (T) -> Boolean,
    excluded: (T) -> Boolean,
    limits: SuggestionLimits = SuggestionLimits(),
    fit: (T) -> Double = { 0.0 },
): List<T> = items
    .filter { title(it).isNotBlank() && hasAddress(it) && !excluded(it) }
    .map { it to fit(it) }
    .sortedByDescending { it.second }
    .take(limits.maxSourceResults)
    .map { it.first }

data class RankedSuggestion<T>(
    val item: T,
    val relevance: Float,
    val direction: SuggestionDirection = SuggestionDirection.TASTE,
    val reason: String? = null,
)

/** How fast a place further down a source's list counts for less: half as much this many places down. */
private const val RANK_DAMPING = 10.0

/** How far the reader's taste can move a work from the place its source gave it. */
private const val TASTE_NUDGE = 1.0

/** Of every this many suggestions, one is a community pick while there are any left. */
const val SUGGESTIONS_COMMUNITY_EVERY = 4

/** A source the reader picked as preferred is dealt at least this many places a round. */
private const val PREFERRED_WEIGHT = 2.0

/** However little a source weighs it is dealt a place now and then, so a round always makes progress. */
private const val MIN_WEIGHT = 0.25

/**
 * The same work listed by several sources, under any name they share, is one candidate. Groups keep the order
 * their first member arrived in.
 */
internal fun <T> mergeByTitle(items: List<T>, titles: (T) -> Collection<String>): List<List<T>> {
    val byKey = HashMap<String, MutableList<T>>()
    val groups = ArrayList<MutableList<T>>()
    for (item in items) {
        val keys = titles(item).map(::suggestionTitleKey).filter { it.isNotEmpty() }
        val group = keys.firstNotNullOfOrNull { byKey[it] } ?: ArrayList<T>(1).also { groups += it }
        group += item
        keys.forEach { if (it !in byKey) byKey[it] = group }
    }
    return groups
}

/**
 * Ranks what the sources answered for this reader.
 *
 * Works already on the shelves are left out and the same work from several sources is one candidate. A candidate keeps
 * the place its source gave it, nudged by how much of the reader's taste its tags echo (each tag counted by how rare it
 * is among the candidates) and scaled by [quality]; what was asked for by taste is on the same scale as the rest.
 * The suggestions are then dealt out source by source, best first: a source with a long list cannot crowd out the rest,
 * and how many places each gets in turn follows how much of the library came from it
 * ([SuggestionProfile.sourceWeight]), at least two for a [preferredSources] one; every
 * [SUGGESTIONS_COMMUNITY_EVERY]th place goes to a community pick. Nothing here is random: the same answers give the
 * same list. The returned relevance encodes that order, since the table is read back ordered by relevance:
 * unfiltered it is the mix, filtered by direction it is that direction's own ranking.
 */
fun <T> rankSuggestions(
    candidates: List<SuggestionCandidate<T>>,
    profile: SuggestionProfile,
    id: (T) -> Long,
    source: (T) -> String,
    titles: (T) -> Collection<String>,
    tags: (T) -> Collection<String>,
    preferredSources: Set<String> = emptySet(),
    limits: SuggestionLimits = SuggestionLimits(),
    languagePriority: (T) -> Boolean = { false },
    /** Whether a candidate is adult: it is scored by what the reader likes among adult works, else among the rest. */
    isAdult: (T) -> Boolean = { false },
    /** Whether a source's share of places follows how much of the library came from it; off, all are alike. */
    weighSources: Boolean = true,
    /** How good each candidate is, as a factor on its score; see [SuggestionQuality]. */
    quality: (T) -> Double = { SuggestionQuality.NEUTRAL },
): List<RankedSuggestion<T>> {
    if (limits.maxResults <= 0) return emptyList()
    val fresh = candidates.filterNot { profile.knows(id(it.item), titles(it.item)) }
    val groups = mergeByTitle(fresh) { titles(it.item) }
    val rarity = TagRarity.of(groups.map { group -> suggestionTagKeys(group.flatMapTo(LinkedHashSet()) { tags(it.item) }) })
    class Scored(val candidate: SuggestionCandidate<T>, val score: Double)

    fun score(candidate: SuggestionCandidate<T>): Double {
        val affinity = profile.forDomain(isAdult(candidate.item)).affinity(suggestionTagKeys(tags(candidate.item)), rarity)
        // One scale for every direction: sources are dealt from together, so a direction scored on a smaller one
        // would sit at the bottom of every queue and never be picked.
        val fit = (1.0 + TASTE_NUDGE * affinity) / (1.0 + candidate.rank.coerceAtLeast(0) / RANK_DAMPING)
        return fit * quality(candidate.item)
    }

    val scored = groups.map { group ->
        val direction = if (group.any { it.direction == SuggestionDirection.COMMUNITY }) SuggestionDirection.COMMUNITY
            else SuggestionDirection.entries.first { direction -> group.any { it.direction == direction } }
        val chosen = group.filter { it.direction == direction }.sortedWith(
            compareByDescending<SuggestionCandidate<T>> { languagePriority(it.item) }
                .thenByDescending { source(it.item) in preferredSources }
                .thenByDescending(::score)
                .thenBy { id(it.item) },
        ).first()
        Scored(chosen, score(chosen))
    }.sortedWith(compareByDescending<Scored> { it.score }.thenBy { id(it.candidate.item) })

    val (community, regular) = scored.partition { it.candidate.direction == SuggestionDirection.COMMUNITY }
    val queues = regular.groupBy { source(it.candidate.item) }.entries.sortedBy { it.key }
        .map { (name, list) -> name to list.iterator() }
    val credit = HashMap<String, Double>()
    fun weightOf(name: String) = maxOf(
        MIN_WEIGHT,
        if (weighSources) profile.sourceWeight(name) else 1.0,
        if (name in preferredSources) PREFERRED_WEIGHT else 0.0,
    )
    val rounds = sequence {
        while (queues.any { it.second.hasNext() }) {
            val round = ArrayList<Scored>()
            for ((name, queue) in queues) {
                // A source that weighs more than one is dealt several places a round, one that weighs less every few.
                val owed = (credit[name] ?: 0.0) + weightOf(name)
                val turns = owed.toInt()
                credit[name] = owed - turns
                repeat(turns) { if (queue.hasNext()) round += queue.next() }
            }
            // Within a round the sources are equal; the order they come in is the order of what they offer.
            yieldAll(
                round.sortedWith(
                    compareByDescending<Scored> { languagePriority(it.candidate.item) }
                        .thenByDescending { source(it.candidate.item) in preferredSources }
                        .thenByDescending { it.score }
                        .thenBy { id(it.candidate.item) },
                ),
            )
        }
    }.iterator()
    val picks = community.iterator()
    val seen = HashSet<Long>()
    val selected = ArrayList<SuggestionCandidate<T>>()
    while (selected.size < limits.maxResults) {
        val communityTurn = (selected.size + 1) % SUGGESTIONS_COMMUNITY_EVERY == 0
        val next = when {
            communityTurn && picks.hasNext() -> picks.next()
            rounds.hasNext() -> rounds.next()
            picks.hasNext() -> picks.next()
            else -> break
        }.candidate
        if (seen.add(id(next.item))) selected += next
    }
    return selected.sortedByDescending { languagePriority(it.item) }.mapIndexed { index, candidate ->
        RankedSuggestion(candidate.item, (limits.maxResults - index).toFloat() / limits.maxResults,
            candidate.direction, candidate.reason)
    }
}
