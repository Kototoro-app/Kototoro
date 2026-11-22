package org.skepsun.kototoro.community.domain

import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.ln
import org.skepsun.kototoro.suggestions.domain.LibraryWork
import org.skepsun.kototoro.suggestions.domain.SuggestionCandidate
import org.skepsun.kototoro.suggestions.domain.SuggestionDirection

data class CommunitySuggestionTarget(val groupId: String, val title: String, val medium: CommunityMedium, val reason: String?)

/** A work in the reader's library that recommendations start from: its community group and how much they like it. */
data class CommunitySeed(val groupId: String, val title: String?, val affinity: Double)

data class CommunityRecommendation(
    val groupId: String,
    val title: String?,
    val score: Double,
    /** The seeds that contributed most, strongest first; the app says "because you liked …". */
    val because: List<CommunitySeed>,
)

/**
 * Community recommendations, computed on the device from the index's similar works (plan §7.3): nothing about the
 * reader's library leaves it. A work is recommended in proportion to how similar it is to what the reader likes, and
 * held back by what they rated low.
 */
object CommunityRecommendations {

    suspend fun <Source, Work> resolve(
        targets: List<CommunitySuggestionTarget>,
        sources: Map<CommunityMedium, List<Source>>,
        cached: (Source) -> List<Work>,
        search: suspend (Source, String) -> List<Work>,
        titles: (Work) -> List<String>,
        eligible: (Source, Work) -> Boolean,
        fold: (String) -> String,
    ): List<SuggestionCandidate<Work>> {
        val results = ArrayList<SuggestionCandidate<Work>>()
        withTimeoutOrNull(15_000L) {
            for ((rank, target) in targets.withIndex()) {
                val key = CommunityWorkKeys.titleKey(target.title, target.medium, fold) ?: continue
                fun matches(source: Source, item: Work) = eligible(source, item) &&
                    key in CommunityWorkKeys.keys(target.medium, titles(item), fold = fold)
                val choices = sources[target.medium].orEmpty()
                val known = choices.firstNotNullOfOrNull { source ->
                    cached(source).firstOrNull { matches(source, it) }
                }
                val match = known ?: choices.take(3).firstNotNullOfOrNull { source ->
                    withTimeoutOrNull(4_000L) { search(source, target.title).firstOrNull { matches(source, it) } }
                } ?: continue
                results += SuggestionCandidate(match, SuggestionDirection.COMMUNITY, target.reason, rank)
            }
        }
        return results
    }

    suspend fun targets(
        library: List<LibraryWork>,
        stars: Map<Long, Int>,
        refs: Map<Long, List<CommunityExternalRef>>,
        fold: (String) -> String,
        groupsOf: suspend (Map<Long, List<String>>) -> Map<Long, String>,
        works: suspend (Collection<String>) -> Map<String, CommunityWorkSummary>,
        limit: Int = 20,
    ): List<CommunitySuggestionTarget> {
        val identified = library.filter { it.id != 0L }
        val keysById = identified.associate { item ->
            item.id to CommunityWorkKeys.keys(
                CommunityMedium.of(item.contentType), item.titles, refs[item.id].orEmpty(), fold,
            )
        }.filterValues { it.isNotEmpty() }
        if (keysById.isEmpty()) return emptyList()
        val groups = groupsOf(keysById)
        val seeds = identified.mapNotNull { item ->
            val group = groups[item.id] ?: return@mapNotNull null
            val affinity = affinity(stars[item.id], item.isFavourite, item.percent) ?: return@mapNotNull null
            CommunitySeed(group, item.titles.firstOrNull(), affinity)
        }
        if (seeds.isEmpty()) return emptyList()
        val summaries = works(seeds.map { it.groupId }.distinct())
        val knownTitles = keysById.values.flatten().filterNot(CommunityWorkKeys::isStrong).toHashSet()
        // What every candidate is like, for damping and diversifying: its readers, its tags and its own similar works.
        val candidates = works(summaries.values.flatMap { summary -> summary.similar.map { it.id } }.distinct())
        val ranked = rank(
            seeds = seeds,
            similar = { summaries[it]?.similar.orEmpty() },
            known = groups.values.toSet(),
            excluded = { work -> work.nsfw || work.title?.let { title ->
                CommunityMedium.entries.any { CommunityWorkKeys.titleKey(title, it, fold) in knownTitles }
            } == true },
            limit = limit,
            readers = { candidates[it]?.rating?.count ?: 0 },
            overlap = { a, b -> overlap(candidates[a], candidates[b]) },
        )
        val targets = candidates
        return ranked.mapNotNull { pick ->
            val summary = targets[pick.groupId]?.takeUnless { it.nsfw } ?: return@mapNotNull null
            val medium = CommunityMedium.entries.firstOrNull { it.wire == summary.medium && it != CommunityMedium.OTHER }
                ?: return@mapNotNull null
            val title = (summary.title ?: pick.title)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CommunitySuggestionTarget(pick.groupId, title, medium, pick.because.firstOrNull()?.title)
        }
    }

    /**
     * How alike two candidates are, for diversifying: the index's own similarity between them when it lists one, else
     * the overlap of their tags.
     */
    fun overlap(a: CommunityWorkSummary?, b: CommunityWorkSummary?): Double {
        if (a == null || b == null) return 0.0
        val listed = maxOf(
            a.similar.firstOrNull { it.id == b.id && it.coRaters > 0 }?.score ?: 0.0,
            b.similar.firstOrNull { it.id == a.id && it.coRaters > 0 }?.score ?: 0.0,
        )
        return maxOf(listed, CommunityGenres.overlap(a.tags, b.tags))
    }

    const val FAVOURITE_AFFINITY = 2.0
    const val FINISHED_AFFINITY = 1.5
    const val STARTED_AFFINITY = 0.5

    /**
     * How much a reader likes a work. Their own community rating says it best and can be negative (a 3 is -2.5, a 10 is
     * +4.5); without one, keeping it as a favourite or reading it through says they like it; null says nothing.
     */
    fun affinity(myStars: Int?, isFavourite: Boolean, readFraction: Float?): Double? {
        if (myStars != null) return myStars - 5.5
        return listOfNotNull(
            FAVOURITE_AFFINITY.takeIf { isFavourite },
            FINISHED_AFFINITY.takeIf { readFraction != null && readFraction >= 0.9f },
            STARTED_AFFINITY.takeIf { readFraction != null && readFraction >= 0.3f },
        ).maxOrNull()
    }

    /**
     * Ranks what [similar] lists for the [seeds]. A seed's group counts once, at its strongest affinity. [known] groups
     * (the library) and anything [excluded] (adult or blocked) are never recommended.
     *
     * Two adjustments follow, both after Kotatsu-Redo's suggestion ranking. A work many readers rated is damped by the
     * log of [readers], mildly, so popularity alone does not lift a looser match above a closer, less known one. Then
     * the list is diversified (greedy maximal marginal relevance): each pick is discounted by its [overlap] with the
     * closest pick already made, so a slightly weaker match with something new beats a near-copy of what is listed.
     */
    fun rank(
        seeds: List<CommunitySeed>,
        similar: (groupId: String) -> List<CommunitySimilarWork>,
        known: Set<String>,
        excluded: (CommunitySimilarWork) -> Boolean = { false },
        limit: Int = 30,
        readers: (groupId: String) -> Int = { 0 },
        overlap: (a: String, b: String) -> Double = { _, _ -> 0.0 },
    ): List<CommunityRecommendation> {
        val strongest = seeds.groupBy { it.groupId }.mapValues { (_, list) -> list.maxBy { abs(it.affinity) } }
        val scores = HashMap<String, Double>()
        val titles = HashMap<String, String?>()
        val contributions = HashMap<String, MutableList<Pair<CommunitySeed, Double>>>()
        for (seed in strongest.values) {
            for (work in similar(seed.groupId)) {
                if (work.id in known || work.id in strongest || excluded(work)) continue
                val contribution = work.score * seed.affinity
                scores[work.id] = (scores[work.id] ?: 0.0) + contribution
                titles.getOrPut(work.id) { work.title }
                if (contribution > 0) contributions.getOrPut(work.id, ::ArrayList) += seed to contribution
            }
        }
        val pool = scores.entries
            .filter { it.value > 0.0 }
            .map { (id, score) -> id to score / (1.0 + POPULARITY_DAMPING * ln(1.0 + readers(id).coerceAtLeast(0))) }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            .take(limit * DIVERSITY_POOL)
            .toMutableList()
        val picked = ArrayList<Pair<String, Double>>(minOf(limit, pool.size))
        while (pool.isNotEmpty() && picked.size < limit) {
            var bestIndex = 0
            var bestScore = Double.NEGATIVE_INFINITY
            pool.forEachIndexed { index, (id, score) ->
                val closest = picked.maxOfOrNull { overlap(it.first, id).coerceIn(0.0, 1.0) } ?: 0.0
                val discounted = score * (1.0 - DIVERSITY * closest)
                if (discounted > bestScore) {
                    bestScore = discounted
                    bestIndex = index
                }
            }
            picked += pool.removeAt(bestIndex).first to bestScore
        }
        return picked.map { (id, score) ->
            CommunityRecommendation(
                groupId = id,
                title = titles[id],
                score = score,
                because = contributions[id].orEmpty().sortedByDescending { it.second }.take(2).map { it.first },
            )
        }
    }

    /** How much a log-unit of readers damps a score: 1000 readers divide it by about 2. */
    const val POPULARITY_DAMPING = 0.15

    /** How much an exact copy of an earlier pick is discounted. */
    const val DIVERSITY = 0.5

    /** How many candidates past the limit the diversification may reach into. */
    private const val DIVERSITY_POOL = 3
}
