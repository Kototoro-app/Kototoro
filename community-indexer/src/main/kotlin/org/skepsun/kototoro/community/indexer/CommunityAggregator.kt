package org.skepsun.kototoro.community.indexer

import org.skepsun.kototoro.community.domain.CommunityChart
import org.skepsun.kototoro.community.domain.CommunityChartEntry
import org.skepsun.kototoro.community.domain.CommunityModeration
import org.skepsun.kototoro.community.domain.CommunityRatingSummary
import org.skepsun.kototoro.community.domain.CommunitySimilarWork
import org.skepsun.kototoro.community.domain.CommunitySnapshotStats
import org.skepsun.kototoro.community.domain.CommunityWorkGroups
import org.skepsun.kototoro.community.domain.CommunityWorkKeys
import org.skepsun.kototoro.community.domain.CommunityWorkSummary
import org.skepsun.kototoro.community.nostr.CommunityComment
import org.skepsun.kototoro.community.nostr.CommunityEventSchema
import org.skepsun.kototoro.community.nostr.CommunityRating
import org.skepsun.kototoro.community.nostr.NostrEvent
import org.skepsun.kototoro.community.nostr.NostrPow
import kotlin.math.sqrt

/** An event as the archive keeps it: [seen] is when the indexer first had it, which its author cannot backdate. */
data class ArchivedEvent(val event: NostrEvent, val seen: Long)

data class IndexerConfig(
    val now: Long,
    val relays: List<String>,
    val powRating: Int = 16,
    val powComment: Int = 20,
    val maintainerPubkey: String? = null,
    /** How many ratings' worth of the medium's mean a work's score starts from (plan §6.2). */
    val bayesPrior: Double = 10.0,
    val minChartWeight: Double = 5.0,
    val chartSize: Int = 100,
    val similarSize: Int = 20,
    val minCoRaters: Int = 3,
    val reportsToHide: Int = 3,
    /** More ratings than this from one account inside a day of arrivals voids that account's ratings in the burst. */
    val burstLimit: Int = 200,
)

data class IndexOutput(
    val keys: Map<String, String>,
    val works: Map<String, CommunityWorkSummary>,
    val charts: List<CommunityChart>,
    val moderation: CommunityModeration,
    val stats: CommunitySnapshotStats,
    val conflicts: Int,
    /** Reported events with their distinct reporters, for the maintainer to look at. */
    val reports: Map<String, Int>,
)

/**
 * Turns the archive into the snapshot (plan §6.2). Deterministic: the same archive, moderation list and config give
 * the same output, so a rerun or a rebuild after a rule change only differs where the inputs do.
 */
object CommunityAggregator {

    const val DAY = 24L * 60 * 60
    val WINDOWS = listOf("week" to 7 * DAY, "month" to 30 * DAY, "all" to null)
    val MEDIA = listOf("comic", "novel", "video", "other")

    fun aggregate(archive: List<ArchivedEvent>, moderation: CommunityModeration, config: IndexerConfig): IndexOutput {
        val byId = archive.associateBy { it.event.id }
        val firstSeen = HashMap<String, Long>()
        archive.forEach { firstSeen.merge(it.event.pubkey, it.seen, ::minOf) }

        // An author can delete their own events, by id or, for ratings, by address up to the deletion's time.
        val deletedIds = HashSet<String>()
        val withdrawnUntil = HashMap<String, Long>()
        archive.mapNotNull { CommunityEventSchema.parseDeletion(it.event) }.forEach { deletion ->
            deletion.eventIds.filterTo(deletedIds) { byId[it]?.event?.pubkey == deletion.pubkey }
            deletion.addresses
                .filter { it.split(':', limit = 3).getOrNull(1) == deletion.pubkey }
                .forEach { withdrawnUntil.merge(it, deletion.createdAt, ::maxOf) }
        }
        val blockedPubkeys = moderation.blockedPubkeys.toSet()
        val blockedEvents = moderation.blockedEvents.toSet()
        fun gone(event: NostrEvent) = event.id in deletedIds || event.pubkey in blockedPubkeys || event.id in blockedEvents
        val seenOf = archive.associate { it.event.id to it.seen }

        val ratings = archive.asSequence()
            .map { it.event }
            .filter { it.kind == CommunityEventSchema.KIND_RATING && NostrPow.satisfies(it, config.powRating, CommunityEventSchema.POW_TAG) && !gone(it) }
            .mapNotNull(CommunityEventSchema::parseRating)
            .filter { rating ->
                val withdrawn = withdrawnUntil["${CommunityEventSchema.KIND_RATING}:${rating.pubkey}:${rating.address}"]
                withdrawn == null || rating.createdAt > withdrawn
            }
            .groupBy { it.pubkey to it.address }
            .values.map { versions -> versions.maxWith(compareBy<CommunityRating> { it.createdAt }.thenBy { it.id }) }

        val allComments = archive.asSequence()
            .map { it.event }
            .filter { it.kind == CommunityEventSchema.KIND_COMMENT && NostrPow.satisfies(it, config.powComment, CommunityEventSchema.POW_TAG) && it.id !in deletedIds }
            .mapNotNull { event -> CommunityEventSchema.parseComment(event)?.let { it to event } }
            .toList()

        val groups = CommunityWorkGroups.build(ratings.map { it.keys } + allComments.map { it.first.keys })
        fun groupOf(keys: List<String>) = groups.groupOf(keys.first())!!

        fun weight(pubkey: String): Double {
            val age = config.now - (firstSeen[pubkey] ?: config.now)
            return when {
                age < 7 * DAY -> 0.3
                age < 30 * DAY -> 0.7
                else -> 1.0
            }
        }

        // One rating per reader per work, the newest; then nothing from a reader's bursts.
        val perWork = ratings
            .groupBy { it.pubkey to groupOf(it.keys) }
            .values.map { versions -> versions.maxWith(compareBy<CommunityRating> { it.createdAt }.thenBy { it.id }) }
        val voided = burstVoided(perWork, seenOf, config)
        val counted = perWork.filterNot { it.id in voided }

        // Reports count once per trusted reader; hidden comments are left out of counts and listed for the app to hide.
        val reporters = HashMap<String, MutableSet<String>>()
        archive.mapNotNull { CommunityEventSchema.parseReport(it.event) }
            .filter { weight(it.pubkey) >= 1.0 && it.pubkey !in blockedPubkeys }
            .forEach { reporters.getOrPut(it.eventId, ::HashSet) += it.pubkey }
        val words = moderation.blockedWords.map { it.lowercase() }.filter { it.isNotBlank() }
        val hidden = allComments.filter { (comment, event) ->
            gone(event) || (reporters[comment.id]?.size ?: 0) >= config.reportsToHide ||
                words.any { comment.content.lowercase().contains(it) }
        }.mapTo(HashSet()) { it.first.id }
        val comments = allComments.map { it.first }

        val ratingsByGroup = counted.groupBy { groupOf(it.keys) }
        val commentsByGroup = comments.groupBy { groupOf(it.keys) }
        val groupIds = (ratingsByGroup.keys + commentsByGroup.keys).sorted()
        val mediumOf = groupIds.associateWith { id ->
            majority(ratingsByGroup[id].orEmpty().map { it.medium } + commentsByGroup[id].orEmpty().map { mediumOfKey(it.keys.first()) })
                ?: "other"
        }
        val titleOf = groupIds.associateWith { id ->
            majority(ratingsByGroup[id].orEmpty().mapNotNull { it.title } + commentsByGroup[id].orEmpty().mapNotNull { it.title })
        }
        val nsfwOf = groupIds.associateWith { id ->
            val flags = ratingsByGroup[id].orEmpty().map { it.nsfw } + commentsByGroup[id].orEmpty().map { it.nsfw }
            flags.count { it } * 2 > flags.size
        }
        val meanByMedium = counted.groupBy { mediumOf.getValue(groupOf(it.keys)) }.mapValues { (_, list) ->
            list.sumOf { weight(it.pubkey) * it.stars } / list.sumOf { weight(it.pubkey) }
        }
        fun summary(list: List<CommunityRating>, medium: String): CommunityRatingSummary? {
            if (list.isEmpty()) return null
            val totalWeight = list.sumOf { weight(it.pubkey) }
            val weighted = list.sumOf { weight(it.pubkey) * it.stars }
            val prior = meanByMedium[medium] ?: DEFAULT_MEAN
            return CommunityRatingSummary(
                score = round2((weighted + config.bayesPrior * prior) / (totalWeight + config.bayesPrior)),
                mean = round2(weighted / totalWeight),
                count = list.size,
                weight = round2(totalWeight),
                distribution = (1..10).map { stars -> list.count { it.stars == stars } },
            )
        }

        val similar = similarWorks(counted, ::groupOf, config)
        val works = groupIds.associateWith { id ->
            val groupComments = commentsByGroup[id].orEmpty()
            CommunityWorkSummary(
                id = id,
                medium = mediumOf.getValue(id),
                title = titleOf[id],
                rating = summary(ratingsByGroup[id].orEmpty(), mediumOf.getValue(id)),
                comments = groupComments.count { it.id !in hidden },
                hiddenComments = groupComments.map { it.id }.filter { it in hidden }.sorted(),
                similar = similar[id].orEmpty().map { (other, score, co) -> CommunitySimilarWork(other, titleOf[other], round2(score), co) },
                nsfw = nsfwOf.getValue(id),
            )
        }

        val charts = WINDOWS.flatMap { (window, length) ->
            val inWindow = if (length == null) counted else counted.filter { it.createdAt >= config.now - length }
            val ranked = inWindow.groupBy { groupOf(it.keys) }.mapNotNull { (id, list) ->
                val medium = mediumOf.getValue(id)
                summary(list, medium)?.takeIf { it.weight >= config.minChartWeight }?.let { Triple(id, medium, it) }
            }
            MEDIA.flatMap { medium ->
                listOf(false, true).map { nsfw ->
                    CommunityChart(
                        window = window,
                        medium = medium,
                        nsfw = nsfw,
                        entries = ranked
                            .filter { (id, m, _) -> m == medium && nsfwOf.getValue(id) == nsfw }
                            .sortedWith(compareByDescending<Triple<String, String, CommunityRatingSummary>> { it.third.score }
                                .thenByDescending { it.third.count }.thenBy { it.first })
                            .take(config.chartSize)
                            .map { (id, _, summary) -> CommunityChartEntry(id, titleOf[id], summary.score, summary.count) },
                    )
                }
            }
        }

        val keys = groupIds.toSet().let { kept -> groups.groups.filterKeys { it in kept } }
            .flatMap { (id, members) -> members.map { it to id } }.toMap()
        return IndexOutput(
            keys = keys,
            works = works,
            charts = charts,
            moderation = CommunityModeration(
                blockedPubkeys = moderation.blockedPubkeys.sorted(),
                blockedEvents = (moderation.blockedEvents + hidden).distinct().sorted(),
                blockedWords = moderation.blockedWords.sorted(),
            ),
            stats = CommunitySnapshotStats(
                events = archive.size,
                ratings = counted.size,
                comments = comments.count { it.id !in hidden },
                raters = counted.mapTo(HashSet()) { it.pubkey }.size,
                works = groupIds.size,
            ),
            conflicts = groups.conflicts,
            reports = reporters.mapValues { it.value.size }.toSortedMap(),
        )
    }

    /** Ratings an account made more than [IndexerConfig.burstLimit] of within any day of arrivals. */
    private fun burstVoided(ratings: List<CommunityRating>, seenOf: Map<String, Long>, config: IndexerConfig): Set<String> {
        val voided = HashSet<String>()
        for ((_, list) in ratings.groupBy { it.pubkey }) {
            if (list.size <= config.burstLimit) continue
            val sorted = list.sortedBy { seenOf[it.id] ?: 0L }
            var start = 0
            for (end in sorted.indices) {
                while ((seenOf[sorted[end].id] ?: 0L) - (seenOf[sorted[start].id] ?: 0L) >= DAY) start++
                if (end - start + 1 > config.burstLimit) {
                    for (i in start..end) voided += sorted[i].id
                }
            }
        }
        return voided
    }

    /**
     * Item-item cosine over ratings centered on each reader's own mean, from readers with at least three ratings; pairs
     * need [IndexerConfig.minCoRaters] readers in common, and the score is shrunk by `co / (co + 5)` so a pair few
     * readers share ranks below one many agree on.
     */
    private fun similarWorks(
        ratings: List<CommunityRating>,
        groupOf: (List<String>) -> String,
        config: IndexerConfig,
    ): Map<String, List<Triple<String, Double, Int>>> {
        class Accumulator(var dot: Double = 0.0, var left: Double = 0.0, var right: Double = 0.0, var co: Int = 0)
        val pairs = HashMap<Pair<String, String>, Accumulator>()
        for ((_, list) in ratings.groupBy { it.pubkey }) {
            if (list.size < 3) continue
            val mean = list.map { it.stars }.average()
            val centered = list.map { groupOf(it.keys) to it.stars - mean }.sortedBy { it.first }
            for (i in centered.indices) {
                for (j in i + 1 until centered.size) {
                    val (a, x) = centered[i]
                    val (b, y) = centered[j]
                    val pair = pairs.getOrPut(a to b) { Accumulator() }
                    pair.dot += x * y
                    pair.left += x * x
                    pair.right += y * y
                    pair.co++
                }
            }
        }
        val result = HashMap<String, MutableList<Triple<String, Double, Int>>>()
        for ((key, pair) in pairs) {
            if (pair.co < config.minCoRaters || pair.left == 0.0 || pair.right == 0.0) continue
            val score = pair.dot / sqrt(pair.left * pair.right) * pair.co / (pair.co + 5.0)
            if (score <= 0.0) continue
            result.getOrPut(key.first, ::ArrayList) += Triple(key.second, score, pair.co)
            result.getOrPut(key.second, ::ArrayList) += Triple(key.first, score, pair.co)
        }
        return result.mapValues { (_, list) ->
            list.sortedWith(compareByDescending<Triple<String, Double, Int>> { it.second }.thenBy { it.first }).take(config.similarSize)
        }
    }

    /** A comment's medium when no rating says it: the title key's own, or what the site's URL path is for. */
    fun mediumOfKey(key: String): String = when {
        key.startsWith(CommunityWorkKeys.TITLE_PREFIX) -> key.removePrefix(CommunityWorkKeys.TITLE_PREFIX).substringBefore(':')
        "/anime/" in key || "/animes/" in key -> "video"
        "/ranobe/" in key -> "novel"
        else -> "comic"
    }

    private fun majority(values: List<String>): String? =
        values.groupingBy { it }.eachCount().entries.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })?.key

    private fun round2(value: Double) = kotlin.math.round(value * 100) / 100

    private const val DEFAULT_MEAN = 7.0
}
