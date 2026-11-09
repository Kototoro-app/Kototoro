package org.skepsun.kototoro.tracker.domain

import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.ChapterEntity
import org.skepsun.kototoro.tracker.data.TrackEntity

/*
 * New chapters are found where a work's chapter list is stored, after Mihon's SyncChaptersWithSource: the stored
 * list is the baseline and every refresh of it, from a check, the details page or the reader, is compared with it.
 * See docs/architecture/chapter-update-detection-plan-2026-11.md.
 */

/** What [MangaDatabase.storeChapterSnapshot] did with a chapter list. */
sealed interface ChapterSnapshotResult {

    /**
     * Stored without reporting anything: the work is not tracked, this is its first list, or the list changed its
     * identities too widely to tell new chapters from renamed ones.
     */
    data object Stored : ChapterSnapshotResult

    /** Stored; [reported] are the new chapters recorded as updates (possibly none), in list order. */
    data class Updated(val reported: List<ChapterEntity>) : ChapterSnapshotResult

    /** Not stored: the list shrank too far to be a complete one, so the previous list stays the baseline. */
    data object Incomplete : ChapterSnapshotResult
}

/** How [diffChapterSnapshots] sees a new chapter list against the stored one. */
data class ChapterSnapshotDiff(
    /** Chapters that were not in the stored list and are not re-uploads of removed ones, in list order. */
    val fresh: List<ChapterEntity>,
    /** Removed chapter to the chapter that replaces it under a new identity. */
    val reuploads: Map<ChapterEntity, ChapterEntity>,
)

/** Lists at least this long are protected from being replaced by a fraction of themselves. */
const val INCOMPLETE_LIST_MIN_SIZE = 10

/** More fresh chapters than this, and more than half the list, means the list was re-identified, not extended. */
const val REIDENTIFIED_LIST_MIN_FRESH = 10

/** True when [new] is too short to stand for [old]: a chapter list cut off by paging or by a failing source. */
fun isIncompleteChapterList(old: List<ChapterEntity>, new: List<ChapterEntity>): Boolean =
    old.size >= INCOMPLETE_LIST_MIN_SIZE && new.size * 2 < old.size

/**
 * Chapters of [new] that [old] does not have by update key, with chapters re-uploaded under a new identity taken
 * out: a removed chapter and an added one in the same branch with the same trusted number
 * ([ChapterNumberRules.trusted]) are the same chapter, as in Mihon.
 */
fun diffChapterSnapshots(old: List<ChapterEntity>, new: List<ChapterEntity>, workTitle: String): ChapterSnapshotDiff {
    val oldKeys = old.mapTo(HashSet()) { it.updateKey() }
    val newKeys = new.mapTo(HashSet()) { it.updateKey() }
    val added = new.filter { it.updateKey() !in oldKeys }
    val removed = old.filter { it.updateKey() !in newKeys }
    if (added.isEmpty() || removed.isEmpty()) return ChapterSnapshotDiff(added, emptyMap())
    val oldNumbers = old.trustedNumbers(workTitle)
    val newNumbers = new.trustedNumbers(workTitle)
    // Only a number one removed chapter of the branch has can name it.
    val removedByNumber = removed
        .mapNotNull { chapter -> oldNumbers[chapter]?.let { (chapter.branch to it) to chapter } }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.size == 1 }
        .mapValues { it.value.single() }
    val reuploads = LinkedHashMap<ChapterEntity, ChapterEntity>()
    val fresh = added.filter { chapter ->
        val number = newNumbers[chapter] ?: return@filter true
        val previous = removedByNumber[chapter.branch to number] ?: return@filter true
        if (previous in reuploads) return@filter true
        reuploads[previous] = chapter
        false
    }
    return ChapterSnapshotDiff(fresh, reuploads)
}

private fun List<ChapterEntity>.trustedNumbers(workTitle: String): Map<ChapterEntity, Float?> =
    zip(ChapterNumberRules.trusted(this, workTitle, ChapterEntity::title, ChapterEntity::number)).toMap()

/**
 * Stores [chapters] as the chapter list of work [mangaId] and, for a tracked work, records the new ones as updates:
 * the chapters [diffChapterSnapshots] finds in the branch being read, after [reportedNewChapters] dropped those
 * before the reading position or long released. Re-uploaded chapters carry their update state over to the new
 * identity. When [markDuplicatesRead], a new chapter whose trusted number a read chapter already has is recorded as
 * read. The track's last chapter moves to the end of the branch; its check time is the checker's to set.
 *
 * The branch being read is the reading position's, else the track's last chapter's, else [fallbackBranch], else the
 * last chapter's. Runs inside the caller's write transaction.
 */
suspend fun MangaDatabase.storeChapterSnapshot(
    mangaId: Long,
    workTitle: String,
    chapters: List<ChapterEntity>,
    now: Long,
    fallbackBranch: String? = null,
    markDuplicatesRead: Boolean = false,
): ChapterSnapshotResult {
    val chaptersDao = getChaptersDao()
    val old = chaptersDao.findAll(mangaId)
    val track = getTracksDao().find(mangaId)
    // Without a stored list a track may still know its last chapter: a restored backup carries tracks but no
    // chapter lists, and the chapters released since the backup are found from that anchor instead.
    val anchored = track != null && old.isEmpty() && !isEmptyTrack(track.lastChapterId)
    if (track == null || (old.isEmpty() && !anchored) || chapters.isEmpty()) {
        if (track != null && old.isNotEmpty() && isIncompleteChapterList(old, chapters)) {
            return ChapterSnapshotResult.Incomplete
        }
        chaptersDao.replaceAll(mangaId, chapters)
        return ChapterSnapshotResult.Stored
    }
    if (isIncompleteChapterList(old, chapters)) return ChapterSnapshotResult.Incomplete

    val history = getHistoryDao().find(mangaId)?.takeIf { it.deletedAt == 0L }
    val readChapterId = history?.chapterId ?: 0L
    val fresh: List<ChapterEntity>
    val anchorId: Long
    if (anchored) {
        anchorId = track.lastChapterId
        val anchorBranch = branchBeingRead(chapters, old, readChapterId, anchorId, fallbackBranch)
        val comparison = compareTrackedChapters(
            chapters = chapters.filter { it.branch == anchorBranch },
            id = ChapterEntity::chapterId,
            lastChapterId = track.lastChapterId,
            uploadDate = ChapterEntity::uploadDate,
            lastChapterDate = track.lastChapterDate,
            historyChapterId = readChapterId,
        )
        if (!comparison.isValid) {
            chaptersDao.replaceAll(mangaId, chapters)
            return ChapterSnapshotResult.Stored
        }
        fresh = comparison.newChapters
    } else {
        val diff = diffChapterSnapshots(old, chapters, workTitle)
        if (diff.reuploads.isNotEmpty()) rekeyUpdateChapters(mangaId, diff.reuploads)
        anchorId = diff.reuploads.entries.firstOrNull { it.key.chapterId == track.lastChapterId }?.value?.chapterId
            ?: track.lastChapterId
        if (diff.fresh.size > REIDENTIFIED_LIST_MIN_FRESH && diff.fresh.size * 2 > chapters.size) {
            chaptersDao.replaceAll(mangaId, chapters)
            return ChapterSnapshotResult.Stored
        }
        fresh = diff.fresh
    }

    val branch = branchBeingRead(chapters, old, readChapterId, anchorId, fallbackBranch)
    val branchChapters = chapters.filter { it.branch == branch }
    val reported = reportedNewChapters(
        newChapters = fresh.filter { it.branch == branch },
        branch = branchChapters,
        id = ChapterEntity::chapterId,
        uploadDate = ChapterEntity::uploadDate,
        readChapterId = readChapterId,
        now = now,
    )
    chaptersDao.replaceAll(mangaId, chapters)
    if (reported.isNotEmpty()) {
        recordUpdateChapters(mangaId, reported, now)
        if (markDuplicatesRead) {
            for (chapter in readDuplicates(reported, branchChapters, readChapterId, workTitle)) {
                completeUpdateChapter(mangaId, chapter.chapterId, now)
            }
        }
    }
    branchChapters.lastOrNull()?.let { last ->
        if (last.chapterId != track.lastChapterId || anchorId != track.lastChapterId) {
            getTracksDao().upsert(TrackEntity(
                mangaId = track.mangaId,
                lastChapterId = last.chapterId,
                newChapters = getTracksDao().find(mangaId)?.newChapters ?: track.newChapters,
                lastCheckTime = track.lastCheckTime,
                lastChapterDate = trackedLastChapterDate(reported.map { it.uploadDate }, last.uploadDate, now)
                    .takeIf { it > 0L } ?: track.lastChapterDate,
                lastResult = track.lastResult,
                lastError = track.lastError,
            ))
        }
    }
    return ChapterSnapshotResult.Updated(reported)
}

private fun branchBeingRead(
    chapters: List<ChapterEntity>,
    old: List<ChapterEntity>,
    readChapterId: Long,
    anchorId: Long,
    fallbackBranch: String?,
): String? {
    if (readChapterId != 0L) {
        (chapters.firstOrNull { it.chapterId == readChapterId } ?: old.firstOrNull { it.chapterId == readChapterId })
            ?.let { return it.branch }
    }
    if (anchorId != 0L) {
        (chapters.firstOrNull { it.chapterId == anchorId } ?: old.firstOrNull { it.chapterId == anchorId })
            ?.let { return it.branch }
    }
    if (fallbackBranch != null && chapters.any { it.branch == fallbackBranch }) return fallbackBranch
    return chapters.last().branch
}

/** The [reported] chapters whose trusted number a chapter at or before the reading position already has. */
private fun readDuplicates(
    reported: List<ChapterEntity>,
    branch: List<ChapterEntity>,
    readChapterId: Long,
    workTitle: String,
): List<ChapterEntity> {
    val readIndex = branch.indexOfFirst { it.chapterId == readChapterId }
    if (readIndex < 0) return emptyList()
    val numbers = branch.trustedNumbers(workTitle)
    val readNumbers = branch.take(readIndex + 1).mapNotNullTo(HashSet()) { numbers[it] }
    return reported.filter { chapter -> numbers[chapter]?.let { it in readNumbers } == true }
}
