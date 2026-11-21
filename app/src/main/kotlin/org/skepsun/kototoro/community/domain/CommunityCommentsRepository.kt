package org.skepsun.kototoro.community.domain

import org.skepsun.kototoro.community.nostr.CommunityReportType
import org.skepsun.kototoro.community.nostr.NostrEvent
import org.skepsun.kototoro.core.library.community.CommunityCommentItem
import org.skepsun.kototoro.core.library.community.CommunityCommentPage
import org.skepsun.kototoro.core.library.community.CommunityService
import javax.inject.Inject
import javax.inject.Singleton

typealias CommunityCommentEntry = CommunityCommentItem

typealias CommunityCommentsPage = CommunityCommentPage

/**
 * Comments on a work, straight from the relays (plan §7.2), through the shared [CommunityService]: everything shown is
 * signed, carries the index's proof of work, and passes the index's moderation plus this reader's own blocks.
 */
@Singleton
class CommunityCommentsRepository @Inject constructor(
    private val service: CommunityService,
    private val repository: CommunityRepository,
) {

    suspend fun load(identity: CommunityWorkIdentity, summary: CommunityWorkSummary?): CommunityCommentsPage =
        service.comments(identity, summary)

    /** Posts a comment, or a reply to [parent]; returns the refusal when the reader may not comment on this work. */
    suspend fun post(
        identity: CommunityWorkIdentity,
        text: String,
        parent: NostrEvent?,
        spoiler: Boolean,
        language: String?,
    ): CommunityRatingRefusal? = service.postComment(identity, text, parent, spoiler, language)
        .also { repository.scheduleLeftovers() }

    suspend fun delete(event: NostrEvent) {
        service.deleteComment(event)
        repository.scheduleLeftovers()
    }

    /** Hides the comment here at once and tells the index; several trusted reports hide it for everyone. */
    suspend fun report(event: NostrEvent, type: CommunityReportType) {
        service.report(event, type)
        repository.scheduleLeftovers()
    }

    fun block(pubkey: String) = service.block(pubkey)
}
