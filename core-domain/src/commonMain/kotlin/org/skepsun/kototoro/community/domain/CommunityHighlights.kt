package org.skepsun.kototoro.community.domain

/**
 * What the suggestions page lists under "Community" (plan §7.3). A chart only holds works whose rater weight reached
 * [CommunitySnapshotManifest.minChartWeight], so while the community is small every chart is empty; until the index
 * outgrows [MAX_FALLBACK_SHARDS] works files, it is read whole and every rated work is listed instead.
 */
object CommunityHighlights {

    const val WINDOW_ALL = "all"

    /** Past this many works files the index is too big to read whole, and the charts have entries of their own. */
    const val MAX_FALLBACK_SHARDS = 16

    private const val WORKS_PREFIX = "v1/works/"

    /** The chart entries of every medium, best first. */
    fun merge(charts: List<CommunityChart?>, limit: Int): List<CommunityChartEntry> = charts.asSequence()
        .flatMap { it?.entries.orEmpty() }
        .filter { !it.title.isNullOrBlank() }
        .distinctBy { it.id }
        .sortedWith(BEST_FIRST)
        .take(limit)
        .toList()

    /** The works files to read for [rated]; empty once the index is too big for that. */
    fun worksShardPaths(manifest: CommunitySnapshotManifest): List<String> =
        manifest.files.keys.filter { it.startsWith(WORKS_PREFIX) }.sorted().takeIf { it.size <= MAX_FALLBACK_SHARDS }.orEmpty()

    /** Every rated work in [works], best first, as chart entries. */
    fun rated(works: Collection<CommunityWorkSummary>, allowNsfw: Boolean, limit: Int): List<CommunityChartEntry> =
        works.asSequence()
            .filter { (allowNsfw || !it.nsfw) && !it.title.isNullOrBlank() }
            .mapNotNull { work ->
                work.rating?.takeIf { it.count > 0 }?.let { CommunityChartEntry(work.id, work.title, it.score, it.count, it.mean) }
            }
            .sortedWith(BEST_FIRST)
            .take(limit)
            .toList()

    /** [CommunityRatingSummary.distribution] folded into five stars: index 0 is one star (1–2 of 10). */
    fun starBuckets(distribution: List<Int>): List<Int> =
        List(5) { star -> distribution.getOrElse(star * 2) { 0 } + distribution.getOrElse(star * 2 + 1) { 0 } }

    private val BEST_FIRST = compareByDescending<CommunityChartEntry> { it.score }
        .thenByDescending { it.count }
        .thenBy { it.id }
}
