package org.skepsun.kototoro.tracker.domain.feed

import org.skepsun.kototoro.core.jsonsource.ContentGroup
import org.skepsun.kototoro.core.jsonsource.OriginGroup
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * The complete, self-consistent feed read model
 * (history-updates-feed komikku-alignment plan, Phase F2).
 *
 * Invariants (mirroring [org.skepsun.kototoro.favourites.domain.library.FavouriteLibrarySnapshot]):
 * - every emission is complete: log rows (the bounded `track_logs` set) plus the
 *   tracked-with-updates set, with identity, pinned and display already resolved;
 * - rows are keyed by log id; `updateRowsByOwnerId` carries the `chapters_new > 0`
 *   set keyed by track owner id for the showAll branch and the header;
 * - broken rows (dangling anchor, no manga) survive — the read model never drops
 *   data the maintenance layer kept;
 * - no filtering happened yet: quick filters, the feed scope, NSFW, the tag
 *   blacklist and the limit window are all derived later, in memory.
 */
data class FeedSnapshot(
    val rows: List<FeedCardRow>,
    val updateRowsByOwnerId: Map<Long, FeedUpdateRow>,
) {
    val isEmpty: Boolean
        get() = rows.isEmpty() && updateRowsByOwnerId.isEmpty()

    companion object {
        val Empty = FeedSnapshot(rows = emptyList(), updateRowsByOwnerId = emptyMap())
    }
}

/** One feed card: a `track_logs` entry with identity and display resolved. */
data class FeedCardRow(
    val logId: Long,
    val anchorMangaId: Long,
    val ownerId: Long,
    val entityId: Long?,
    val preferredLocalMangaId: Long?,
    val chapters: List<String>,
    val createdAt: Long,
    val unread: Boolean,
    val isPinned: Boolean,
    // display projection (COALESCE(preferred, anchor)); null title = broken row
    val displayMangaId: Long?,
    val title: String,
    val altTitle: String?,
    val coverUrl: String?,
    val author: String?,
    val sourceName: String,
    val displayUrl: String,
    val contentType: ContentType?,
    val publicationState: ContentState?,
    val isNsfw: Boolean,
    val rating: Float,
    // tag identity on the representative manga (the tag filter key)
    val tagIds: Set<Long>,
    val tagTitles: List<String>,
    // manual override of the display manga
    val overrideTitle: String?,
    val overrideCoverUrl: String?,
    /** Stable group/origin bit flags for the group tab and source-tag filters. */
    val sourceGroupFlags: Int,
    val sourceOriginFlags: Int,
) {
    val hasDisplay: Boolean
        get() = displayMangaId != null

    /** The tag blacklist matches on the display title of any tag. */
    val tagTitleSet: Set<String>
        get() = tagTitles.toSet()

    fun contentGroup(contentGroup: ContentGroup): Boolean = sourceGroupFlags and (1 shl contentGroup.ordinal) != 0

    fun originGroup(originGroup: OriginGroup): Boolean = sourceOriginFlags and (1 shl originGroup.ordinal) != 0
}

/** A tracked work with pending new chapters, keyed by the track's owner id. */
data class FeedUpdateRow(
    val ownerId: Long,
    val mangaId: Long,
    val entityId: Long?,
    val preferredLocalMangaId: Long?,
    val newChapters: Int,
    val lastChapterDate: Long?,
    val lastCheckTime: Long,
    val lastChapterId: Long,
    val isPinned: Boolean,
    val displayMangaId: Long?,
    val title: String,
    val coverUrl: String?,
    val sourceName: String,
    val isNsfw: Boolean,
)
