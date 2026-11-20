package org.skepsun.kototoro.community.nostr

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * NIP-13 proof of work: a `nonce` tag varied until the event id starts with [difficulty] zero bits. It is what an event
 * costs to send without a server to rate-limit anyone; the community index decides the difficulty (plan §4).
 */
object NostrPow {

    fun leadingZeroBits(idHex: String): Int {
        var count = 0
        for (char in idHex) {
            val nibble = "0123456789abcdef".indexOf(char)
            if (nibble < 0) return count
            if (nibble == 0) {
                count += 4
            } else {
                return count + nibble.countLeadingZeroBits() - (Int.SIZE_BITS - 4)
            }
        }
        return count
    }

    /** NIP-13's own tag name; [CommunityEventSchema.POW_TAG] is used instead where relays refuse it. */
    const val NIP13_TAG = "nonce"

    /** The difficulty an event commits to in its [tagName] tag; 0 when it has none. */
    fun committedDifficulty(event: NostrEvent, tagName: String = NIP13_TAG): Int =
        event.tags.firstOrNull { it.firstOrNull() == tagName }?.getOrNull(2)?.toIntOrNull() ?: 0

    /** Whether the event has at least [difficulty] bits and committed to them, so a lucky low target cannot pass. */
    fun satisfies(event: NostrEvent, difficulty: Int, tagName: String = NIP13_TAG): Boolean =
        difficulty <= 0 || (committedDifficulty(event, tagName) >= difficulty && leadingZeroBits(event.id) >= difficulty)

    /**
     * Adds a [tagName] tag that gives [event] [difficulty] zero bits for [pubkeyHex]. Checks for cancellation every few
     * thousand hashes, so a reader who gives up stops the work at once.
     */
    suspend fun mine(
        event: NostrUnsignedEvent,
        pubkeyHex: String,
        difficulty: Int,
        tagName: String = NIP13_TAG,
    ): NostrUnsignedEvent {
        if (difficulty <= 0) return event
        val base = event.tags.filterNot { it.firstOrNull() == tagName }
        var nonce = 0L
        while (true) {
            if (nonce and 0xFFF == 0L) currentCoroutineContext().ensureActive()
            val tags = base + listOf(listOf(tagName, nonce.toString(), difficulty.toString()))
            val id = NostrEvent.computeId(pubkeyHex, event.createdAt, event.kind, tags, event.content)
            if (leadingZeroBits(id) >= difficulty) return event.copy(tags = tags)
            nonce++
        }
    }
}
