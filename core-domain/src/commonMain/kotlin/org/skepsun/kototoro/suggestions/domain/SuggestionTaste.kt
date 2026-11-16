package org.skepsun.kototoro.suggestions.domain

import org.skepsun.kototoro.core.model.TagTaxonomy
import org.skepsun.kototoro.core.model.TagTitleBlacklist
import org.skepsun.kototoro.core.model.TaxonomyCategory
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * What the reader's library says about their taste, and how well a candidate fits it. Shared by every host: the
 * suggestions worker on Android and the source-protocol generator behind Windows and Apple.
 */

/** One work on the shelves or in history, as evidence of what the reader likes. */
data class LibraryWork(
    /** The title and every other name the work goes by. */
    val titles: Collection<String>,
    /** Tag titles as the source spells them. */
    val tags: Collection<String>,
    val isFavourite: Boolean = false,
    /** Reading progress in 0..1, or null when it is not known. */
    val percent: Float? = null,
    /** Chapters the source lists, 0 when not known. */
    val chapters: Int = 0,
    /** When it was last read, epoch millis; 0 for a work that was only saved. */
    val lastReadAt: Long = 0L,
    /** The library's id for it, 0 when there is none. */
    val id: Long = 0L,
)

/**
 * One key per meaning of a tag, however a source spells it or in which language: the taxonomy id when the catalogue
 * knows the tag, the normalised title otherwise. Tags that only say how a work is published say nothing about taste
 * and yield no key.
 */
fun suggestionTagKeys(title: String): Set<String> {
    val known = TagTaxonomy.resolve(title)
    if (known.isNotEmpty()) {
        return known.mapNotNullTo(LinkedHashSet(known.size)) { tag ->
            tag.id.takeUnless { tag.category == TaxonomyCategory.FORMAT }
        }
    }
    val raw = TagTaxonomy.normalize(title)
    return if (raw.isEmpty()) emptySet() else setOf(TagTitleBlacklist.RAW_TAG_PREFIX + raw)
}

fun suggestionTagKeys(titles: Collection<String>): Set<String> = titles.flatMapTo(LinkedHashSet()) { suggestionTagKeys(it) }

/** Two spellings of one title share a key: letters and digits only, lower case. */
fun suggestionTitleKey(title: String): String = buildString(title.length) {
    for (char in title) if (char.isLetterOrDigit()) append(char.lowercaseChar())
}

/**
 * How rare each tag is among a set of works, as an inverse document frequency. A tag every candidate carries tells
 * candidates apart no better than no tag at all, while a rare one the reader keeps coming back to is the whole point.
 */
class TagRarity private constructor(private val works: Int, private val counts: Map<String, Int>) {

    operator fun invoke(key: String): Double = ln((works + 2.0) / ((counts[key] ?: 0) + 1.0))

    companion object {

        /** Every tag counts the same: what is left when there is nothing to compare against. */
        val Uniform = TagRarity(0, emptyMap())

        fun of(works: Collection<Set<String>>): TagRarity {
            val counts = HashMap<String, Int>()
            for (keys in works) for (key in keys) counts[key] = (counts[key] ?: 0) + 1
            return TagRarity(works.size, counts)
        }
    }
}

/**
 * The reader's taste: each tag's share of the library by weight, and every title already on the shelves.
 * Build one with [SuggestionProfile.of].
 */
class SuggestionProfile internal constructor(
    /** Tag key to its share of the library's weight, in 0..1. */
    val taste: Map<String, Double>,
    private val knownTitles: Set<String>,
    private val knownIds: Set<Long>,
    private val spellings: Map<String, List<String>>,
    private val pinned: List<String>,
) {

    val isEmpty: Boolean get() = taste.isEmpty()

    /** Whether a work is already in the library: the same entry, or its title or any other name it goes by. */
    fun knows(id: Long, titles: Collection<String>): Boolean = id in knownIds || knows(titles)

    fun knows(titles: Collection<String>): Boolean = titles.any { title ->
        suggestionTitleKey(title).let { it.isNotEmpty() && it in knownTitles }
    }

    /**
     * How much of the taste a work's tags echo, each tag counted by how rare it is. Divided by the root of the tag
     * count: as a plain sum the most heavily tagged work always wins, only because its source lists more tags.
     */
    fun affinity(tagKeys: Set<String>, rarity: TagRarity = TagRarity.Uniform): Double {
        if (tagKeys.isEmpty() || taste.isEmpty()) return 0.0
        return tagKeys.sumOf { key -> (taste[key] ?: 0.0) * rarity(key) } / sqrt(tagKeys.size.toDouble())
    }

    /**
     * Tag keys to ask for, most wanted first: what the reader pinned, then their taste by share. [pick] in 0..1
     * chooses which of the first few leads, in proportion to their share, so that sources asked with different
     * picks are asked for different things.
     */
    private fun queryKeys(limit: Int, pick: Double): List<String> {
        val strongest = taste.entries.sortedByDescending { it.value }.map { it.key }
        val keys = (pinned.flatMap { suggestionTagKeys(it) } + strongest).distinct().take(limit.coerceAtLeast(0))
        val leads = keys.take(QUERY_DIRECTIONS)
        val total = leads.sumOf { taste[it] ?: 0.0 }
        if (leads.size < 2 || total <= 0.0) return keys
        var left = pick.coerceIn(0.0, 1.0) * total
        var lead = leads.last()
        for (key in leads) {
            left -= taste[key] ?: 0.0
            if (left < 0.0) {
                lead = key
                break
            }
        }
        return listOf(lead) + (keys - lead)
    }

    /**
     * Tag titles to ask a source for, the most wanted first, in every spelling the library has seen.
     * Sources disagree on spelling; [pickQueryTag] also knows two spellings of one tag by their meaning.
     */
    fun queryTags(limit: Int = SuggestionLimits().seedTags, pick: Double = 0.0): List<String> =
        (pinned + queryKeys(limit, pick).flatMap { spellings[it].orEmpty() }).distinct()

    /**
     * The tag of a source to filter its list by: the most wanted one it offers, whatever it calls it or in which
     * language, and failing that one spelled nearly like a tag the reader has read.
     */
    fun <T> pickQueryTag(
        available: Collection<T>,
        title: (T) -> String,
        isBlacklisted: (T) -> Boolean,
        matches: SuggestionTagMatcher,
        pick: Double = 0.0,
        limit: Int = SuggestionLimits().seedTags,
    ): T? {
        return pickQuery(available, title, isBlacklisted, matches, pick, limit)?.first
    }

    /** [pickQueryTag] with the taste it answers, as a tag key. */
    internal fun <T> pickQuery(
        available: Collection<T>,
        title: (T) -> String,
        isBlacklisted: (T) -> Boolean,
        matches: SuggestionTagMatcher,
        pick: Double,
        limit: Int,
    ): Pair<T, String>? {
        if (available.isEmpty()) return null
        val offered = available.filterNot(isBlacklisted)
        val byKey = HashMap<String, T>()
        offered.forEach { tag -> suggestionTagKeys(title(tag)).forEach { key -> byKey.getOrPut(key) { tag } } }
        return queryKeys(limit, pick).firstNotNullOfOrNull { key ->
            val tag = byKey[key] ?: (spellings[key].orEmpty() + pinned.filter { key in suggestionTagKeys(it) })
                .firstNotNullOfOrNull { spelling -> offered.find { matches(title(it), spelling) } }
            tag?.let { it to key }
        }
    }

    companion object {

        /** How many of the strongest tastes sources are spread over. */
        private const val QUERY_DIRECTIONS = 4

        /** Opened or read past this point, a work counts as read rather than merely opened. */
        private const val READ_PERCENT = 0.5f
        private const val WEIGHT_READ = 2.0
        private const val WEIGHT_OPENED = 1.0
        private const val WEIGHT_FAVOURITE = 1.0

        /** Saved and never opened: an intention, which says less than anything actually read. */
        private const val WEIGHT_SAVED_UNREAD = 0.5

        /** The last this many works read say more about what is wanted now. */
        private const val RECENT_WORKS = 60
        private const val RECENT_FACTOR = 1.5

        /** Under a tenth read and untouched for three weeks is left, not paused. */
        private const val DROPPED_PERCENT = 0.1f
        private const val DROPPED_AFTER_DAYS = 21.0

        /** Chapters read for a work to count twice as much as one merely opened, and how far that can go. */
        private const val ENGAGEMENT_CHAPTERS = 8.0
        private const val ENGAGEMENT_MAX = 3.0

        /** How long until something read says half as much, and how little it can come to. */
        private const val FADE_HALF_LIFE_DAYS = 180.0
        private const val FADE_FLOOR = 0.25
        private const val DAY_MS = 24 * 60 * 60 * 1000.0

        /**
         * @param library most recently read first; works that were only saved may come in any order
         * @param pinnedTags tags the reader asked for outright, counted as much as their strongest taste
         */
        fun of(library: List<LibraryWork>, now: Long, pinnedTags: Collection<String> = emptyList()): SuggestionProfile {
            val weights = HashMap<String, Double>()
            val spellings = HashMap<String, HashMap<String, Double>>()
            val known = HashSet<String>(library.size * 2)
            val knownIds = library.mapNotNullTo(HashSet(library.size * 2)) { work -> work.id.takeIf { it != 0L } }
            var total = 0.0
            var read = 0
            for (work in library) {
                work.titles.forEach { title -> suggestionTitleKey(title).takeIf { it.isNotEmpty() }?.let(known::add) }
                val weight = weightOf(work, if (work.lastReadAt > 0L) read++ else null, now)
                if (weight <= 0.0) continue
                total += weight
                val seen = HashSet<String>()
                for (title in work.tags) {
                    for (key in suggestionTagKeys(title)) {
                        if (seen.add(key)) weights[key] = (weights[key] ?: 0.0) + weight
                        val titles = spellings.getOrPut(key) { HashMap() }
                        titles[title] = (titles[title] ?: 0.0) + weight
                    }
                }
            }
            val taste = if (total > 0.0) weights.mapValuesTo(HashMap()) { it.value / total } else HashMap()
            val pinned = pinnedTags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val strongest = taste.values.maxOrNull() ?: 1.0
            pinned.forEach { title ->
                suggestionTagKeys(title).forEach { key -> taste[key] = maxOf(taste[key] ?: 0.0, strongest) }
            }
            return SuggestionProfile(
                taste = taste,
                knownTitles = known,
                knownIds = knownIds,
                spellings = spellings.mapValues { (_, titles) -> titles.entries.sortedByDescending { it.value }.map { it.key } },
                pinned = pinned,
            )
        }

        /**
         * How much one work says about the reader. Reading most of something says more than opening it, time spent
         * more than either, and what is read now more than what was read years ago. Opened, barely read and left
         * says nothing, unless it was kept as a favourite anyway.
         *
         * @param recency how many works were read since, null for one never opened
         */
        internal fun weightOf(work: LibraryWork, recency: Int?, now: Long): Double {
            val percent = work.percent?.takeIf { it >= 0f }
            val ageDays = if (work.lastReadAt > 0L) (now - work.lastReadAt).coerceAtLeast(0L) / DAY_MS else null
            if (recency == null) {
                return if (work.isFavourite) WEIGHT_SAVED_UNREAD else 0.0
            }
            val isDropped = percent != null && percent <= DROPPED_PERCENT && ageDays != null && ageDays > DROPPED_AFTER_DAYS
            if (isDropped && !work.isFavourite) return 0.0
            var weight = if (percent != null && percent >= READ_PERCENT) WEIGHT_READ else WEIGHT_OPENED
            if (work.isFavourite) weight += WEIGHT_FAVOURITE
            if (recency < RECENT_WORKS) weight *= RECENT_FACTOR
            val chaptersRead = (percent ?: 0f) * work.chapters.coerceAtLeast(0)
            weight *= (1.0 + ln(1.0 + chaptersRead / ENGAGEMENT_CHAPTERS) / ln(2.0)).coerceAtMost(ENGAGEMENT_MAX)
            if (ageDays != null) weight *= 0.5.pow(ageDays / FADE_HALF_LIFE_DAYS).coerceAtLeast(FADE_FLOOR)
            return weight
        }
    }
}
