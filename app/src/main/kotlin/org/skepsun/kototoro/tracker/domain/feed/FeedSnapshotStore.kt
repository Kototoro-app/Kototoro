package org.skepsun.kototoro.tracker.domain.feed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.tracker.data.FeedLogRow
import org.skepsun.kototoro.tracker.data.TrackedChapterCountRow
import org.skepsun.kototoro.tracker.data.TrackedOverrideRow
import org.skepsun.kototoro.tracker.data.TrackedTagFacetRow
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
 * - nothing writes and no network is performed.
 *
 * The assembly is shared with the Windows host ([FeedSnapshotAssembler]); Android supplies its source groups.
 */
@Singleton
class FeedSnapshotStore @Inject constructor(
    private val database: MangaDatabase,
    sourceGroupManager: SourceGroupManager,
) {

    private val assembler = FeedSnapshotAssembler(
        contentGroupOf = sourceGroupManager::getContentGroupByName,
        originGroupOf = sourceGroupManager::getOriginGroupByName,
    )

    fun observe(): Flow<FeedSnapshot> = assembler.observe(database).flowOn(Dispatchers.Default)

    internal fun buildSnapshot(
        logRows: List<FeedLogRow>,
        updateRows: List<UpdateTrackRow>,
        tagFacets: List<TrackedTagFacetRow>,
        overrides: List<TrackedOverrideRow>,
        chapterCounts: List<TrackedChapterCountRow>,
    ): FeedSnapshot = assembler.buildSnapshot(logRows, updateRows, tagFacets, overrides, chapterCounts)
}
