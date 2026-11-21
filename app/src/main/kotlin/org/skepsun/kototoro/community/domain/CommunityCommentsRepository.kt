package org.skepsun.kototoro.community.domain

import org.skepsun.kototoro.community.data.CommunityLocalStore
import org.skepsun.kototoro.community.data.CommunitySnapshotRepository
import org.skepsun.kototoro.community.nostr.CommunityComment
import org.skepsun.kototoro.community.nostr.CommunityEventSchema
import org.skepsun.kototoro.community.nostr.CommunityProfiles
import org.skepsun.kototoro.community.nostr.CommunityReportType
import org.skepsun.kototoro.community.nostr.NostrBech32
import org.skepsun.kototoro.community.nostr.NostrEvent
import org.skepsun.kototoro.community.nostr.NostrFilter
import org.skepsun.kototoro.community.nostr.NostrPow
import org.skepsun.kototoro.community.nostr.NostrRelayClient
import org.skepsun.kototoro.community.nostr.NostrRelayPool
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

data class CommunityCommentEntry(
    val comment: CommunityComment,
    val event: NostrEvent,
    /** The author's chosen name, or a short form of their public key. */
    val author: String,
    val isMine: Boolean,
    /** Written on this device and not yet stored by any relay. */
    val isPending: Boolean,
)

data class CommunityCommentsPage(
    val threads: List<CommunityThread<CommunityCommentEntry>>,
    /** How many relays answered; zero means the list says nothing about what exists. */
    val relaysAnswered: Int,
)

/**
 * Comments on a work, straight from the relays (plan §7.2): the index only counts them, so a comment shows as soon as
 * one relay has it. Everything shown is signed, carries the index's proof of work, and passes the moderation the index
 * publishes plus this reader's own blocks.
 */
@Singleton
class CommunityCommentsRepository @Inject constructor(
    private val repository: CommunityRepository,
    private val relayClient: NostrRelayClient,
    private val snapshots: CommunitySnapshotRepository,
    private val local: CommunityLocalStore,
) {

    suspend fun load(identity: CommunityWorkIdentity, summary: CommunityWorkSummary?): CommunityCommentsPage {
        val pool = NostrRelayPool(relayClient, repository.relays())
        val keys = identity.keys.take(MAX_QUERY_KEYS)
        val me = repository.keys()?.publicKeyHex
        val pow = repository.powComment()
        val result = pool.query(listOf(NostrFilter(kinds = listOf(CommunityEventSchema.KIND_COMMENT), tags = mapOf("I" to keys), limit = LIMIT)))
        val published = result.events.filter { it.pubkey == me || NostrPow.satisfies(it, pow, CommunityEventSchema.POW_TAG) }
        val pending = repository.pendingEvents(CommunityEventSchema.KIND_COMMENT)
            .filter { event -> event.tagValues("I").any { it in keys } && published.none { it.id == event.id } }
        val events = (published + pending + repository.recentComments(keys).filter { it.pubkey == me })
            .distinctBy(NostrEvent::id)
        val comments = events.mapNotNull { event -> CommunityEventSchema.parseComment(event)?.let { it to event } }
        if (comments.isEmpty()) return CommunityCommentsPage(emptyList(), result.relays.count { it.error == null })

        val ids = comments.map { it.first.id }
        val deletions = runCatchingCancellable {
            pool.query(listOf(NostrFilter(kinds = listOf(CommunityEventSchema.KIND_DELETION), tags = mapOf("e" to ids))))
        }.getOrNull()?.events.orEmpty()
            .mapNotNull(CommunityEventSchema::parseDeletion)
            .groupBy({ it.pubkey }, { it.eventIds })
            .mapValues { (_, sets) -> sets.flatten().toSet() }
        val names = repository.profiles(pool, comments.map { it.first.pubkey }.distinct())
            .mapValues { CommunityProfiles.nickname(it.value) }
        val moderation = runCatchingCancellable { snapshots.moderation() }.getOrNull() ?: CommunityModeration()
        val filter = CommunityCommentFilter(
            blockedPubkeys = moderation.blockedPubkeys.toSet(),
            blockedEvents = moderation.blockedEvents.toSet() + summary?.hiddenComments.orEmpty(),
            blockedWords = moderation.blockedWords,
        ) + local.filter
        val pendingIds = pending.mapTo(HashSet()) { it.id }
        val entries = comments.map { (comment, event) ->
            CommunityCommentEntry(
                comment = comment,
                event = event,
                author = names[comment.pubkey] ?: shortKey(comment.pubkey),
                isMine = comment.pubkey == me,
                isPending = comment.id in pendingIds,
            )
        }
        val visible = CommunityCommentThreads.visible(
            entries, filter, deletions, { it.comment.id }, { it.comment.pubkey }, { it.comment.content },
        )
        return CommunityCommentsPage(
            threads = CommunityCommentThreads.build(visible, { it.comment.id }, { it.comment.parentId }, { it.comment.createdAt }),
            relaysAnswered = result.relays.count { it.error == null },
        )
    }

    /** Posts a comment, or a reply to [parent]; returns the refusal when the reader may not comment on this work. */
    suspend fun post(
        identity: CommunityWorkIdentity,
        text: String,
        parent: NostrEvent?,
        spoiler: Boolean,
        language: String?,
    ): CommunityRatingRefusal? {
        repository.refusal(identity)?.let { return it }
        val keys = repository.keys() ?: return CommunityRatingRefusal.NO_IDENTITY
        val draft = CommunityEventSchema.comment(
            keys = identity.keys,
            content = text,
            title = identity.title,
            createdAt = System.currentTimeMillis() / 1000,
            parent = parent,
            spoiler = spoiler,
            nsfw = identity.nsfw,
            language = language,
        )
        repository.publish(draft, keys, repository.powComment())
        return null
    }

    suspend fun delete(event: NostrEvent) {
        val keys = repository.keys() ?: return
        if (event.pubkey != keys.publicKeyHex) return
        local.hide(event.id)
        repository.publish(CommunityEventSchema.deletion(event, System.currentTimeMillis() / 1000), keys)
    }

    /** Hides the comment here at once and tells the index; several trusted reports hide it for everyone. */
    suspend fun report(event: NostrEvent, type: CommunityReportType) {
        local.hide(event.id)
        val keys = repository.keys() ?: return
        repository.publish(CommunityEventSchema.report(event, type, System.currentTimeMillis() / 1000), keys)
    }

    fun block(pubkey: String) = local.block(pubkey)

    companion object {
        private const val LIMIT = 500
        /** Relays cap filter sizes; a work's strong keys and first titles name it well enough. */
        private const val MAX_QUERY_KEYS = 10

        fun shortKey(pubkey: String): String =
            NostrBech32.encodePublicKey(pubkey).let { "${it.take(10)}…${it.takeLast(4)}" }
    }
}
