package org.skepsun.kototoro.tracker.domain.updates

import org.skepsun.kototoro.core.jsonsource.ContentGroup
import org.skepsun.kototoro.core.jsonsource.OriginGroup
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * The complete, self-consistent updates read model
 * (history-updates-feed komikku-alignment plan, Phase U2).
 *
 * Invariants:
 * - every emission is the complete `chapters_new > 0` set (one row per track),
 *   already grouped per entity — the paging-era per-page `aggregateByEntity`
 *   with its cross-page @Volatile accumulator maps is replaced by a single
 *   whole-list grouping that cannot split an entity at a page boundary;
 * - identity, pinned, display projection and the metadata authority are
 *   resolved once in SQL (see [org.skepsun.kototoro.tracker.data.TrackerReadDao]);
 * - no filtering happened yet — quick filters, group tab, source tags, NSFW
 *   and the tag blacklist are derived later, in memory;
 * - nothing writes; a per-row failure never fails the flow.
 */
data class UpdatesSnapshot(
    val groups: List<UpdateGroupRow>,
) {
    val isEmpty: Boolean
        get() = groups.isEmpty()

    companion object {
        val Empty = UpdatesSnapshot(groups = emptyList())
    }
}

/**
 * One entity group: the per-entity aggregate the updates page renders.
 *
 * Field budget follows the legacy `UpdateGroup` + `mapUpdatesPage`: the
 * representative (preferred projection first, else the freshest track), the
 * sum of new chapters, the max last-chapter date, the track manga ids (the
 * legacy "N projections" suffix counted tracks) and the metadata authority.
 */
data class UpdateGroupRow(
    /** UI id: `entityId.toUiGroupId(contentTypeOrdinal)` or the manga id. */
    val uiId: Long,
    val entityId: Long?,
    val preferredLocalMangaId: Long?,
    /** Track manga ids of this entity (ordered by first appearance). */
    val mangaIds: List<Long>,
    val totalNewChapters: Int,
    val lastChapterDate: Long?,
    val isPinned: Boolean,
    // ---- representative (display) fields
    val displayMangaId: Long?,
    val title: String,
    val altTitle: String?,
    val coverUrl: String?,
    val author: String?,
    val sourceName: String,
    val contentType: ContentType?,
    val publicationState: ContentState?,
    val isNsfw: Boolean,
    val rating: Float,
    val tagIds: Set<Long>,
    val tagTitles: List<String>,
    val overrideTitle: String?,
    val overrideCoverUrl: String?,
    val metadataTrackingService: Int?,
    val metadataTrackingTitle: String?,
    val metadataTrackingCoverUrl: String?,
    val sourceGroupFlags: Int,
    val sourceOriginFlags: Int,
    /** The content-type ordinal used for the UI id encoding (legacy parity). */
    val displayContentTypeOrdinal: Int,
) {
    val hasDisplay: Boolean
        get() = displayMangaId != null

    fun contentGroup(group: ContentGroup): Boolean = sourceGroupFlags and (1 shl group.ordinal) != 0

    fun originGroup(origin: OriginGroup): Boolean = sourceOriginFlags and (1 shl origin.ordinal) != 0
}

/** One raw track row as assembled before grouping (store-internal). */
internal data class TrackRowSeed(
    val ownerId: Long,
    val mangaId: Long,
    val entityId: Long?,
    val preferredLocalMangaId: Long?,
    val newChapters: Int,
    val lastChapterDate: Long?,
    val lastCheckTime: Long,
    val isPinned: Boolean,
    val displayMangaId: Long?,
    val title: String,
    val altTitle: String?,
    val coverUrl: String?,
    val author: String?,
    val sourceName: String,
    val contentType: ContentType?,
    val displayContentTypeOrdinal: Int,
    val publicationState: ContentState?,
    val isNsfw: Boolean,
    val rating: Float,
    val tagIds: Set<Long>,
    val tagTitles: List<String>,
    val overrideTitle: String?,
    val overrideCoverUrl: String?,
    val metadataTrackingService: Int?,
    val metadataTrackingTitle: String?,
    val metadataTrackingCoverUrl: String?,
    val sourceGroupFlags: Int,
    val sourceOriginFlags: Int,
)
