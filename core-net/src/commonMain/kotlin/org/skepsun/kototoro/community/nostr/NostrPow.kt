package org.skepsun.kototoro.community.nostr

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.kotlincrypto.hash.sha2.SHA256

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
        fun tagsFor(nonce: Long) = base + listOf(listOf(tagName, nonce.toString(), difficulty.toString()))
        // The nonce is the only part that changes, and it is the last tag: everything before it is hashed once and the
        // state copied, so each attempt hashes only the nonce and what follows it (comments ask for 2^20 attempts).
        val text = NostrEvent.serializeForId(pubkeyHex, event.createdAt, event.kind, tagsFor(0), event.content)
        val tail = "\",\"$difficulty\"]]," + NostrEvent.quoted(event.content) + "]"
        check(text.endsWith(tail)) { "unexpected serialization" }
        val prefix = SHA256().apply { update(text.substring(0, text.length - tail.length - 1).encodeToByteArray()) }
        val tailBytes = tail.encodeToByteArray()
        var nonce = 0L
        while (true) {
            if (nonce and 0xFFF == 0L) currentCoroutineContext().ensureActive()
            val digest = prefix.copy()
            digest.update(nonce.toString().encodeToByteArray())
            digest.update(tailBytes)
            if (leadingZeroBits(digest.digest()) >= difficulty) return event.copy(tags = tagsFor(nonce))
            nonce++
        }
    }

    private fun leadingZeroBits(hash: ByteArray): Int {
        var count = 0
        for (byte in hash) {
            val value = byte.toInt() and 0xFF
            if (value == 0) {
                count += 8
            } else {
                return count + value.countLeadingZeroBits() - (Int.SIZE_BITS - 8)
            }
        }
        return count
    }
}
