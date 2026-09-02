package org.skepsun.kototoro.tracker.domain.feed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.jsonsource.ContentGroup
import org.skepsun.kototoro.core.jsonsource.OriginGroup
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.tracker.data.FeedLogRow
import org.skepsun.kototoro.tracker.data.TrackedChapterCountRow
import org.skepsun.kototoro.tracker.data.TrackedTagFacetRow
import org.skepsun.kototoro.tracker.data.TrackedOverrideRow
import org.skepsun.kototoro.tracker.data.UpdateTrackRow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The deep module owning the tracker feed read model
 * (history-updates-feed komikku-alignment plan, Phase F2).
 *
 * Interface contract (mirroring
 * [org.skepsun.kototoro.favourites.domain.library.FavouriteLibrarySnapshotStore]):
 * - [observe] takes no parameters: the feed limit window, showAllUpdates, quick
 *   filters and the feed scope are all derived in memory from the snapshot;
 * - every emission is complete and self-consistent (see [FeedSnapshot]);
 * - upstream changes that do not alter the snapshot do not re-emit;
 * - broken rows survive as rows instead of failing the flow;
 * - nothing writes and no network is performed; the paging-era per-page
 *   `resolveDisplayTrackingLogItems` lookups (per-anchor identity, fallback
 *   content, display tracking content) collapse into the snapshot assembly.
 */
@Singleton
class FeedSnapshotStore @Inject constructor(
    private val database: MangaDatabase,
    private val sourceGroupManager: SourceGroupManager,
) {

    fun observe(): Flow<FeedSnapshot> {
        val dao = database.getTrackerReadDao()
        return combine(
            dao.observeFeedLogRows().distinctUntilChanged(),
            dao.observeUpdateTrackRows().distinctUntilChanged(),
            dao.observeTrackedTagFacets().distinctUntilChanged(),
            dao.observeTrackedOverrides().distinctUntilChanged(),
            dao.observeTrackedChapterCounts().distinctUntilChanged(),
        ) { values: Array<*> ->
            @Suppress("UNCHECKED_CAST")
            buildSnapshot(
                logRows = values[0] as List<FeedLogRow>,
                updateRows = values[1] as List<UpdateTrackRow>,
                tagFacets = values[2] as List<TrackedTagFacetRow>,
                overrides = values[3] as List<TrackedOverrideRow>,
                chapterCounts = values[4] as List<TrackedChapterCountRow>,
            )
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    internal fun buildSnapshot(
        logRows: List<FeedLogRow>,
        updateRows: List<UpdateTrackRow>,
        tagFacets: List<TrackedTagFacetRow>,
        overrides: List<TrackedOverrideRow>,
        chapterCounts: List<TrackedChapterCountRow>,
    ): FeedSnapshot {
        if (logRows.isEmpty() && updateRows.isEmpty()) {
            return FeedSnapshot.Empty
        }

        // ---- tag facets keyed by the representative manga id
        val tagIdsByMangaId = HashMap<Long, LinkedHashSet<Long>>(tagFacets.size)
        val tagTitlesByMangaId = HashMap<Long, LinkedHashMap<Long, String>>(tagFacets.size)
        for (facet in tagFacets) {
            tagIdsByMangaId.getOrPut(facet.mangaId) { LinkedHashSet() }.add(facet.tagId)
            tagTitlesByMangaId.getOrPut(facet.mangaId) { LinkedHashMap() }
                .putIfAbsent(facet.tagId, facet.tagTitle)
        }

        // ---- manual overrides and chapter counts keyed by manga id
        val overrideByMangaId = HashMap<Long, TrackedOverrideRow>(overrides.size)
        for (override in overrides) {
            overrideByMangaId[override.mangaId] = override
        }
        val chapterCountByMangaId = HashMap<Long, Int>(chapterCounts.size)
        for (count in chapterCounts) {
            chapterCountByMangaId[count.mangaId] = count.chapterCount
        }

        // ---- feed rows (log entries)
        val rows = ArrayList<FeedCardRow>(logRows.size)
        for (log in logRows) {
            val displayId = log.displayMangaId
            val tagIds = displayId?.let(tagIdsByMangaId::get).orEmpty()
            val tagTitles = displayId?.let(tagTitlesByMangaId::get).orEmpty()
            val manualOverride = displayId?.let(overrideByMangaId::get)
            rows += FeedCardRow(
                logId = log.logId,
                anchorMangaId = log.anchorMangaId,
                ownerId = log.ownerId,
                entityId = log.entityId,
                preferredLocalMangaId = log.preferredLocalMangaId,
                chapters = log.chapters.split('\n').filterNot { it.isEmpty() },
                createdAt = log.createdAt,
                unread = log.unread,
                isPinned = log.entityPinned,
                displayMangaId = displayId,
                title = log.displayTitle.orEmpty(),
                altTitle = log.displayAltTitle,
                coverUrl = log.displayCoverUrl?.takeIf { it.isNotBlank() },
                author = log.displayAuthor,
                sourceName = log.displaySource.orEmpty(),
                displayUrl = log.displayUrl.orEmpty(),
                contentType = log.displayContentType?.let(::parseContentType),
                publicationState = log.displayState?.let(::parseContentState),
                isNsfw = log.displayNsfw == true,
                rating = log.displayRating ?: -1f,
                tagIds = tagIds,
                tagTitles = tagTitles.values.toList(),
                overrideTitle = manualOverride?.titleOverride?.takeIf { it.isNotBlank() },
                overrideCoverUrl = manualOverride?.coverOverride?.takeIf { it.isNotBlank() },
                sourceGroupFlags = contentGroupFlag(log.displaySource, log.displayNsfw == true),
                sourceOriginFlags = originGroupFlag(log.displaySource),
            )
        }

        // ---- tracked works with pending updates keyed by owner id
        val updateRowsByOwnerId = HashMap<Long, FeedUpdateRow>(updateRows.size)
        for (track in updateRows) {
            updateRowsByOwnerId[track.ownerId] = FeedUpdateRow(
                ownerId = track.ownerId,
                mangaId = track.mangaId,
                entityId = track.entityId,
                preferredLocalMangaId = track.preferredLocalMangaId,
                newChapters = track.newChapters,
                lastChapterDate = track.lastChapterDate,
                lastCheckTime = track.lastCheckTime,
                lastChapterId = track.lastChapterId,
                isPinned = track.entityPinned,
                displayMangaId = track.displayMangaId,
                title = track.displayTitle.orEmpty(),
                coverUrl = track.displayCoverUrl?.takeIf { it.isNotBlank() },
                sourceName = track.displaySource.orEmpty(),
                isNsfw = track.displayNsfw == true,
            )
        }

        return FeedSnapshot(rows = rows, updateRowsByOwnerId = updateRowsByOwnerId)
    }

    private fun contentGroupFlag(sourceName: String?, nsfw: Boolean): Int {
        val group = sourceGroupManager.getContentGroupByName(sourceName.orEmpty(), nsfw)
        return 1 shl group.ordinal
    }

    private fun originGroupFlag(sourceName: String?): Int {
        val origin = sourceGroupManager.getOriginGroupByName(sourceName.orEmpty())
        return 1 shl origin.ordinal
    }

    private companion object {
        fun parseContentType(name: String): ContentType? = runCatching { ContentType.valueOf(name) }.getOrNull()

        fun parseContentState(name: String): ContentState? = runCatching { ContentState.valueOf(name) }.getOrNull()
    }
}
