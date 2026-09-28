package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.core.db.entity.MangaPrefsEntity
import org.skepsun.kototoro.core.db.entity.TrackingSiteLinkEntity
import org.skepsun.kototoro.core.model.ContentHistory
import org.skepsun.kototoro.core.model.getPreferredBranch
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.skepsun.kototoro.notes.data.MediaNoteEntity
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.readingrecord.data.ReadingJumpPointEntity
import org.skepsun.kototoro.readingrecord.data.ReadingRecordEntity
import org.skepsun.kototoro.tracker.data.TrackEntity
import java.time.Instant

/** Everything the old entry owns that a migration may carry over. */
data class MigrationSnapshot(
    val favourites: List<FavouriteEntity>,
    val history: HistoryEntity?,
    val prefs: MangaPrefsEntity?,
    val trackingLinks: List<TrackingSiteLinkEntity>,
    val track: TrackEntity?,
    val notes: List<MediaNoteEntity>,
    val sessions: List<ReadingRecordEntity>,
    val jumpPoints: List<ReadingJumpPointEntity>,
)

/** Database writes for one migration, applied inside a single transaction. */
data class MigrationPlan(
    val favouritesToUpsert: List<FavouriteEntity>,
    val deleteOldFavourites: Boolean,
    val historyToUpsert: HistoryEntity?,
    val deleteOldHistory: Boolean,
    val prefsToUpsert: MangaPrefsEntity?,
    val trackingLinksToUpsert: List<TrackingSiteLinkEntity>,
    val trackingLinksToDelete: List<TrackingSiteLinkEntity>,
    val trackToUpsert: TrackEntity?,
    val deleteOldTrack: Boolean,
    val notesToInsert: List<MediaNoteEntity>,
    val notesToUpdate: List<MediaNoteEntity>,
    val moveStats: Boolean,
    val sessionsToUpdate: List<ReadingRecordEntity>,
    val jumpPointsToUpdate: List<ReadingJumpPointEntity>,
)

object MigrationPlanner {

    fun plan(
        old: Content,
        new: Content,
        snapshot: MigrationSnapshot,
        mode: MigrationMode,
        flags: Set<MigrationDataFlag>,
        now: Long,
    ): MigrationPlan {
        val replace = mode == MigrationMode.REPLACE
        val oldChapters = old.chapters.orEmpty()
        val newChapters = new.chapters.orEmpty()
        val chapterMap = ChapterIdMapper.map(oldChapters, newChapters)
        fun remap(chapterId: Long) = chapterMap[chapterId] ?: chapterId

        val progress = MigrationDataFlag.PROGRESS in flags
        val tracking = MigrationDataFlag.TRACKING in flags
        val notes = MigrationDataFlag.NOTES in flags
        val stats = MigrationDataFlag.STATS in flags && replace

        val newHistory = snapshot.history
            ?.takeIf { progress && newChapters.isNotEmpty() }
            ?.let { makeNewHistory(old, new, it) }

        val movedNotes = if (notes) {
            snapshot.notes.map { note ->
                val chapterId = chapterMap[note.chapterId]
                    ?: ChapterIdMapper.idAtIndex(newChapters, note.chapterIndex)
                    ?: note.chapterId
                note.copy(mangaId = new.id, chapterId = chapterId, updatedAt = now)
            }
        } else {
            emptyList()
        }

        val lastNewChapter = newChapters.lastOrNull()
        return MigrationPlan(
            favouritesToUpsert = snapshot.favourites.map { it.copy(mangaId = new.id, updatedAt = now) },
            deleteOldFavourites = replace && snapshot.favourites.isNotEmpty(),
            historyToUpsert = newHistory,
            deleteOldHistory = replace && newHistory != null,
            prefsToUpsert = snapshot.prefs?.copy(mangaId = new.id),
            trackingLinksToUpsert = if (tracking) {
                snapshot.trackingLinks.map { it.copy(mangaId = new.id, sourceName = new.source.name, updatedAt = now) }
            } else {
                emptyList()
            },
            trackingLinksToDelete = if (tracking && replace) snapshot.trackingLinks else emptyList(),
            trackToUpsert = snapshot.track?.takeIf { tracking }?.let {
                TrackEntity(
                    mangaId = new.id,
                    lastChapterId = lastNewChapter?.id ?: 0L,
                    newChapters = 0,
                    lastCheckTime = now,
                    lastChapterDate = lastNewChapter?.uploadDate ?: 0L,
                    lastResult = TrackEntity.RESULT_EXTERNAL_MODIFICATION,
                    lastError = null,
                )
            },
            deleteOldTrack = tracking && replace && snapshot.track != null,
            notesToInsert = if (replace) emptyList() else movedNotes.map { it.copy(id = 0L) },
            notesToUpdate = if (replace) movedNotes else emptyList(),
            moveStats = stats,
            sessionsToUpdate = if (stats) {
                snapshot.sessions.map {
                    it.copy(mangaId = new.id, startChapterId = remap(it.startChapterId), endChapterId = remap(it.endChapterId))
                }
            } else {
                emptyList()
            },
            jumpPointsToUpdate = if (stats) {
                snapshot.jumpPoints.map {
                    it.copy(mangaId = new.id, fromChapterId = remap(it.fromChapterId), toChapterId = remap(it.toChapterId))
                }
            } else {
                emptyList()
            },
        )
    }

    // Moved verbatim from the previous MigrateUseCase implementation.
    private fun makeNewHistory(oldContent: Content, newContent: Content, history: HistoryEntity): HistoryEntity {
        if (oldContent.chapters.isNullOrEmpty()) {
            val branch = newContent.getPreferredBranch(null)
            val chapters = checkNotNull(newContent.getChapters(branch))
            val currentChapter = if (history.percent in 0f..1f) {
                chapters[(chapters.lastIndex * history.percent).toInt()]
            } else {
                chapters.first()
            }
            return history.copy(
                mangaId = newContent.id,
                chapterId = currentChapter.id,
                deletedAt = 0,
                chaptersCount = chapters.count { it.branch == currentChapter.branch },
            )
        }
        val branch = oldContent.getPreferredBranch(history.toContentHistory())
        val oldChapters = checkNotNull(oldContent.getChapters(branch))
        var index = oldChapters.indexOfFirst { it.id == history.chapterId }
        if (index < 0) {
            index = if (history.percent in 0f..1f) (oldChapters.lastIndex * history.percent).toInt() else 0
        }
        val newChapters = checkNotNull(newContent.chapters).groupBy { it.branch }
        val newBranch = if (newChapters.containsKey(branch)) branch else newContent.getPreferredBranch(null)
        val branchChapters = checkNotNull(newChapters[newBranch])
        val oldChapter = oldChapters[index]
        val newChapterId = (branchChapters.findByNumber(oldChapter.volume, oldChapter.number)
            ?: branchChapters.getOrNull(index)
            ?: branchChapters.last()).id
        return history.copy(
            mangaId = newContent.id,
            chapterId = newChapterId,
            percent = PROGRESS_NONE,
            deletedAt = 0,
            chaptersCount = branchChapters.size,
        )
    }

    private fun HistoryEntity.toContentHistory() = ContentHistory(
        createdAt = Instant.ofEpochMilli(createdAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
        chapterId = chapterId,
        page = page,
        scroll = scroll.toInt(),
        percent = percent,
        chaptersCount = chaptersCount,
        parentChapterId = parentChapterId,
    )

    private fun List<ContentChapter>.findByNumber(volume: Int, number: Float): ContentChapter? =
        if (number <= 0f) null else firstOrNull { it.volume == volume && it.number == number }
}
