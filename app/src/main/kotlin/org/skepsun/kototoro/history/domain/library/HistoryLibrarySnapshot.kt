package org.skepsun.kototoro.history.domain.library

import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * The complete, self-consistent history read model
 * (history-updates-feed komikku-alignment plan, Phase H2).
 *
 * Invariants:
 * - one row per active work_history entity (the table's primary key is
 *   `entity_id`), i.e. exactly the set the paging SQL produced;
 * - identity, display projection, tracking summary, favourite/pinned
 *   membership, tags, local bindings, category memberships and the metadata
 *   authority are resolved once in SQL and folded here;
 * - no filtering and no ordering happened yet — the 10 sort orders, the
 *  SQL-equivalent filters, the space/tab filters, the fold and the group
 *   headers are derived in memory (Phase H3);
 * - nothing writes; a per-row failure never fails the flow.
 */
data class HistorySnapshot(
    val rows: List<HistoryCardEntry>,
) {
    val isEmpty: Boolean
        get() = rows.isEmpty()

    companion object {
        val Empty = HistorySnapshot(rows = emptyList())
    }
}

/** One display tag of a history row: the Tag filter matches on title + key. */
data class HistoryCardTag(
    val title: String,
    val key: String,
)

/** One local binding of a history entity (the space filter's data). */
data class HistoryBinding(
    val mangaId: Long,
    val source: String,
    /** COALESCE(manga content type, entity content type) as parsed type. */
    val contentType: ContentType?,
)

/**
 * One history card row: entity identity + progress + the display projection.
 *
 * The UI id is `entityId.toUiGroupId(contentTypeOrdinal)` — the same negative
 * encoding the paging-era `foldAdjacentByEntity` produced, so selection and
 * removal keep their identity contract.
 */
data class HistoryCardEntry(
    val uiId: Long,
    val entityId: Long,
    val anchorMangaId: Long,
    val preferredLocalMangaId: Long?,
    val displayMangaId: Long?,
    // progress
    val updatedAt: Long,
    val createdAt: Long,
    val percent: Float,
    val chaptersCount: Int,
    val chapterId: Long,
    // tracking summary (entity level)
    val newChapters: Int,
    val lastChapterDate: Long?,
    // membership
    val isFavourite: Boolean,
    val isPinned: Boolean,
    /** Any bound projection has a local download (the `Downloaded` filter). */
    val isDownloaded: Boolean,
    val categoryIds: Set<Long>,
    // authoritative content type: anchor manga type first, entity type second
    val contentType: ContentType?,
    val displayContentTypeOrdinal: Int,
    // local bindings (space filter + projection count)
    val localMangaIds: List<Long>,
    val bindings: List<HistoryBinding>,
    // display projection
    val title: String,
    val altTitle: String?,
    val coverUrl: String?,
    val largeCoverUrl: String?,
    val author: String?,
    val sourceName: String,
    val publicationState: ContentState?,
    val isNsfw: Boolean,
    val rating: Float,
    val tags: List<HistoryCardTag>,
    val overrideTitle: String?,
    val overrideCoverUrl: String?,
    val metadataTrackingService: Int?,
    val metadataTrackingTitle: String?,
    val metadataTrackingCoverUrl: String?,
    val sourceGroupFlags: Int,
    val sourceOriginFlags: Int,
)
