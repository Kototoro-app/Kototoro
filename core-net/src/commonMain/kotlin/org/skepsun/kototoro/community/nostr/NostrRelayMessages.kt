package org.skepsun.kototoro.community.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A NIP-01 subscription filter. [tags] is keyed by the single tag letter, without the `#`. */
data class NostrFilter(
    val ids: List<String>? = null,
    val authors: List<String>? = null,
    val kinds: List<Int>? = null,
    val tags: Map<String, List<String>> = emptyMap(),
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
) {

    fun toJson(): JsonObject = buildJsonObject {
        ids?.let { values -> putJsonArray("ids") { values.forEach { add(JsonPrimitive(it)) } } }
        authors?.let { values -> putJsonArray("authors") { values.forEach { add(JsonPrimitive(it)) } } }
        kinds?.let { values -> putJsonArray("kinds") { values.forEach { add(JsonPrimitive(it)) } } }
        for ((letter, values) in tags.entries.sortedBy { it.key }) {
            putJsonArray("#$letter") { values.forEach { add(JsonPrimitive(it)) } }
        }
        since?.let { put("since", it) }
        until?.let { put("until", it) }
        limit?.let { put("limit", it) }
    }

    /** Whether [event] passes this filter, the way a relay decides; used to drop what a relay sends unasked. */
    fun matches(event: NostrEvent): Boolean {
        if (ids != null && event.id !in ids) return false
        if (authors != null && event.pubkey !in authors) return false
        if (kinds != null && event.kind !in kinds) return false
        if (since != null && event.createdAt < since) return false
        if (until != null && event.createdAt > until) return false
        return tags.all { (letter, values) -> event.tagValues(letter).any { it in values } }
    }
}

/** What a client sends to a relay. */
object NostrClientMessage {

    fun event(event: NostrEvent): String = buildJsonArray {
        add(JsonPrimitive("EVENT"))
        add(event.toJson())
    }.toString()

    fun request(subscriptionId: String, filters: List<NostrFilter>): String = buildJsonArray {
        add(JsonPrimitive("REQ"))
        add(JsonPrimitive(subscriptionId))
        filters.forEach { add(it.toJson()) }
    }.toString()

    fun close(subscriptionId: String): String = buildJsonArray {
        add(JsonPrimitive("CLOSE"))
        add(JsonPrimitive(subscriptionId))
    }.toString()
}

/** What a relay sends back. Anything a client does not understand parses to null and is ignored. */
sealed interface NostrRelayMessage {

    data class Event(val subscriptionId: String, val event: NostrEvent) : NostrRelayMessage

    data class EndOfStoredEvents(val subscriptionId: String) : NostrRelayMessage

    data class Ok(val eventId: String, val accepted: Boolean, val message: String) : NostrRelayMessage

    data class Closed(val subscriptionId: String, val message: String) : NostrRelayMessage

    data class Notice(val message: String) : NostrRelayMessage

    companion object {

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): NostrRelayMessage? {
            val array = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return null
            fun text(index: Int) = (array.getOrNull(index) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            return when (text(0)) {
                "EVENT" -> {
                    val event = (array.getOrNull(2) as? JsonObject)?.let(NostrEvent::fromJson) ?: return null
                    Event(text(1) ?: return null, event)
                }
                "EOSE" -> EndOfStoredEvents(text(1) ?: return null)
                "OK" -> Ok(
                    eventId = text(1) ?: return null,
                    accepted = (array.getOrNull(2) as? JsonPrimitive)?.booleanOrNull ?: return null,
                    message = text(3).orEmpty(),
                )
                "CLOSED" -> Closed(text(1) ?: return null, text(2).orEmpty())
                "NOTICE" -> Notice(text(1).orEmpty())
                else -> null
            }
        }
    }
}
