package org.skepsun.kototoro.community.domain

/** A comment on the work and every reply under it, however deep, in the order they were written. */
data class CommunityThread<T>(val root: T, val replies: List<T>)

/** What a reader should not see: the index's moderation, comments its readers reported away, and their own blocks. */
data class CommunityCommentFilter(
    val blockedPubkeys: Set<String> = emptySet(),
    val blockedEvents: Set<String> = emptySet(),
    val blockedWords: List<String> = emptyList(),
) {
    private val words = blockedWords.map { it.lowercase() }.filter { it.isNotBlank() }

    fun allows(id: String, pubkey: String, content: String): Boolean =
        id !in blockedEvents && pubkey !in blockedPubkeys && words.none { content.lowercase().contains(it) }

    operator fun plus(other: CommunityCommentFilter) = CommunityCommentFilter(
        blockedPubkeys + other.blockedPubkeys,
        blockedEvents + other.blockedEvents,
        blockedWords + other.blockedWords,
    )
}

/**
 * Comment threads as a details page shows them (plan §7.2): two levels, so a reply to a reply still sits under the
 * comment that started the thread. Threads with the newest activity come first; replies read top to bottom.
 */
object CommunityCommentThreads {

    private const val MAX_DEPTH = 64

    fun <T> build(
        items: List<T>,
        id: (T) -> String,
        parent: (T) -> String?,
        createdAt: (T) -> Long,
    ): List<CommunityThread<T>> {
        val byId = items.associateBy(id)
        fun rootOf(item: T): T {
            var current = item
            repeat(MAX_DEPTH) {
                current = parent(current)?.let(byId::get) ?: return current
            }
            return current
        }
        return items.groupBy { id(rootOf(it)) }
            .map { (rootId, members) ->
                CommunityThread(
                    root = byId.getValue(rootId),
                    replies = members.filter { id(it) != rootId }.sortedWith(compareBy(createdAt).thenBy(id)),
                )
            }
            .sortedWith(
                compareByDescending<CommunityThread<T>> { thread -> (thread.replies.map(createdAt) + createdAt(thread.root)).max() }
                    .thenBy { id(it.root) },
            )
    }

    /**
     * The comments to show: those [filter] allows and their authors did not delete. A deletion counts only from the
     * comment's own author; a hidden comment takes its replies with it only when they cannot be placed elsewhere.
     */
    fun <T> visible(
        items: List<T>,
        filter: CommunityCommentFilter,
        deletions: Map<String, Set<String>>,
        id: (T) -> String,
        pubkey: (T) -> String,
        content: (T) -> String,
    ): List<T> = items.filter { item ->
        filter.allows(id(item), pubkey(item), content(item)) && id(item) !in deletions[pubkey(item)].orEmpty()
    }
}
