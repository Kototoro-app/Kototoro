package org.skepsun.kototoro.suggestions.domain

import kotlin.math.ln
import kotlin.math.pow

/*
 * Android's suggestion rules, shared with Apple and Windows hosts: one tagged list per source, asked for what the
 * reader's [SuggestionProfile] says they like, then ranked by how well each candidate fits it. JVM hosts inject the parser's `almostEquals`;
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
    /** Items kept from one source's list. */
    val maxSourceResults: Int = 20,
    /** Items one source may contribute to the ranked list. */
    val maxResultsPerSource: Int = 12,
    /** Seed tags taken from history and favourites. */
    val seedTags: Int = 10,
    /** History entries read for the reader's taste, most recent first; favourites are read whole. */
    val libraryHistory: Int = 500,
    /** Suggestions at the top of the list that do not move from one generation to the next. */
    val stableHead: Int = 12,
    /** Largest share of the list one group (see [SuggestionOrigins]) may take while others still have candidates. */
    val maxGroupShare: Float = 0.4f,
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

/** A candidate nothing is known about still gets shown, after everything that matches. */
private const val BASE_SCORE = 0.05

/** How much each doubling of the sources listing a work adds: agreement counts, mirrors of one site less so. */
private const val AGREEMENT_WEIGHT = 0.15

/** A suggestion left unopened says half as much after this long on the list, and never less than the floor. */
private const val STALE_HALF_LIFE_DAYS = 5.0
private const val STALE_FLOOR = 0.3
private const val DAY_MS = 24 * 60 * 60 * 1000.0

/**
 * How far down the list chance reaches: 0 keeps the order, larger lets weaker matches surface more often. Kept low now
 * that quality counts: the rotation is for variety between days and refreshes, not for overruling the ranking.
 */
private const val ROTATION_TEMPERATURE = 0.25

/** How much a full tag overlap with an earlier pick costs, at most: half its score. */
private const val DIVERSITY = 0.5

/**
 * The [sorted] candidates re-ranked so a direction is not twenty variations of one thing: greedy maximal marginal
 * relevance, each pick's score discounted by its largest tag overlap (Jaccard) with what was already picked.
 */
internal fun <T> diversifyByTags(sorted: List<T>, score: (T) -> Double, tags: (T) -> Set<String>): List<Pair<T, Double>> {
    val items = sorted.toMutableList()
    val itemTags = items.mapTo(ArrayList(items.size)) { tags(it) }
    val baseScores = items.mapTo(ArrayList(items.size)) { score(it) }
    // Each candidate's largest overlap with anything picked so far, updated with every new pick: O(n²) overall.
    val overlap = MutableList(items.size) { 0.0 }
    val picked = ArrayList<Pair<T, Double>>(items.size)
    while (items.isNotEmpty()) {
        var best = 0
        for (index in 1 until items.size) {
            if (baseScores[index] * (1.0 - DIVERSITY * overlap[index]) > baseScores[best] * (1.0 - DIVERSITY * overlap[best])) {
                best = index
            }
        }
        val pickedTags = itemTags[best]
        picked += items[best] to baseScores[best] * (1.0 - DIVERSITY * overlap[best])
        items.removeAt(best)
        itemTags.removeAt(best)
        baseScores.removeAt(best)
        overlap.removeAt(best)
        if (pickedTags.isEmpty()) continue
        for (index in items.indices) {
            val own = itemTags[index]
            if (own.isEmpty()) continue
            val shared = own.count { it in pickedTags }
            overlap[index] = maxOf(overlap[index], shared.toDouble() / (own.size + pickedTags.size - shared))
        }
    }
    return picked
}

/**
 * The same work listed by several sources, under any name they share, is one candidate. Groups keep the order
 * their first member arrived in.
 */
internal fun <T> mergeByTitle(items: List<T>, titles: (T) -> Collection<String>): List<List<T>> {
    val parent = IntArray(items.size) { it }
    fun find(index: Int): Int {
        var root = index
        while (parent[root] != root) root = parent[root]
        var node = index
        while (parent[node] != root) node = parent[node].also { parent[node] = root }
        return root
    }
    val owners = HashMap<String, Int>()
    items.forEachIndexed { index, item ->
        for (title in titles(item)) {
            val key = suggestionTitleKey(title)
            if (key.isEmpty()) continue
            val owner = owners.getOrPut(key) { index }
            // The smaller root wins, so a group is found under its first member
            val a = find(owner)
            val b = find(index)
            if (a != b) parent[maxOf(a, b)] = minOf(a, b)
        }
    }
    val groups = LinkedHashMap<Int, MutableList<T>>()
    items.forEachIndexed { index, item -> groups.getOrPut(find(index)) { ArrayList(1) } += item }
    return groups.values.toList()
}

/**
 * The best [stable] stay where they are; the rest are drawn by score, differently for each [seed]. A weaker match
 * comes up now and then instead of never, while the order still mostly follows the scores: a plain shuffle of the
 * rest would throw the ranking away.
 */
internal fun <T> rotateRanked(sorted: List<T>, score: (T) -> Double, seed: Long, stable: Int): List<T> {
    if (sorted.size <= stable + 1) return sorted
    val random = kotlin.random.Random(seed)
    val head = sorted.take(stable.coerceAtLeast(0))
    val tail = sorted.drop(head.size).map { item ->
        val noise = -ln(-ln(random.nextDouble().coerceIn(1e-12, 1.0 - 1e-12)))
        item to ln(score(item).coerceAtLeast(1e-9)) + ROTATION_TEMPERATURE * noise
    }
    return head + tail.sortedByDescending { it.second }.map { it.first }
}

/** How fast a place further down a source's list counts for less: half as much this many places down. */
private const val RANK_DAMPING = 10.0

/** How far the reader's taste can move a work on a list that was not asked for by taste. */
private const val TASTE_NUDGE = 1.0

/** Of every ten suggestions, how many each direction gets while it has any left. */
private val DirectionShares = mapOf(
    SuggestionDirection.TASTE to 5,
    SuggestionDirection.POPULAR to 2,
    SuggestionDirection.NEW to 2,
    SuggestionDirection.EXPLORE to 1,
)

/**
 * Ranks what the sources answered for this reader.
 *
 * Works already on the shelves are left out and the same work from several sources is merged. What was asked for by
 * taste is scored by how much of the reader's taste its tags echo, each tag counted by how rare it is among the
 * candidates; what is popular, new or unexplored keeps the order the sources gave it, with taste as a nudge. A
 * suggestion that has been on the list for days without being opened sinks, the top of each direction stays put and
 * the rest rotates with [seed]. Preferred sources come first and no source takes more than its share. The directions
 * are then dealt into one list in fixed proportions, and that order is re-encoded as the stored relevance, since the
 * table is read back ordered by relevance: unfiltered it is the mix, filtered by direction it is that direction's
 * own ranking.
 *
 * @param firstSuggestedAt when a candidate was first put on the list it is still on, null for a new one
 */
fun <T> rankSuggestions(
    candidates: List<SuggestionCandidate<T>>,
    profile: SuggestionProfile,
    id: (T) -> Long,
    source: (T) -> String,
    titles: (T) -> Collection<String>,
    tags: (T) -> Collection<String>,
    now: Long,
    seed: Long = suggestionRotationSeed(now),
    firstSuggestedAt: (T) -> Long? = { null },
    preferredSources: Set<String> = emptySet(),
    limits: SuggestionLimits = SuggestionLimits(),
    languagePriority: (T) -> Boolean = { false },
    groupOf: ((T) -> String)? = null,
    /** How good each candidate is, as a factor on its score; see [SuggestionQuality]. */
    quality: (T) -> Double = { SuggestionQuality.NEUTRAL },
): List<RankedSuggestion<T>> {
    if (limits.maxResults <= 0 || limits.maxResultsPerSource <= 0) return emptyList()
    val fresh = candidates.filterNot { profile.knows(id(it.item), titles(it.item)) }
    val groups = mergeByTitle(fresh) { titles(it.item) }
    val rarity = TagRarity.of(groups.map { group -> group.flatMapTo(LinkedHashSet()) { tags(it.item) }
        .let(::suggestionTagKeys) })
    data class Scored<T>(val candidate: SuggestionCandidate<T>, val score: Double)
    val scored = groups.map { group ->
        fun score(candidate: SuggestionCandidate<T>): Double {
            val affinity = profile.affinity(suggestionTagKeys(tags(candidate.item)), rarity)
            val fit = if (candidate.direction == SuggestionDirection.TASTE) BASE_SCORE + affinity
                else (1.0 + TASTE_NUDGE * affinity) / (1.0 + candidate.rank.coerceAtLeast(0) / RANK_DAMPING)
            val age = firstSuggestedAt(candidate.item)?.let { (now - it).coerceAtLeast(0L) / DAY_MS } ?: 0.0
            return fit * quality(candidate.item) * 0.5.pow(age / STALE_HALF_LIFE_DAYS).coerceAtLeast(STALE_FLOOR)
        }
        val direction = if (group.any { it.direction == SuggestionDirection.COMMUNITY }) SuggestionDirection.COMMUNITY
            else SuggestionDirection.entries.first { direction -> group.any { it.direction == direction } }
        val chosen = group.filter { it.direction == direction }.sortedWith(
            compareByDescending<SuggestionCandidate<T>> { languagePriority(it.item) }
                .thenByDescending { source(it.item) in preferredSources }
                .thenByDescending(::score)
                .thenBy { id(it.item) },
        ).first()
        val agreement = 1.0 + AGREEMENT_WEIGHT * ln(group.map { source(it.item) }.distinct().size.toDouble()) / ln(2.0)
        Scored(chosen, score(chosen) * agreement)
    }
    val queues = SuggestionDirection.entries.associateWith { direction ->
        // A preference is explicit: keep preferred sources ahead even when rotating the tail.
        val tiers = scored.filter { it.candidate.direction == direction }
            .groupBy { languagePriority(it.candidate.item) to (source(it.candidate.item) in preferredSources) }
        listOf(true to true, true to false, false to true, false to false).flatMap { tier ->
            val diversified = diversifyByTags(
                tiers[tier].orEmpty().sortedWith(compareByDescending<Scored<T>> { it.score }.thenBy { id(it.candidate.item) }),
                score = { it.score },
                tags = { suggestionTagKeys(tags(it.candidate.item)) },
            )
            rotateRanked(diversified, { it.second }, seed xor direction.ordinal.toLong(), limits.stableHead)
                .map { it.first }
        }.iterator()
    }
    val counts = HashMap<String, Int>()
    val seen = HashSet<Long>()
    val selected = ArrayList<SuggestionCandidate<T>>()
    // One group (say Chinese comics) must not fill the list: past its share a candidate waits and only fills what is left.
    val groupCap = kotlin.math.ceil(limits.maxResults * limits.maxGroupShare.toDouble()).toInt().coerceAtLeast(1)
    val groupCounts = HashMap<String, Int>()
    val deferred = ArrayList<SuggestionCandidate<T>>()
    // Interleave the shares so every available direction appears near the beginning of the mix.
    val shares = if (queues.getValue(SuggestionDirection.COMMUNITY).hasNext()) linkedMapOf(
        SuggestionDirection.TASTE to 4,
        SuggestionDirection.COMMUNITY to 3,
        SuggestionDirection.POPULAR to 1,
        SuggestionDirection.NEW to 1,
        SuggestionDirection.EXPLORE to 1,
    ) else DirectionShares
    val schedule = (0 until shares.values.max()).flatMap { turn ->
        shares.filterValues { it > turn }.keys
    }
    while (selected.size < limits.maxResults) {
        var added = false
        for (direction in schedule) {
            val queue = queues.getValue(direction)
            while (queue.hasNext()) {
                val candidate = queue.next().candidate
                val name = source(candidate.item)
                if ((counts[name] ?: 0) >= limits.maxResultsPerSource || !seen.add(id(candidate.item))) continue
                val group = groupOf?.invoke(candidate.item)
                if (group != null && (groupCounts[group] ?: 0) >= groupCap) {
                    deferred += candidate
                    continue
                }
                counts[name] = (counts[name] ?: 0) + 1
                if (group != null) groupCounts[group] = (groupCounts[group] ?: 0) + 1
                selected += candidate
                added = true
                break
            }
            if (selected.size >= limits.maxResults) break
        }
        if (!added) break
    }
    for (candidate in deferred) {
        if (selected.size >= limits.maxResults) break
        val name = source(candidate.item)
        if ((counts[name] ?: 0) >= limits.maxResultsPerSource) continue
        counts[name] = (counts[name] ?: 0) + 1
        selected += candidate
    }
    return selected.sortedByDescending { languagePriority(it.item) }.mapIndexed { index, candidate ->
        RankedSuggestion(candidate.item, (limits.maxResults - index).toFloat() / limits.maxResults,
            candidate.direction, candidate.reason)
    }
}
