package org.skepsun.kototoro.community.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.kotlincrypto.hash.sha2.SHA256

/** An event as NIP-01 defines it. [id], [pubkey] and [sig] are lowercase hex; [createdAt] is in seconds. */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {

    /** The first value of each tag named [name], e.g. every `i` an event carries. */
    fun tagValues(name: String): List<String> = tags.mapNotNull { tag -> tag.getOrNull(1)?.takeIf { tag.firstOrNull() == name } }

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("pubkey", pubkey)
        put("created_at", createdAt)
        put("kind", kind)
        put("tags", JsonArray(tags.map { tag -> JsonArray(tag.map(::JsonPrimitive)) }))
        put("content", content)
        put("sig", sig)
    }

    /** Whether [id] is this event's own hash. Says nothing about [sig]; see [NostrKeys.verify]. */
    fun hasValidId(): Boolean = id == computeId(pubkey, createdAt, kind, tags, content)

    companion object {

        /** NIP-01: the sha256 of `[0,pubkey,created_at,kind,tags,content]` written as [serializeForId] does. */
        fun computeId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String =
            SHA256().digest(serializeForId(pubkey, createdAt, kind, tags, content).encodeToByteArray()).toHex()

        /**
         * The exact text NIP-01 hashes: no whitespace, and strings escaped only for line feed, double quote, backslash,
         * carriage return, tab, backspace and form feed. Every other character, control characters included, is
         * written as is; a general JSON writer escapes those as `\u00XX` and would give another id.
         */
        fun serializeForId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String =
            buildString {
                append("[0,")
                appendQuoted(pubkey)
                append(',').append(createdAt).append(',').append(kind).append(",[")
                tags.forEachIndexed { i, tag ->
                    if (i > 0) append(',')
                    append('[')
                    tag.forEachIndexed { j, value ->
                        if (j > 0) append(',')
                        appendQuoted(value)
                    }
                    append(']')
                }
                append("],")
                appendQuoted(content)
                append(']')
            }

        /** Reads an event from a relay message; null when a field is missing or the wrong type. */
        fun fromJson(json: JsonObject): NostrEvent? {
            fun text(key: String) = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val tags = (json["tags"] as? JsonArray)?.map { tag ->
                (tag as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull ?: return null }
                    ?: return null
            } ?: return null
            return NostrEvent(
                id = text("id") ?: return null,
                pubkey = text("pubkey") ?: return null,
                createdAt = (json["created_at"] as? JsonPrimitive)?.longOrNull ?: return null,
                kind = (json["kind"] as? JsonPrimitive)?.longOrNull?.toInt() ?: return null,
                tags = tags,
                content = text("content") ?: return null,
                sig = text("sig") ?: return null,
            )
        }

        private fun StringBuilder.appendQuoted(value: String) {
            append('"')
            for (char in value) {
                when (char) {
                    '\n' -> append("\\n")
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    else -> append(char)
                }
            }
            append('"')
        }
    }
}

/** An event before it is signed; [NostrKeys.sign] fills in pubkey, id and sig. */
data class NostrUnsignedEvent(
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
)

internal fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        val value = byte.toInt() and 0xFF
        append(HEX[value ushr 4]).append(HEX[value and 0x0F])
    }
}

internal fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    return ByteArray(length / 2) { i ->
        val high = HEX.indexOf(this[i * 2])
        val low = HEX.indexOf(this[i * 2 + 1])
        if (high < 0 || low < 0) return null
        ((high shl 4) or low).toByte()
    }
}

private const val HEX = "0123456789abcdef"
