package org.skepsun.kototoro.community.nostr

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge

data class NostrPoolPublishResult(val results: List<NostrPublishResult>, val required: Int) {
    val accepted: Int get() = results.count { it.accepted }

    /** Stored by enough relays to be found again; otherwise the outbox keeps it and tries later (plan §5). */
    val isPublished: Boolean get() = accepted >= required
}

data class NostrPoolQueryResult(val events: List<NostrEvent>, val relays: List<NostrQueryResult>)

/**
 * The relays the community writes to and reads from (plan §5). Writes go to all of them at once; reads merge what every
 * relay returns, so one that is down, slow or missing old events costs nothing as long as another has them.
 */
class NostrRelayPool(
    private val client: NostrRelayClient,
    val relays: List<String>,
    /** How many relays must store an event before it counts as published; capped at the number of relays. */
    private val minAccepted: Int = 2,
) {

    suspend fun publish(event: NostrEvent, timeoutMillis: Long = NostrRelayClient.DEFAULT_TIMEOUT_MILLIS): NostrPoolPublishResult =
        coroutineScope {
            val results = relays.map { relay -> async { client.publish(relay, event, timeoutMillis) } }.awaitAll()
            NostrPoolPublishResult(results, required = minAccepted.coerceIn(1, relays.size.coerceAtLeast(1)))
        }

    /** Every distinct event any relay has, newest first. */
    suspend fun query(filters: List<NostrFilter>, timeoutMillis: Long = NostrRelayClient.DEFAULT_TIMEOUT_MILLIS): NostrPoolQueryResult =
        coroutineScope {
            val results = relays.map { relay -> async { client.query(relay, filters, timeoutMillis) } }.awaitAll()
            val events = results.flatMap { it.events }
                .distinctBy { it.id }
                .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id })
            NostrPoolQueryResult(events, results)
        }

    /** Live events from [from] relays merged, each event once; a relay that drops out leaves the others running. */
    fun subscribe(filters: List<NostrFilter>, from: List<String> = relays): Flow<NostrEvent> = flow {
        // merge() hands events to this one collector in turn, so the set needs no lock.
        val seen = HashSet<String>()
        from.map { relay -> client.subscribe(relay, filters).catch { } }
            .merge()
            .collect { event -> if (seen.add(event.id)) emit(event) }
    }
}
