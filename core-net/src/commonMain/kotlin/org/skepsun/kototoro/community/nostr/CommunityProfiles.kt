package org.skepsun.kototoro.community.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object CommunityProfiles {
    const val KIND = 0
    const val MAX_NAME_LENGTH = 32

    fun latest(events: List<NostrEvent>): Map<String, NostrEvent> = events.asSequence()
        .filter { it.kind == KIND }
        .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id })
        .distinctBy { it.pubkey }
        .associateBy { it.pubkey }

    fun nickname(event: NostrEvent): String? {
        val metadata = metadata(event)
        return listOf("display_name", "name").firstNotNullOfOrNull { key ->
            (metadata[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NAME_LENGTH)
        }
    }

    fun update(nickname: String, now: Long, previous: NostrEvent?): NostrUnsignedEvent {
        val name = nickname.trim()
        require(name.isNotEmpty() && name.length <= MAX_NAME_LENGTH)
        val metadata = previous?.let(::metadata).orEmpty() + mapOf(
            "name" to JsonPrimitive(name), "display_name" to JsonPrimitive(name),
        )
        return NostrUnsignedEvent(
            createdAt = maxOf(now, (previous?.createdAt ?: 0) + 1),
            kind = KIND,
            tags = emptyList(),
            content = JsonObject(metadata).toString(),
        )
    }

    suspend fun query(pool: NostrRelayPool, authors: List<String>): List<NostrEvent> {
        if (authors.isEmpty()) return emptyList()
        val filters = authors.distinct().chunked(50).map { NostrFilter(kinds = listOf(KIND), authors = it) }
        return pool.query(filters, timeoutMillis = 3_000L).events
    }

    private fun metadata(event: NostrEvent): JsonObject =
        (runCatching { Json.parseToJsonElement(event.content) }.getOrNull() as? JsonObject) ?: JsonObject(emptyMap())
}
