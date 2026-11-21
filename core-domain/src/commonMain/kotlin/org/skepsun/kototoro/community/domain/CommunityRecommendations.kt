package org.skepsun.kototoro.community.domain

import kotlinx.coroutines.withTimeoutOrNull
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
        val ranked = rank(
            seeds = seeds,
            similar = { summaries[it]?.similar.orEmpty() },
            known = groups.values.toSet(),
            excluded = { work -> work.nsfw || work.title?.let { title ->
                CommunityMedium.entries.any { CommunityWorkKeys.titleKey(title, it, fold) in knownTitles }
            } == true },
            limit = limit,
        )
        val targets = works(ranked.map { it.groupId })
        return ranked.mapNotNull { pick ->
            val summary = targets[pick.groupId]?.takeUnless { it.nsfw } ?: return@mapNotNull null
            val medium = CommunityMedium.entries.firstOrNull { it.wire == summary.medium && it != CommunityMedium.OTHER }
                ?: return@mapNotNull null
            val title = (summary.title ?: pick.title)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CommunitySuggestionTarget(pick.groupId, title, medium, pick.because.firstOrNull()?.title)
        }
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
     */
    fun rank(
        seeds: List<CommunitySeed>,
        similar: (groupId: String) -> List<CommunitySimilarWork>,
        known: Set<String>,
        excluded: (CommunitySimilarWork) -> Boolean = { false },
        limit: Int = 30,
    ): List<CommunityRecommendation> {
        val strongest = seeds.groupBy { it.groupId }.mapValues { (_, list) -> list.maxBy { kotlin.math.abs(it.affinity) } }
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
        return scores.entries
            .filter { it.value > 0.0 }
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { (id, score) ->
                CommunityRecommendation(
                    groupId = id,
                    title = titles[id],
                    score = score,
                    because = contributions[id].orEmpty().sortedByDescending { it.second }.take(2).map { it.first },
                )
            }
    }
}
