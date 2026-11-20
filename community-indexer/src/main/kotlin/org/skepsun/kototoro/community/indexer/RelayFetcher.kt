package org.skepsun.kototoro.community.indexer

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.skepsun.kototoro.community.domain.CommunityModeration
import org.skepsun.kototoro.community.nostr.CommunityEventSchema
import org.skepsun.kototoro.community.nostr.NostrEvent
import org.skepsun.kototoro.community.nostr.NostrFilter
import org.skepsun.kototoro.community.nostr.NostrRelayClient

data class RelayFetchReport(val relay: String, val events: Int, val pages: Int, val error: String?)

/** Pulls Kototoro's events from every relay, paging back with `until` because relays cap how many one query returns. */
class RelayFetcher(
    private val client: NostrRelayClient,
    private val pageSize: Int = 500,
    private val maxPages: Int = 200,
    private val timeoutMillis: Long = 20_000,
) {

    suspend fun fetch(relays: List<String>, since: Long?): Pair<List<NostrEvent>, List<RelayFetchReport>> = coroutineScope {
        val results = relays.map { relay -> async { fetchRelay(relay, since) } }.awaitAll()
        results.flatMap { it.first }.distinctBy { it.id } to results.map { it.second }
    }

    private suspend fun fetchRelay(relay: String, since: Long?): Pair<List<NostrEvent>, RelayFetchReport> {
        val events = LinkedHashMap<String, NostrEvent>()
        var until: Long? = null
        var pages = 0
        while (pages < maxPages) {
            val result = client.query(relay, listOf(CommunityEventSchema.indexFilter(since, until, pageSize)), timeoutMillis)
            pages++
            if (result.error != null) return events.values.toList() to RelayFetchReport(relay, events.size, pages, result.error)
            val fresh = result.events.filter { it.id !in events }
            fresh.forEach { events[it.id] = it }
            // Inclusive `until` repeats the oldest second's events, so an empty page of new ones is the end.
            if (fresh.isEmpty() || result.events.isEmpty()) break
            until = result.events.minOf { it.createdAt }
        }
        return events.values.toList() to RelayFetchReport(relay, events.size, pages, null)
    }

    /** The maintainer's NIP-51 mute list (kind 10000), newest copy any relay has; empty when there is none. */
    suspend fun moderation(relays: List<String>, maintainer: String?): CommunityModeration {
        if (maintainer == null) return CommunityModeration()
        val filter = NostrFilter(authors = listOf(maintainer), kinds = listOf(CommunityEventSchema.KIND_MUTE_LIST), limit = 1)
        val latest = coroutineScope {
            relays.map { relay -> async { client.query(relay, listOf(filter), timeoutMillis).events } }.awaitAll()
        }.flatten().maxByOrNull { it.createdAt } ?: return CommunityModeration()
        return CommunityModeration(
            blockedPubkeys = latest.tagValues("p").distinct(),
            blockedEvents = latest.tagValues("e").distinct(),
            blockedWords = latest.tagValues("word").distinct(),
        )
    }
}
