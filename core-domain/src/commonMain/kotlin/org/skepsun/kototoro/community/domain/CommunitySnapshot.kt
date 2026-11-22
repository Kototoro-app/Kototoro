package org.skepsun.kototoro.community.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The static index the community indexer publishes (community plan §6.3) and the app reads. Everything is derived from
 * public relay events, so a reader who cannot reach it still has comments straight from the relays; it adds the
 * grouping, averages, charts and similar works a phone cannot compute alone.
 *
 * Layout under the snapshot root:
 * - `v1/manifest.json`: [CommunitySnapshotManifest]
 * - `v1/keys/<shard>.json`: work key → group id, for the keys whose [CommunitySnapshotShards.of] is `<shard>`
 * - `v1/works/<shard>.json`: group id → [CommunityWorkSummary], sharded by group id
 * - `v1/charts/<window>-<medium>[-nsfw].json`: [CommunityChart]
 * - `v1/moderation.json`: [CommunityModeration]
 */
@Serializable
data class CommunitySnapshotManifest(
    val version: Int = VERSION,
    @SerialName("generated_at") val generatedAt: Long,
    val relays: List<String>,
    @SerialName("pow_rating") val powRating: Int,
    @SerialName("pow_comment") val powComment: Int,
    @SerialName("maintainer_pubkey") val maintainerPubkey: String? = null,
    @SerialName("min_chart_weight") val minChartWeight: Double,
    /** Path → content hash, so a client downloads only what changed since its last copy. */
    val files: Map<String, String>,
    val stats: CommunitySnapshotStats,
) {
    companion object {
        const val VERSION = 1
    }
}

@Serializable
data class CommunitySnapshotStats(
    val events: Int,
    val ratings: Int,
    val comments: Int,
    val raters: Int,
    val works: Int,
)

@Serializable
data class CommunityWorkSummary(
    val id: String,
    val medium: String,
    /** The title most of its raters and commenters gave it. */
    val title: String? = null,
    val rating: CommunityRatingSummary? = null,
    val comments: Int = 0,
    /** Comments readers should not be shown: reported by enough trusted readers, or blocked by the maintainer. */
    @SerialName("hidden_comments") val hiddenComments: List<String> = emptyList(),
    val similar: List<CommunitySimilarWork> = emptyList(),
    val nsfw: Boolean = false,
    /** Taxonomy tag ids most of its raters' sources gave it ([CommunityGenres]), most agreed first. */
    val tags: List<String> = emptyList(),
    /** Where its raters and commenters read it, most readers first ([CommunitySources]). */
    val sources: List<CommunityWorkSource> = emptyList(),
)

/** One place a work can be read: a source id, the entry there, the site's host and how many readers used it. */
@Serializable
data class CommunityWorkSource(
    val source: String,
    val url: String,
    val host: String? = null,
    val readers: Int,
)

@Serializable
data class CommunityRatingSummary(
    /** Bayesian average in stars, pulled toward the medium's mean while the weight is small. */
    val score: Double,
    /** Plain weighted mean of the stars given, for display next to [score]. */
    val mean: Double,
    val count: Int,
    /** Sum of rater weights; new accounts count for less. */
    val weight: Double,
    /** How many ratings gave 1..10 stars, index 0 being 1 star. */
    val distribution: List<Int>,
)

@Serializable
data class CommunitySimilarWork(
    val id: String,
    val title: String? = null,
    val score: Double,
    @SerialName("co_raters") val coRaters: Int,
    /**
     * Taxonomy tags the two works share. A work with [coRaters] 0 is similar by its tags alone: the index lists those
     * after the rating-based ones while too few readers rated both.
     */
    @SerialName("shared_tags") val sharedTags: Int = 0,
    /** Whether the similar work is adult, so readers who keep those out can skip it without fetching its shard. */
    val nsfw: Boolean = false,
)

@Serializable
data class CommunityChart(
    val window: String,
    val medium: String,
    val nsfw: Boolean,
    val entries: List<CommunityChartEntry>,
)

@Serializable
data class CommunityChartEntry(
    val id: String,
    val title: String? = null,
    /** The Bayesian score the chart is ordered by: pulled toward the medium's mean while few readers rated it. */
    val score: Double,
    val count: Int,
    /** The plain (weighted) mean of the stars given; what readers see. Absent from snapshots before 2026-11-21. */
    val mean: Double? = null,
) {
    /** What to show: the mean readers gave, the ranking score only for snapshots that do not have it. */
    val displayScore: Double get() = mean ?: score
}

@Serializable
data class CommunityModeration(
    @SerialName("blocked_pubkeys") val blockedPubkeys: List<String> = emptyList(),
    @SerialName("blocked_events") val blockedEvents: List<String> = emptyList(),
    @SerialName("blocked_words") val blockedWords: List<String> = emptyList(),
)

/** Which shard file a key or group id lives in: the low byte of its 32-bit FNV-1a hash, as two hex digits. */
object CommunitySnapshotShards {

    fun of(value: String): String {
        var hash = 0x811c9dc5.toInt()
        for (byte in value.encodeToByteArray()) {
            hash = hash xor (byte.toInt() and 0xFF)
            hash *= 0x01000193
        }
        val low = hash and 0xFF
        return "0123456789abcdef"[low ushr 4].toString() + "0123456789abcdef"[low and 0x0F]
    }

    fun keysPath(shard: String) = "v1/keys/$shard.json"
    fun worksPath(shard: String) = "v1/works/$shard.json"
    fun chartPath(window: String, medium: String, nsfw: Boolean) = "v1/charts/$window-$medium${if (nsfw) "-nsfw" else ""}.json"

    const val MANIFEST_PATH = "v1/manifest.json"
    const val MODERATION_PATH = "v1/moderation.json"
}
