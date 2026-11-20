package org.skepsun.kototoro.community.nostr

import kotlin.math.roundToInt

/** NIP-56 report types a reader can choose from. */
enum class CommunityReportType(val wire: String) {
    SPAM("spam"),
    ILLEGAL("illegal"),
    NUDITY("nudity"),
    PROFANITY("profanity"),
    OTHER("other"),
    ;

    companion object {
        fun of(wire: String?): CommunityReportType = entries.firstOrNull { it.wire == wire } ?: OTHER
    }
}

data class CommunityRating(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    /** The rating's address within its author's events; a newer rating with the same one replaces it. */
    val address: String,
    val keys: List<String>,
    val medium: String,
    val stars: Int,
    val title: String?,
    val nsfw: Boolean,
)

data class CommunityComment(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    /** The work's keys, from the root scope (`I`) tags; replies repeat their thread's. */
    val keys: List<String>,
    /** The comment this one answers; null for a comment on the work itself. */
    val parentId: String?,
    val content: String,
    val title: String?,
    val chapter: String?,
    val spoiler: Boolean,
    val nsfw: Boolean,
    val language: String?,
)

data class CommunityDeletion(
    val pubkey: String,
    val createdAt: Long,
    val eventIds: Set<String>,
    val addresses: Set<String>,
)

data class CommunityReport(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val eventId: String,
    val reportedPubkey: String?,
    val type: CommunityReportType,
)

/**
 * The events Kototoro writes and reads (community plan §4), shared by the app and the indexer so both read exactly what
 * the other writes. Every event carries the NIP-32 label namespace [LABEL_NAMESPACE]; the indexer asks relays for that
 * label alone, and events from other clients without it are never counted.
 */
object CommunityEventSchema {

    /** Generic rating, nostr-protocol/nips#1914 (open as of 2026-11): addressable, `d` = `<type>:<id>`, rating 0..1. */
    const val KIND_RATING = 34259
    const val KIND_COMMENT = 1111
    const val KIND_DELETION = 5
    const val KIND_REPORT = 1984
    const val KIND_MUTE_LIST = 10000

    const val LABEL_NAMESPACE = "org.kototoro"
    const val LABEL_VALUE = "community/v1"
    const val MAX_COMMENT_LENGTH = 4000
    const val MIN_STARS = 1
    const val MAX_STARS = 10

    private const val NSFW = "nsfw"
    private const val SPOILER = "spoiler"
    private const val LANGUAGE_NAMESPACE = "ISO-639-1"

    /** What the indexer asks every relay for: everything Kototoro wrote since [since]. */
    fun indexFilter(since: Long?, until: Long? = null, limit: Int? = null): NostrFilter = NostrFilter(
        kinds = listOf(KIND_RATING, KIND_COMMENT, KIND_DELETION, KIND_REPORT),
        tags = mapOf("L" to listOf(LABEL_NAMESPACE)),
        since = since,
        until = until,
        limit = limit,
    )

    fun isKototoro(event: NostrEvent): Boolean = LABEL_NAMESPACE in event.tagValues("L")

    /** `[1, 10]` stars as #1914's normalized value: 8 stars is `"0.8"`, 10 is `"1.0"`. */
    fun ratingValue(stars: Int): String {
        require(stars in MIN_STARS..MAX_STARS) { "stars out of range: $stars" }
        return if (stars == MAX_STARS) "1.0" else "0.$stars"
    }

    /** Stars back from any value a client wrote in `[0, 1]`; null for anything else. */
    fun starsOf(value: String?): Int? {
        val number = value?.trim()?.toDoubleOrNull() ?: return null
        if (number.isNaN() || number < 0.0 || number > 1.0) return null
        return (number * MAX_STARS).roundToInt().coerceIn(MIN_STARS, MAX_STARS)
    }

    /** The rating's `d`: `<medium>:<first key>`, keys being strong first as core-domain's CommunityWorkKeys orders them. */
    fun ratingAddress(medium: String, keys: List<String>): String = "$medium:${keys.first()}"

    fun rating(
        keys: List<String>,
        medium: String,
        contentType: String?,
        stars: Int,
        title: String?,
        nsfw: Boolean,
        createdAt: Long,
    ): NostrUnsignedEvent {
        require(keys.isNotEmpty()) { "a rating needs at least one work key" }
        val tags = buildList {
            add(listOf("d", ratingAddress(medium, keys)))
            add(listOf("m", medium))
            add(listOf("rating", ratingValue(stars)))
            keys.forEach { add(listOf("i", it)) }
            contentType?.let { add(listOf("content_type", it)) }
            title?.takeIf { it.isNotBlank() }?.let { add(listOf("title", it.trim())) }
            if (nsfw) add(listOf("content-warning", NSFW))
            addAll(label())
        }
        return NostrUnsignedEvent(createdAt, KIND_RATING, tags, "")
    }

    /**
     * A NIP-22 comment on a work ([parent] null) or a reply. The root scope is the work's keys as `I` tags, so a reply
     * is found by the same query as the thread it belongs to.
     */
    fun comment(
        keys: List<String>,
        content: String,
        title: String?,
        createdAt: Long,
        parent: NostrEvent? = null,
        chapter: String? = null,
        spoiler: Boolean = false,
        nsfw: Boolean = false,
        language: String? = null,
    ): NostrUnsignedEvent {
        require(keys.isNotEmpty()) { "a comment needs at least one work key" }
        require(content.isNotBlank() && content.length <= MAX_COMMENT_LENGTH) { "comment length out of range" }
        val rootKind = rootKindOf(keys.first())
        val tags = buildList {
            keys.forEach { add(listOf("I", it)) }
            add(listOf("K", rootKind))
            if (parent == null) {
                keys.forEach { add(listOf("i", it)) }
                add(listOf("k", rootKind))
            } else {
                add(listOf("e", parent.id, "", parent.pubkey))
                add(listOf("k", parent.kind.toString()))
                add(listOf("p", parent.pubkey))
            }
            title?.takeIf { it.isNotBlank() }?.let { add(listOf("title", it.trim())) }
            chapter?.takeIf { it.isNotBlank() }?.let { add(listOf("chapter", it)) }
            if (spoiler) add(listOf("content-warning", SPOILER))
            if (nsfw) add(listOf("content-warning", NSFW))
            language?.takeIf { it.isNotBlank() }?.let {
                add(listOf("L", LANGUAGE_NAMESPACE))
                add(listOf("l", it, LANGUAGE_NAMESPACE))
            }
            addAll(label())
        }
        return NostrUnsignedEvent(createdAt, KIND_COMMENT, tags, content.trim())
    }

    /** NIP-09: asks relays to drop the reader's own [event]; the indexer honours it either way. */
    fun deletion(event: NostrEvent, createdAt: Long): NostrUnsignedEvent = NostrUnsignedEvent(
        createdAt,
        KIND_DELETION,
        listOf(listOf("e", event.id), listOf("k", event.kind.toString())) + label(),
        "",
    )

    /** Withdraws the reader's rating at [address] (see [ratingAddress]), including any later copy of it. */
    fun ratingWithdrawal(pubkey: String, address: String, createdAt: Long): NostrUnsignedEvent = NostrUnsignedEvent(
        createdAt,
        KIND_DELETION,
        listOf(listOf("a", "$KIND_RATING:$pubkey:$address"), listOf("k", KIND_RATING.toString())) + label(),
        "",
    )

    fun report(event: NostrEvent, type: CommunityReportType, createdAt: Long, note: String = ""): NostrUnsignedEvent =
        NostrUnsignedEvent(
            createdAt,
            KIND_REPORT,
            listOf(listOf("e", event.id, type.wire), listOf("p", event.pubkey, type.wire)) + label(),
            note,
        )

    fun parseRating(event: NostrEvent): CommunityRating? {
        if (event.kind != KIND_RATING || !isKototoro(event)) return null
        val address = event.tagValues("d").firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val keys = event.tagValues("i").filter { it.isNotBlank() }.distinct().ifEmpty { return null }
        return CommunityRating(
            id = event.id,
            pubkey = event.pubkey,
            createdAt = event.createdAt,
            address = address,
            keys = keys,
            medium = event.tagValues("m").firstOrNull() ?: address.substringBefore(':'),
            stars = starsOf(event.tagValues("rating").firstOrNull()) ?: return null,
            title = event.tagValues("title").firstOrNull(),
            nsfw = NSFW in event.tagValues("content-warning"),
        )
    }

    fun parseComment(event: NostrEvent): CommunityComment? {
        if (event.kind != KIND_COMMENT || !isKototoro(event)) return null
        if (event.content.isBlank() || event.content.length > MAX_COMMENT_LENGTH) return null
        val keys = event.tagValues("I").filter { it.isNotBlank() }.distinct().ifEmpty { return null }
        val warnings = event.tagValues("content-warning")
        return CommunityComment(
            id = event.id,
            pubkey = event.pubkey,
            createdAt = event.createdAt,
            keys = keys,
            parentId = event.tagValues("e").firstOrNull(),
            content = event.content,
            title = event.tagValues("title").firstOrNull(),
            chapter = event.tagValues("chapter").firstOrNull(),
            spoiler = SPOILER in warnings,
            nsfw = NSFW in warnings,
            language = event.tags.firstOrNull { it.size >= 3 && it[0] == "l" && it[2] == LANGUAGE_NAMESPACE }?.get(1),
        )
    }

    fun parseDeletion(event: NostrEvent): CommunityDeletion? {
        if (event.kind != KIND_DELETION) return null
        return CommunityDeletion(
            pubkey = event.pubkey,
            createdAt = event.createdAt,
            eventIds = event.tagValues("e").toSet(),
            addresses = event.tagValues("a").toSet(),
        )
    }

    fun parseReport(event: NostrEvent): CommunityReport? {
        if (event.kind != KIND_REPORT || !isKototoro(event)) return null
        val target = event.tags.firstOrNull { it.firstOrNull() == "e" } ?: return null
        return CommunityReport(
            id = event.id,
            pubkey = event.pubkey,
            createdAt = event.createdAt,
            eventId = target.getOrNull(1) ?: return null,
            reportedPubkey = event.tagValues("p").firstOrNull(),
            type = CommunityReportType.of(target.getOrNull(2)),
        )
    }

    /** NIP-73 names a URL root `web`; Kototoro's own title keys have no NIP-73 type, so they say what they are. */
    private fun rootKindOf(key: String): String = if (key.startsWith("https://")) "web" else "kototoro"

    private fun label(): List<List<String>> = listOf(listOf("L", LABEL_NAMESPACE), listOf("l", LABEL_VALUE, LABEL_NAMESPACE))
}
